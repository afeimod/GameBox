package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// Game 对战平台游戏条目。ROM 由服务器统一分发（中转下载）。
type Game struct {
	ID       string `json:"id"`       // 游戏 ID（如 kof97）
	Title    string `json:"title"`    // 显示名（如 拳皇97）
	Platform string `json:"platform"` // 平台标识（arcade / gba / ...）
	// ROM 源地址：可以是 GitHub 直链、GitHub Release 资产或任意 HTTP 地址。
	// 服务器作为代理转发给客户端，实现统一分发。
	ROMURL   string `json:"romUrl"`
	FileName string `json:"fileName"` // 客户端保存的文件名（如 kof97.zip）
	Size     int64  `json:"size"`     // ROM 大小（字节），未知为 0
	NeedsBIOS []string `json:"needsBios,omitempty"` // 需要的 BIOS 文件名列表（提示用）
	// 游戏封面图标地址（网络 URL，客户端用 Coil 加载展示在游戏库宫格 / 街机桌面）。
	IconURL string `json:"iconUrl,omitempty"`
}

// Config 服务器配置
type Config struct {
	// HTTP 服务监听地址，如 ":8080" 或 "0.0.0.0:8080"
	HTTPAddr string `json:"httpAddr"`
	// TCP 帧同步中继监听地址，如 ":9090"
	TCPAddr string `json:"tcpAddr"`
	// 对外公布的地址（客户端连接用）。例如 "game.example.com:8080"。
	// 为空时客户端使用请求中的 Host。
	PublicHTTPAddr string `json:"publicHttpAddr"`
	// 对外公布的 TCP 中继地址，例如 "game.example.com:9090"。
	// 为空时使用 PublicHTTPAddr 的主机名 + TCPAddr 的端口。
	PublicTCPAddr string `json:"publicTcpAddr"`

	JWTSecret string `json:"jwtSecret"` // 签密钥，生产环境务必修改
	DataDir   string `json:"dataDir"`   // 用户数据存放目录

	MaxRooms    int `json:"maxRooms"`    // 最大房间数
	InputDelay  int `json:"inputDelay"`  // 帧同步输入延迟（帧），一般 3-5
	RomCacheMax int `json:"romCacheMax"` // ROM 内存缓存上限（字节）

	Games []Game `json:"games"` // 对战平台游戏列表
}

// AddressHTTP 返回 HTTP 监听地址
func (c *Config) AddressHTTP() string {
	if c.HTTPAddr == "" {
		return ":8080"
	}
	return c.HTTPAddr
}

// AddressTCP 返回 TCP 监听地址
func (c *Config) AddressTCP() string {
	if c.TCPAddr == "" {
		return ":9090"
	}
	return c.TCPAddr
}

// PublicHTTP 返回对外公布的 HTTP 地址（不含 scheme）
func (c *Config) PublicHTTP() string {
	if c.PublicHTTPAddr != "" {
		return c.PublicHTTPAddr
	}
	return c.AddressHTTP()
}

// PublicTCP 返回对外公布的 TCP 中继地址
func (c *Config) PublicTCP() string {
	if c.PublicTCPAddr != "" {
		return c.PublicTCPAddr
	}
	// 从 HTTP 地址推断主机名
	host := c.PublicHTTPAddr
	// 去掉端口（如果 PublicHTTPAddr 带端口则保留主机部分）
	hostname := host
	if h, _ := splitHostPort(host); h != "" {
		hostname = h
	}
	_, tcpPort := splitHostPort(c.AddressTCP())
	return fmt.Sprintf("%s:%s", hostname, tcpPort)
}

func (c *Config) validate() error {
	if c.JWTSecret == "" {
		return fmt.Errorf("jwtSecret 不能为空")
	}
	if c.InputDelay < 0 {
		return fmt.Errorf("inputDelay 不能为负数")
	}
	if len(c.Games) == 0 {
		return fmt.Errorf("games 列表为空，请配置至少一个游戏")
	}
	seen := map[string]bool{}
	for _, g := range c.Games {
		if g.ID == "" || g.ROMURL == "" {
			return fmt.Errorf("游戏条目缺少 id 或 romUrl")
		}
		if seen[g.ID] {
			return fmt.Errorf("游戏 id 重复: %s", g.ID)
		}
		seen[g.ID] = true
	}
	return nil
}

// loadConfig 读取 JSON 配置文件
func loadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("读取配置文件失败: %w", err)
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("解析配置文件失败: %w", err)
	}
	if cfg.DataDir == "" {
		cfg.DataDir = filepath.Join(filepath.Dir(path), "data")
	}
	if cfg.MaxRooms <= 0 {
		cfg.MaxRooms = 200
	}
	if cfg.RomCacheMax <= 0 {
		cfg.RomCacheMax = 64 * 1024 * 1024
	}
	if err := cfg.validate(); err != nil {
		return nil, err
	}
	return &cfg, nil
}
