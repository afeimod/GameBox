// GameBox 对战平台服务器
//
// 零第三方依赖（仅 Go 标准库），构建：
//   cd server && go build -o gamebox-server .
// 运行：
//   ./gamebox-server -config config.json
//
// 功能：
//   - 虚拟账号注册/登录（JWT 鉴权）
//   - 游戏列表 + ROM 统一分发（服务器中转下载，可在配置里指向 GitHub 直链）
//   - 房间系统（创建 / 列表 / 加入 / 离开）
//   - 帧同步中继（TCP）：对战双方输入互相转发，锁步 + 输入延迟
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	configPath := flag.String("config", "config.json", "配置文件路径")
	flag.Parse()

	cfg, err := loadConfig(*configPath)
	if err != nil {
		log.Fatalf("加载配置失败: %v", err)
	}

	store, err := newUserStore(cfg.DataDir)
	if err != nil {
		log.Fatalf("初始化用户存储失败: %v", err)
	}

	jwter := newJWT(cfg.JWTSecret, 7*24*time.Hour)
	hub := newRelayHub(cfg, jwter, store)

	api := &apiServer{
		cfg:      cfg,
		users:    store,
		hub:      hub,
		jwt:      jwter,
		romCache: newRomCache(cfg, cfg.RomCacheMax),
	}

	// TCP 中继服务器（帧同步）
	tcpLn, err := net.Listen("tcp", cfg.AddressTCP())
	if err != nil {
		log.Fatalf("监听 TCP %s 失败: %v", cfg.AddressTCP(), err)
	}
	go func() {
		log.Printf("帧同步中继监听: tcp://%s", cfg.AddressTCP())
		for {
			conn, err := tcpLn.Accept()
			if err != nil {
				// 监听器已关闭（优雅退出）——直接退出，避免死循环刷日志
				if errors.Is(err, net.ErrClosed) {
					return
				}
				log.Printf("TCP accept 错误: %v", err)
				time.Sleep(100 * time.Millisecond)
				continue
			}
			go hub.handleConn(conn)
		}
	}()

	// HTTP API 服务器
	srv := &http.Server{
		Addr:              cfg.AddressHTTP(),
		Handler:           api.routes(),
		ReadHeaderTimeout: 10 * time.Second,
		WriteTimeout:      30 * time.Minute, // ROM 大文件下载需要足够时间
	}

	go func() {
		log.Printf("HTTP API 监听: http://%s", cfg.AddressHTTP())
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("HTTP 服务器错误: %v", err)
		}
	}()

	// 优雅退出
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop
	log.Println("正在关闭服务器...")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
	_ = tcpLn.Close()
	_ = store.Close()
	log.Println("已退出")
	os.Exit(0)
}
