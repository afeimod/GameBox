# RetroBox 模拟器合集

一个基于 Android + Jetpack Compose 的多平台复古游戏模拟器合集应用，支持 FC/NES、SFC/SNES、MD/Genesis、街机(Arcade/MAME) 等平台，内置精美赛博朋克霓虹风格的虚拟手柄，支持在线下载 ROM。

## 功能特性

### 模拟器核心
- **FC/NES** — 基于 FCEUX/Nestopia 引擎，支持 `.nes` `.fds` `.unf` `.nez`
- **SFC/SNES** — 基于 Snes9x 引擎，支持 `.smc` `.sfc` `.fig` `.bs`
- **MD/Genesis** — 基于 Genesis Plus GX 引擎，支持 `.md` `.gen` `.smd` `.bin`
- **街机/Arcade** — 基于 MAME 引擎，支持 `.zip` `.7z`
- 统一的 `EmulatorCore` 接口，支持热插拔核心
- `CoreManager` 按文件扩展名自动选择最优核心
- `EmulatorThread` 独立线程渲染 + AudioTrack 音频输出

### 虚拟手柄（核心亮点）
- **赛博朋克霓虹视觉风格** — 深色背景 + 发光按钮 + 渐变描边
- **4 套预设主题** — 霓虹赛博 / 复古游戏 / 极简暗黑 / 发光紫色
- **3 种布局模式** — 标准 / 紧凑 / 自定义
- **完整手柄组件**：
  - 十字方向键（8 方向含对角线，极坐标算法）
  - A/B/X/Y 菱形动作键（各自独立颜色与发光）
  - L1/R1/L2/R2 肩键（L 型设计）
  - Start/Select 系统键
- **交互手势**：
  - 按下缩放动画 + 发光增强 + 触觉震动反馈
  - 单指拖拽移动手柄位置
  - 双指捏合缩放（0.5x ~ 2.5x）
  - 双指点击切换显示/隐藏
- **全可配置** — 按钮大小、间距、透明度、震动开关，实时预览
- **按键映射** — 每个平台独立映射表（基于 Android KeyEvent 码值），支持 JSON 序列化持久化

### 在线下载系统
- **Gitee 仓库集成** — 通过 Gitee API v5 拉取游戏列表与下载 ROM
- **JS 脚本** — `assets/gitee_download.js`，支持 Node.js / 浏览器环境
- **多任务下载** — 并发控制、断点续传（HTTP Range）、通知栏进度
- **自动扫描** — 仓库有 `gamelist.json` 时解析结构化列表；无则自动扫描目录
- **平台筛选与搜索**

### 游戏库管理
- 自动扫描本地 ROM 目录
- 按平台分类、关键字搜索
- 游玩记录（次数、上次游玩时间）
- 存档管理（多存档槽位）

## 项目结构

```
RetroBox/
├── app/src/main/java/com/retrobox/
│   ├── RetroBoxApp.kt              # 全局 Application
│   ├── MainActivity.kt             # 入口 Activity + 导航
│   ├── ui/
│   │   ├── theme/                  # 主题配色（赛博朋克霓虹）
│   │   ├── navigation/             # 路由定义
│   │   ├── components/             # 虚拟手柄 UI 组件
│   │   │   ├── VirtualGamepad.kt   # 手柄主容器
│   │   │   ├── GamepadButton.kt    # 可复用按钮
│   │   │   ├── DPad.kt             # 十字方向键
│   │   │   ├── ActionButtons.kt    # A/B/X/Y 动作键
│   │   │   ├── ShoulderButtons.kt  # L/R 肩键
│   │   │   ├── GamepadOverlay.kt   # 覆盖层（拖拽/缩放/隐藏）
│   │   │   └── GamepadTheme.kt     # 主题预设
│   │   ├── screens/                # 功能页面
│   │   │   ├── LibraryScreen.kt    # 游戏库
│   │   │   ├── EmulatorScreen.kt   # 游戏运行界面
│   │   │   ├── DownloadScreen.kt   # 在线下载
│   │   │   ├── SettingsScreen.kt   # 设置
│   │   │   └── GamepadSettingsScreen.kt  # 手柄设置
│   │   └── viewmodel/
│   │       └── GameViewModel.kt    # 主 ViewModel
│   ├── emulator/                   # 模拟器核心框架
│   │   ├── EmulatorCore.kt         # 核心接口
│   │   ├── CoreManager.kt          # 核心管理器
│   │   ├── EmulatorThread.kt       # 渲染/音频线程
│   │   └── cores/                  # 各平台核心实现
│   ├── download/                   # 下载系统
│   │   ├── GiteeClient.kt          # Gitee API 客户端
│   │   ├── DownloadManager.kt      # 下载管理器
│   │   └── GameListParser.kt       # 列表解析器
│   ├── input/                      # 输入配置
│   │   └── ButtonConfig.kt         # 按键配置/映射
│   └── data/                       # 数据层
│       ├── GameInfo.kt             # 游戏信息模型
│       ├── GameRepository.kt       # 游戏仓库
│       └── PreferenceManager.kt    # 偏好设置
├── app/src/main/assets/
│   └── gitee_download.js           # Gitee 下载 JS 脚本
└── app/src/main/res/               # 资源文件
```

## 构建

### 环境要求
- JDK 17+
- Android SDK 34 (compileSdk)
- Android Gradle Plugin 8.2+
- Kotlin 1.9+

### 编译
```bash
# 设置 Java 17
export JAVA_HOME=/path/to/jdk17

# 编译 Debug APK
./gradlew assembleDebug

# 输出 APK
# app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

### 1. 配置 Gitee 仓库
进入 **设置 → Gitee 仓库配置**，填写：
- **Owner** — Gitee 用户名/组织名
- **Repo** — 仓库名（如 `roms-repo`）
- **Branch** — 分支名（默认 `master`）
- **Token** — 私有令牌（可选，私有仓库需要）

### 2. 仓库目录结构
推荐在 Gitee 仓库中按平台分目录存放 ROM：
```
roms-repo/
├── fc/
│   ├── super-mario.nes
│   └── contra.nes
├── sfc/
│   └── chronotrigger.smc
├── md/
│   └── sonic.md
├── arcade/
│   └── 1942.zip
└── gamelist.json    # 可选：结构化游戏列表
```

### 3. gamelist.json 格式（可选）
```json
[
  {
    "name": "超级马里奥",
    "platform": "FC",
    "fileSize": 40960,
    "downloadUrl": "https://gitee.com/owner/repo/raw/master/fc/mario.nes",
    "coverUrl": "https://gitee.com/owner/repo/raw/master/fc/mario.png",
    "description": "经典横版过关游戏",
    "romUrl": "fc/mario.nes"
  }
]
```

### 4. 使用 JS 脚本（独立运行）
```javascript
// Node.js 环境
const { CONFIG, fetchGameList, downloadGame } = require('./gitee_download.js');

// 修改配置
CONFIG.GITEE_OWNER = 'your-username';
CONFIG.GITEE_REPO = 'roms-repo';

// 获取 FC 游戏列表
fetchGameList('FC').then(games => {
  console.log(`找到 ${games.length} 个游戏`);
  // 下载第一个
  return downloadGame(games[0], './downloads/' + games[0].fileName);
}).then(path => {
  console.log('已下载到:', path);
});
```

### 5. 手柄设置
进入 **设置 → 虚拟手柄设置**：
- 切换 4 套主题
- 选择布局模式（标准/紧凑/自定义）
- 调节按钮大小、方向键大小、间距、透明度
- 开关触觉反馈
- 查看各平台按键映射
- 实时预览

### 6. 游戏中操作
- **点击底部** — 显示/隐藏菜单栏
- **单指拖拽空白区域** — 移动手柄位置
- **双指捏合** — 缩放手柄
- **双指点击** — 切换手柄显示/隐藏
- 菜单栏支持：暂停/恢复、存档/读档、退出

## 接入原生引擎

当前各核心（`NESCore` `SNECore` `GenesisCore` `ArcadeCore`）的引擎调用以 `TODO` 标注，接入步骤：

1. 编译对应引擎的 JNI 库（如 FCEUX、Snes9x、Genesis Plus GX、MAME）
2. 将 `.so` 文件放入 `app/src/main/jniLibs/{abi}/` 目录
3. 在各 Core 实现类中通过 `System.loadLibrary()` 加载并调用 native 方法
4. 实现 `getFrameBuffer()` / `getAudioBuffer()` 返回引擎数据

## 技术栈
- **Kotlin** + **Jetpack Compose** (BOM 2024.02.00)
- **Material 3** 主题系统
- **Navigation Compose** 页面导航
- **OkHttp + Retrofit** 网络请求
- **Coroutines** 异步处理
- **Coil** 图片加载
- **Accompanist** 系统栏控制

## License
MIT
