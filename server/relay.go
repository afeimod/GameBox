package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"
)

// ---------------------------------------------------------------------------
// 房间模型
// ---------------------------------------------------------------------------

type roomStatus int

const (
	roomWaiting roomStatus = iota // 等待玩家加入
	roomReady                     // 双方已就绪（客户端正在下载/加载 ROM）
	roomPlaying                   // 对战中
)

type Room struct {
	ID        string
	GameID    string
	Status    roomStatus
	CreatedAt time.Time
	// 已发送 ready 的玩家数。双方都 ready 后才发送 start。
	readyCount int
	// 玩家连接（socket 已建立）。key 为 user id。
	conns map[string]*relayConn
}

func (r *Room) playerCount() int { return len(r.conns) }

// ---------------------------------------------------------------------------
// 中继连接
// ---------------------------------------------------------------------------

// relayMsg 帧同步消息。输入用 JSON 编码（小巧且便于调试）。
// 协议：客户端 -> 服务器 -> 对端。
//   {"type":"input","frame":123,"pad":0x00FF}
//   {"type":"hello",...}  握手
//   {"type":"start",...}  服务器通知开始
//   {"type":"end",...}    结束
type relayMsg struct {
	Type  string `json:"type"`
	Frame int64  `json:"frame,omitempty"`
	Pad   int    `json:"pad,omitempty"`
	// 握手字段
	Token  string `json:"token,omitempty"`
	RoomID string `json:"roomId,omitempty"`
	UserID string `json:"userId,omitempty"`
	// 通知字段
	Msg string `json:"msg,omitempty"`
}

type relayConn struct {
	conn net.Conn
	enc  *json.Encoder
	user *User
	room *Room
	// 对端引用（快速访问）
	peer *relayConn
}

func (rc *relayConn) send(m relayMsg) error {
	rc.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return rc.enc.Encode(m)
}

func (rc *relayConn) close() {
	_ = rc.conn.Close()
}

// ---------------------------------------------------------------------------
// 中继中心
// ---------------------------------------------------------------------------

type relayHub struct {
	cfg   *Config
	jwt   *jwt
	users *userStore

	mu    sync.Mutex
	rooms map[string]*Room // 等待/进行中的房间
}

func newRelayHub(cfg *Config, jwt *jwt, users *userStore) *relayHub {
	return &relayHub{cfg: cfg, jwt: jwt, users: users, rooms: map[string]*Room{}}
}

// roomByID 获取房间
func (h *relayHub) roomByID(id string) *Room {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.rooms[id]
}

// handleConn 处理一条 TCP 连接：先握手，再进入房间中继
func (h *relayHub) handleConn(conn net.Conn) {
	defer conn.Close()
	conn.SetReadDeadline(time.Now().Add(15 * time.Second))

	reader := bufio.NewReader(conn)
	// 读第一行：握手消息
	line, err := reader.ReadString('\n')
	if err != nil {
		return
	}
	var hello relayMsg
	if err := json.Unmarshal([]byte(line), &hello); err != nil || hello.Type != "hello" {
		_ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "握手消息格式错误"})
		return
	}

	// 校验 token（JWT）
	claims, err := h.jwt.verify(hello.Token)
	if err != nil {
		_ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "token 无效: " + err.Error()})
		return
	}

	h.mu.Lock()
	room, ok := h.rooms[hello.RoomID]
	if !ok {
		h.mu.Unlock()
		_ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "房间不存在"})
		return
	}
	if room.playerCount() >= 2 {
		h.mu.Unlock()
		_ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "房间已满"})
		return
	}
	if _, exists := room.conns[claims.Sub]; exists {
		h.mu.Unlock()
		_ = json.NewEncoder(conn).Encode(relayMsg{Type: "error", Msg: "已在房间中"})
		return
	}

	rc := &relayConn{
		conn: conn,
		enc:  json.NewEncoder(conn),
		user: h.users.byID(claims.Sub),
		room: room,
	}
	room.conns[claims.Sub] = rc
	playerCount := room.playerCount()
	h.mu.Unlock()

	conn.SetReadDeadline(time.Time{}) // 清除超时

	// 通知对方
	other := h.peerOf(room, claims.Sub)
	if other != nil {
		other.peer = rc
		rc.peer = other
		_ = other.send(relayMsg{Type: "peer_joined", Msg: rc.user.Username})
	}

	// 向本端发送 hello 回执
	role := "host"
	if playerCount == 2 {
		role = "guest"
	}
	_ = rc.send(relayMsg{
		Type: "hello",
		Msg:  role,
	})

	if playerCount == 2 {
		// 双方到齐，但暂不发送 start。等待双方都发送 ready 后再发送 start。
		// 这样双方可以从同一帧（frame 0）同时开始，保证锁步同步。
	}

	// 中继循环：读本端输入，转发给对端
	h.relayLoop(rc, reader)
}

// relayLoop 读取一条输入消息，原样转发给对端
func (h *relayHub) relayLoop(rc *relayConn, reader *bufio.Reader) {
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			break
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		var msg relayMsg
		if err := json.Unmarshal([]byte(line), &msg); err != nil {
			continue
		}

		switch msg.Type {
		case "input":
			// 转发给对端
			if rc.peer != nil && rc.peer.conn != nil {
				if err := rc.peer.send(relayMsg{Type: "input", Frame: msg.Frame, Pad: msg.Pad}); err != nil {
					rc.peer.close()
					h.drop(rc)
					return
				}
			}
		case "ready":
			// 客户端已加载完 ROM。追踪 readyCount，双方都 ready 后发送 start。
			h.mu.Lock()
			room := rc.room
			if room != nil {
				room.readyCount++
				if room.readyCount >= 2 {
					h.mu.Unlock()
					h.markReady(room)
				} else {
					h.mu.Unlock()
				}
			}
		case "ping":
			_ = rc.send(relayMsg{Type: "pong"})
		case "bye":
			// 主动退出
			h.drop(rc)
			return
		}
	}
	// 连接断开
	h.drop(rc)
}

// drop 断开连接：通知对端、清理房间
func (h *relayHub) drop(rc *relayConn) {
	h.mu.Lock()
	room := rc.room
	if room != nil {
		if rc.peer != nil {
			_ = rc.peer.send(relayMsg{Type: "peer_left", Msg: rc.user.Username})
			rc.peer.peer = nil
		}
		delete(room.conns, rc.user.ID)
		if room.playerCount() == 0 {
			delete(h.rooms, room.ID)
		}
	}
	h.mu.Unlock()
	rc.close()
}

// peerOf 返回 room 中与 userID 对战的另一端
func (h *relayHub) peerOf(room *Room, userID string) *relayConn {
	h.mu.Lock()
	defer h.mu.Unlock()
	for id, c := range room.conns {
		if id != userID {
			return c
		}
	}
	return nil
}

// markReady 双方都 ready，通知开始
func (h *relayHub) markReady(room *Room) {
	h.mu.Lock()
	defer h.mu.Unlock()

	if room.Status != roomWaiting {
		return
	}
	room.Status = roomReady
	room.readyCount = 0

	// 给双方发送 start（附带延迟帧数）
	for _, c := range room.conns {
		delay := h.cfg.InputDelay
		if delay <= 0 {
			delay = 4
		}
		_ = c.send(relayMsg{
			Type: "start",
			Msg:  fmt.Sprintf("%d", delay),
		})
	}
}

// jwtToken / users 由 newRelayHub 注入
