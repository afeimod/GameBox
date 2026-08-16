package main

import (
	"encoding/json"
	"net/http"
	"time"
)

// --- 房间 HTTP API ---

type roomResponse struct {
	ID        string `json:"id"`
	GameID    string `json:"gameId"`
	GameTitle string `json:"gameTitle"`
	Host      string `json:"host"`
	Guest     string `json:"guest"` // 空 = 等待加入
	Status    string `json:"status"`
	CreatedAt int64  `json:"createdAt"`
}

func roomToResponse(cfg *Config, r *Room) roomResponse {
	resp := roomResponse{
		ID:        r.ID,
		GameID:    r.GameID,
		CreatedAt: r.CreatedAt.Unix(),
	}
	// 找到游戏标题
	for _, g := range cfg.Games {
		if g.ID == r.GameID {
			resp.GameTitle = g.Title
			break
		}
	}
	// 主机 = 先加入的玩家
	first := true
	for _, c := range r.conns {
		if first {
			resp.Host = c.user.Username
			first = false
		} else {
			resp.Guest = c.user.Username
		}
	}
	switch r.Status {
	case roomWaiting:
		resp.Status = "waiting"
	case roomReady:
		resp.Status = "ready"
	case roomPlaying:
		resp.Status = "playing"
	}
	return resp
}

// handleListRooms 列出所有等待中的房间
func (s *apiServer) handleListRooms(w http.ResponseWriter, r *http.Request) {
	s.hub.mu.Lock()
	rooms := make([]roomResponse, 0, len(s.hub.rooms))
	for _, rm := range s.hub.rooms {
		if rm.Status == roomPlaying {
			continue
		}
		rooms = append(rooms, roomToResponse(s.cfg, rm))
	}
	s.hub.mu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{"rooms": rooms})
}

// handleCreateRoom 创建房间
func (s *apiServer) handleCreateRoom(w http.ResponseWriter, r *http.Request) {
	_, err := s.requireAuth(r)
	if err != nil {
		writeErr(w, http.StatusUnauthorized, err.Error())
		return
	}

	var req struct {
		GameID string `json:"gameId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "请求格式错误")
		return
	}
	// 校验游戏存在
	gameExists := false
	for _, g := range s.cfg.Games {
		if g.ID == req.GameID {
			gameExists = true
			break
		}
	}
	if !gameExists {
		writeErr(w, http.StatusBadRequest, "游戏不存在")
		return
	}

	s.hub.mu.Lock()
	if len(s.hub.rooms) >= s.cfg.MaxRooms {
		s.hub.mu.Unlock()
		writeErr(w, http.StatusTooManyRequests, "房间数量已达上限")
		return
	}
	rm := &Room{
		ID:        randomID()[:8],
		GameID:    req.GameID,
		Status:    roomWaiting,
		CreatedAt: time.Now().UTC(),
		conns:     map[string]*relayConn{},
	}
	s.hub.rooms[rm.ID] = rm
	s.hub.mu.Unlock()

	writeJSON(w, http.StatusOK, map[string]any{
		"room":    roomToResponse(s.cfg, rm),
		"tcpAddr": s.cfg.PublicTCP(),
	})
}

// handleJoinRoom 加入房间
func (s *apiServer) handleJoinRoom(w http.ResponseWriter, r *http.Request) {
	user, err := s.requireAuth(r)
	if err != nil {
		writeErr(w, http.StatusUnauthorized, err.Error())
		return
	}
	id := r.PathValue("id")

	s.hub.mu.Lock()
	rm, ok := s.hub.rooms[id]
	if !ok {
		s.hub.mu.Unlock()
		writeErr(w, http.StatusNotFound, "房间不存在")
		return
	}
	if rm.Status == roomPlaying {
		s.hub.mu.Unlock()
		writeErr(w, http.StatusConflict, "对局已开始")
		return
	}
	if rm.playerCount() >= 2 {
		s.hub.mu.Unlock()
		writeErr(w, http.StatusConflict, "房间已满")
		return
	}
	for _, c := range rm.conns {
		if c.user.ID == user.ID {
			s.hub.mu.Unlock()
			writeErr(w, http.StatusConflict, "你已在房间中")
			return
		}
	}
	s.hub.mu.Unlock()

	writeJSON(w, http.StatusOK, map[string]any{
		"room":    roomToResponse(s.cfg, rm),
		"tcpAddr": s.cfg.PublicTCP(),
	})
}

// handleLeaveRoom 离开房间
func (s *apiServer) handleLeaveRoom(w http.ResponseWriter, r *http.Request) {
	user, err := s.requireAuth(r)
	if err != nil {
		writeErr(w, http.StatusUnauthorized, err.Error())
		return
	}
	id := r.PathValue("id")

	s.hub.mu.Lock()
	rm, ok := s.hub.rooms[id]
	if ok {
		// 如果存在已连接的 relayConn 则关闭，否则直接删房间
		for uid, c := range rm.conns {
			if uid == user.ID {
				s.hub.mu.Unlock()
				s.hub.drop(c)
				writeJSON(w, http.StatusOK, map[string]any{"ok": true})
				return
			}
		}
		// 房间没有该用户的连接，若无玩家则删除
		if rm.playerCount() == 0 {
			delete(s.hub.rooms, id)
		}
	}
	s.hub.mu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}
