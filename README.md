# NesStation (GameBox)

一个为 Android 手机与 Android TV 打造的高质感多平台复古游戏模拟器。

支持 **9 大平台**：NES / SFC / GB / GBA / PCE / DOS / Arcade / MD / Java ME，通过
统一的 Compose UI 与一致的游戏内菜单体验，让你在 TV 大屏和手机小屏上都能
畅玩从 8-bit 到街机的所有经典游戏。

| 平台 | 核心 | 文件扩展名 | 是否需要 BIOS |
| --- | --- | --- | --- |
| **NES / FC**   | FCEUmm            | `.nes` `.fds` `.unf`           | FDS 游戏需要 `disksys.rom` |
| **SNES / SFC** | snes9x            | `.smc` `.sfc` `.fig` `.swc`   | 否 |
| **GB / GBC**   | mGBA              | `.gb` `.gbc` `.sgb`           | 否 |
| **GBA**        | mGBA              | `.gba`                        | 否 |
| **PCE / TG16** | **Geargrafx**     | `.pce` `.sgx` `.hes` `.cue` `.chd` | PCE-CD 游戏需要 System Card（`syscard1/2/3.pce`、`gameexpress.pce`） |
| **DOS**        | DOSBox-Pure       | `.bat` `.exe` `.dosz` `.conf` `.iso` | 否 |
| **Arcade**     | **FBNeo**         | `.zip` `.7z`                  | NeoGeo / PGM / Mega-CD 游戏需要 |
| **MD / SEGA**  | **Genesis-Plus-GX** | `.md` `.smd` `.sms` `.gg` `.sg` `.cue` `.chd` | Mega-CD 游戏需要 |
| **Java ME**    | J2ME-Loader       | `.jar` `.jad`                 | 否 |

> 主界面参考 Pico-8 / Analogue Pocket 的视觉语言：像素云朵天空 + 玻璃拟态卡片 + 圆角高亮。

---

## ✨ 主要特性

| 模块 | 功能 |
| --- | --- |
| **9 大核心** | NES（FCEUmm）、SNES（snes9x）、GB/GBC/GBA（mGBA）、PCE/TG16（Geargrafx）、DOS（DOSBox-Pure）、Arcade（FBNeo）、MD/SEGA（Genesis-Plus-GX）、Java ME（J2ME-Loader） |
| **画面** | 多种滤镜（Nearest、Scanline、CRT、Dot、XBR、HQ2X、HQ4X），画面比例（4:3 / 16:9 / 自动 / 拉伸），横竖屏旋转 |
| **音频** | 48000 Hz 重采样 + AudioTrack BLOCKING 写入，消除 TV HDMI 输出爆音 |
| **输入** | 屏幕手柄（可自定义布局）、蓝牙手柄 / 键盘 / TV D-pad 焦点导航、自定义按键映射；PCE 布局编辑器支持逐键显示/隐藏（含 Turbo 连发键） |
| **游戏库** | 横向平台分类（NES / SFC / GB / GBA / PCE / DOS / Arcade / MD / Java），自动扫描 ROM，收藏、最近游玩、搜索、封面 |
| **游戏库刷新** | 刷新按钮重扫**所有**已导入的文件夹（SAF 文件夹 + 本地文件夹），自动补全新 ROM、清理已删除/失效条目；无导入文件夹时回退扫描标准 ROM 目录 |
| **存档** | 10 个存档槽位 + 自动存档 + 电池存档（SRAM / Flash / Mapper 电池） |
| **截图** | 一键截图保存到相册 |
| **快进/慢放** | 2×/4×/6×/8× 快进 |
| **游戏内菜单** | 返回键唤起，包含：暂停/恢复、存档/读档、快进、截图、设置（引擎专属选项）、退出 |
| **引擎独立设置** | 每个核心有独立的设置项（NES NTSC 滤镜、SNES 图层、GBA 颜色预设、Arcade 旋转、MD 区域选择等） |
| **自定义图标** | 长按游戏卡片可自定义图标，支持从相册选取图片 |
| **TV 模式** | Android TV 适配，Leanback 启动、横屏 D-pad 焦点、远距离 UI |
| **J2ME 悬浮菜单** | 全屏沉浸式刘海屏适配，浮动菜单支持滤镜选择、暂停/恢复、屏幕布局、退出 |

---

## 📁 目录结构

```
GameBox-main/
├── app/                              # 主 module（手机 + TV + 全部核心的 UI）
│   ├── src/main/
│   │   ├── java/com/nesstation/app/
│   │   │   ├── core/
│   │   │   │   ├── model/            # GameEntry, GamePlatform 等数据模型
│   │   │   │   ├── storage/          # RomStore, RomScanner, PadLayoutStore
│   │   │   │   ├── engine/           # NesEngine, SnesEngine, GbaEngine, PceEngine,
│   │   │   │   │                     # DosEngine, FbNeoEngine, GenesisEngine (9 大引擎)
│   │   │   │   └── jni/              # NesNative, SnesNative, GbaNative, PceNative,
│   │   │   │                         # DosNative, FbNeoNative, GenesisNative
│   │   │   └── ui/                   # Compose UI（Home, Library, Settings,
│   │   │                             #   EmulatorScreen, etc.）
│   │   ├── java/javax/microedition/  # J2ME-Loader 引擎代码
│   │   ├── cpp/m3g/                  # M3G 3D 渲染原生代码（J2ME）
│   │   ├── assets/
│   │   │   ├── fbneo/                # FBNeo BIOS 文件目录（自带 README）
│   │   │   ├── genesis/              # Genesis-Plus-GX BIOS 文件目录
│   │   │   ├── legal/                # 各核心的开源许可证
│   │   │   ├── scanner/              # ROM 扫描配置
│   │   │   └── disksys.rom           # FDS BIOS（已内置）
│   │   ├── jniLibs/
│   │   │   ├── arm64-v8a/            # 预编译 .so（FBNeo / Genesis-Plus-GX /
│   │   │   ├── armeabi-v7a/          #   DOSBox-Pure libretro 核心）
│   │   │   └── x86_64/
│   │   └── res/                      # 资源文件
│   ├── build.gradle.kts              # 构建配置
│   ├── proguard-rules.pro            # ProGuard/R8 混淆规则
│   └── multidex-config.pro           # MultiDex 保留规则
├── core/                             # 原生核心源码 + JNI 桥接
│   ├── fceumm/                       # FCEUmm 核心（git submodule）
│   ├── snes9x/                       # snes9x 核心（git submodule）
│   ├── mgba/                         # mGBA 核心（vendored）
│   ├── jni/                          # C++ ↔ Kotlin 桥接 + libretro 前端
│   │   ├── bridge.cpp / rom_loader.cpp         # NES（FCEUmm）
│   │   ├── snes_bridge.cpp / snes_loader.cpp   # SNES（snes9x）
│   │   ├── gba_bridge.cpp / gba_loader.cpp     # GBA（mGBA）
│   │   ├── pce_bridge.cpp / pce_loader.cpp     # PCE/TG16（Geargrafx，dlopen 预编译核心）
│   │   ├── dos_bridge.cpp / dos_loader.cpp     # DOS（DOSBox-Pure）
│   │   ├── fbneo_bridge.cpp / fbneo_loader.cpp # Arcade（FBNeo）
│   │   ├── genesis_bridge.cpp / genesis_loader.cpp  # MD（Genesis-Plus-GX）
│   │   ├── shared/                   # 共享 libretro.h + 工具
│   │   └── hqx/                      # HQ2X / HQ4X 视频滤镜算法
│   ├── cmake/CMakeLists.txt          # CMake 入口
│   └── native-stub/                  # 占位核心（无 submodule 时用）
├── dexlib/                           # J2ME DEX 转换库（独立 module）
├── scripts/
│   ├── download_prebuilt_cores.sh    # 下载预编译 .so 文件
│   └── check_bios_files.sh           # 检查 BIOS 文件完整性
├── .github/workflows/                # CI 构建
└── docs/                             # 截图、设计说明
```

> 注意：本仓库 **不包含任何受版权保护的游戏 ROM 或 BIOS 文件**。请仅使用你自己合法获取的游戏文件。BIOS 占位文件在 `app/src/main/assets/fbneo/` 和 `app/src/main/assets/genesis/` 中有详细说明。

---

## 🎮 各核心说明

### NES / FC（FCEUmm 核心）
- 业界精度与兼容性最好的 NES 模拟核心
- 支持 NTSC / PAL / Dendy 三种区域
- 支持 FDS 磁碟游戏（需 `disksys.rom` BIOS，已内置在 assets）
- NTSC 滤镜（composite / s-video / RGB）、调色板选择、超频
- 自动存档 ×5 槽位 + 10 个手动存档槽

### SNES / SFC（snes9x 核心）
- snes9x 是跨平台兼容性最好的 SNES 核心
- 支持 5 个图层开关（BG1 / BG2 / BG3 / BG4 / OBJ）
- 高分辨率模式（hires）、图形透明、减少精灵闪烁
- 音频插值（同步 / 异步）
- 阻止无效 VRAM 写入、允许上下同时输入

### GB / GBC / GBA（mGBA 核心）
- mGBA 是最活跃维护的高精度 GB/GBC/GBA 核心
- GBC 颜色预设（默认 / GB ASP / GBC LCD / GBA LCD 等）
- GBA 颜色预设（默认 / GBA LCD / GBA SP 101 等）
- 帧跳类型（自动 / 无 / 帧跳 / VBI）
- 强制 RTC（实时时钟，用于宝可梦等游戏）
- 允许相反方向输入

### PCE / TG16（Geargrafx 核心）
- Geargrafx 支持 PC-Engine / TurboGrafx-16 / SuperGrafx / PCE-CD
- 主机类型（自动 / PC Engine 日版 / SuperGrafx / TurboGrafx-16 美版）
- 画面比例（1:1 PAR / 4:3 DAR / 6:5 DAR / 16:9 DAR / 16:10 DAR）
- 过扫描裁剪、精灵数量上限开关、调色板（Standard RGB / Turboxray / Kitrinx）
- 支持 5 人 TurboTap 手柄扩展、Memory Base 128 存档、允许相反方向输入
- **虚拟按键显隐**：布局编辑器内可单独显示/隐藏每个按键（十字键 / I / II / RUN / SELECT / V / VI / IV / III / TURBO I / TURBO II），适合清理屏幕或仅保留常用键
- **BIOS 需求**：卡带游戏（`.pce` / `.sgx`）与 HES 音乐（`.hes`）无需 BIOS；PCE-CD 光盘游戏（`.cue` / `.chd`）需要 System Card（`syscard3.pce` 最常见），存放在系统目录

### DOS（DOSBox-Pure 核心）
- DOSBox-Pure 是为 RetroArch 优化的 DOSBox 分支
- 支持 .bat 启动器导入整个游戏文件夹
- 机器型号（SVGA / VGA / EGA / CGA / Tandy / PCjr）
- CPU 速度（自动 / 486 / 386 / 286 / 8086）、声卡类型（SB16 / SB Pro / GUS）
- 鼠标输入模式（手柄 / 键盘）、暗屏超时
- Voodoo 显卡模拟、强制 60fps

### **Arcade / 街机（FBNeo 核心）**
- FBNeo（Final Burn Neo）支持 CPS1 / CPS2 / CPS3 / NeoGeo / PGM / ST-V 等
- 街机游戏存储为 `.zip` / `.7z` 文件（zip 本身就是 ROM）
- **6 键街机布局**：A B X Y L R + Select(Coin) + Start
- NeoGeo 模式切换（MVS 街机 / AES 家用）
- 画面旋转（横向 / 竖向射击游戏必需）、竖屏模式
- CPU 速度调节（50% / 75% / 100% / 150% / 200% / 250%）
- 音频插值（最近邻 / 线性 / 三次）+ 低通滤波
- **BIOS 需求**：NeoGeo 游戏 → `neogeo.zip`，PGM 游戏（三国战纪/魔窟）→ `pgm.zip`，详见 `assets/fbneo/README.txt`

### **MD / SEGA（Genesis-Plus-GX 核心）**
- Genesis-Plus-GX 支持 Mega Drive / Master System / Game Gear / SG-1000 / Mega-CD
- ⚠️ **不支持 SEGA Saturn（SS）** — SS 需要单独的 Yabause / Mednafen 核心
- **3 键 / 6 键手柄切换**（经典 3 键 / 街机 6 键）
- 区域选择（自动 / NTSC-U 美 / PAL 欧 / NTSC-J 日）
- 系统型号（自动 / MD / SMS / GG / SG）
- NTSC 滤镜（黑白 / RF / 复合 / S-Video / RGB）、LCD 滤镜
- Game Gear 扩展屏幕（160×144 → 256×144）、画面拉伸
- Master System FM 音源（自动 / 开 / 关）
- Mega-CD CD 快速启动（跳过 BIOS 动画）
- 超频（100% / 125% / 150% / 200%）
- **BIOS 需求**：MD/SMS/GG/SG 卡带游戏无需 BIOS；Mega-CD 光盘游戏需要 `bios_CD_E/J/U.zip`，详见 `assets/genesis/README.txt`

### J2ME / Java ME（J2ME-Loader 引擎）
- 支持 `.jar` 格式的 Java ME 游戏 / MIDlet
- 通过 DexClassLoader 动态加载游戏 DEX 文件
- 完整兼容 MIDP 2.0 / CLDC 1.1 规范
- M3G 3D 渲染（OpenGL ES 1.1）+ Mascot Capsule Micro3D
- 9 种视频滤镜（无 / 扫描线 / CRT / 点阵 / XBR / 4XBR / XBR+点阵 / 4XBR+点阵 / HQ4x）
- 滤镜直接作用于游戏渲染管线而非全屏覆盖
- 独立的 J2ME 滤镜偏好存储（与 NES 滤镜完全隔离）

---

## 🎮 控制 / 手柄映射

### NES / SNES / GBA / Arcade / MD

| 按键 | 屏幕按钮 | 物理手柄 / 键盘 |
| --- | --- | --- |
| Up / Down / Left / Right | 十字键 | 方向键 / D-pad |
| A | A | X / Button A |
| B | B | Z / Button B |
| X (SNES/MD/Arcade) | X | S / Button X |
| Y (SNES/MD/Arcade) | Y | A / Button Y |
| L (SNES/GBA/MD/Arcade) | L | Q / Button L1 |
| R (SNES/GBA/MD/Arcade) | R | W / Button R1 |
| Start | Start | Enter / Button Start |
| Select / Coin (Arcade) | Select | Shift / Button Select |

支持自定义映射：进入 **设置 → 按键映射**。

### PCE / TG16 专属
PCE 使用与 SNES 相同的共享屏幕布局槽位，但按钮名映射为 PCE 原生按键：

| 屏幕按钮 | PCE 原生按键 | 物理手柄 / 键盘 |
| --- | --- | --- |
| Up / Down / Left / Right | 十字键 | 方向键 / D-pad |
| A | **I**（动作 / 部分游戏攻击） | X / Button A |
| B | **II**（跳跃 / 部分游戏射击） | Z / Button B |
| X | **IV** | S / Button X |
| Y | **III** | A / Button Y |
| L | **V** | Q / Button L1 |
| R | **VI** | W / Button R1 |
| L2 | **TURBO II**（II 连发开关） | L2 |
| R2 | **TURBO I**（I 连发开关） | R2 |
| Start | RUN | Enter / Button Start |
| Select | SELECT | Shift / Button Select |

> 大多数 2 键 PCE 游戏只用到 I（A）和 II（B）；IV/III/V/VI 用于部分 6 键格斗游戏。连发开关（TURBO I/II）按下后开启/关闭对应键的自动连发。

### Arcade（FBNeo）专属
- Select 键 = **投币（Coin）** — 街机游戏必须投币才能开始
- Start 键 = 开始游戏 / 服务菜单
- 6 键布局映射：A=Btn1, B=Btn2, X=Btn3, Y=Btn4, L=Btn5, R=Btn6
- 4 键格斗游戏（KOF、街霸 2）只用 A/B/X/Y
- 6 键格斗游戏（街霸 Zero、恶魔战士）全部使用

### MD / SEGA 专属
- 3 键游戏：A=SEGA A（跳）、B=SEGA B（攻击）、Start、C=SEGA C（冲刺）
- 6 键游戏：A=SEGA A, B=SEGA B, X=SEGA C, Y=SEGA X, L=SEGA Y, R=SEGA Z
- Select = Mode（6 键手柄模式切换）
- 在设置中切换手柄类型（3 键 / 6 键）

### J2ME
J2ME 游戏使用 J2ME-Loader 的虚拟键盘系统，支持：
- 屏幕虚拟按键（可自定义布局）
- 蓝牙手柄 / 键盘映射
- 长按游戏卡片 → 设置 → 进入 J2ME 配置页面进行按键重映射

---

## 🔧 BIOS 文件管理

### FDS（NES 磁碟游戏）
- 已内置 `assets/disksys.rom`（8192 字节）
- 启动 FDS 游戏时自动加载，无需手动操作

### FBNeo 街机 BIOS
- **必需**：NeoGeo 游戏 → `neogeo.zip`，PGM 游戏（三国战纪/魔窟）→ `pgm.zip`
- CPS1/CPS2/CPS3 游戏无需 BIOS
- BIOS 文件位置：`<filesDir>/fbneo/`
- **两种添加方式**：
  1. **打包到 APK**（私有构建）：放入 `app/src/main/assets/fbneo/`，启动时自动解压
  2. **运行时导入**：游戏中按返回键 → 设置 → Arcade BIOS 管理 → 导入
- 详见 `app/src/main/assets/fbneo/README.txt`

### Genesis-Plus-GX（Mega-CD）BIOS
- **必需**：Mega-CD / SEGA-CD 光盘游戏 → `bios_CD_E.zip`（欧）/ `bios_CD_J.zip`（日）/ `bios_CD_U.zip`（美）
- 卡带游戏（MD/SMS/GG/SG）无需 BIOS
- BIOS 文件位置：`<filesDir>/genesis/`
- **两种添加方式**：同 FBNeo
- 详见 `app/src/main/assets/genesis/README.txt`

### Geargrafx（PCE-CD）BIOS
- **必需**：PCE-CD 光盘游戏 → `syscard3.pce`（System Card 3 / Arcade Card Pro，最常用）
- 可选：`syscard1.pce` / `syscard2.pce`（旧 System Card）/ `gameexpress.pce`（少量游戏需要）
- 卡带游戏（`.pce` / `.sgx`）与 HES 音乐（`.hes`）无需 BIOS
- BIOS 文件位置：`<filesDir>/pce/`
- **添加方式**：游戏中按返回键 → 设置 → PCE BIOS 导入，从文件选择器导入 `.pce` 文件并自动命名

> ⚠️ **法律声明**：所有 BIOS 文件（neogeo.zip、pgm.zip、bios_CD_*.zip 等）都包含受版权保护的代码（SNK、IGS、SEGA 等）。本仓库不包含任何 BIOS 文件，仅提供占位说明文档。你只能将合法获取的 BIOS 文件打包到私有 APK 中供个人使用，不能在公开渠道（GitHub、应用商店等）分发包含 BIOS 的 APK。

---

## 🚀 快速开始

### 克隆

```bash
git clone https://github.com/<your-username>/NesStation.git
cd NesStation
git submodule update --init --recursive
```

### 一键下载预编译核心

FBNeo 和 Genesis-Plus-GX 使用 dlopen() 模式加载预编译的 libretro 核心。
预编译 .so 文件已包含在仓库中（`app/src/main/jniLibs/`），如需更新到最新版本：

```bash
./scripts/download_prebuilt_cores.sh                # 全部 3 个核心
./scripts/download_prebuilt_cores.sh fbneo           # 仅 FBNeo
./scripts/download_prebuilt_cores.sh genesis         # 仅 Genesis-Plus-GX
./scripts/download_prebuilt_cores.sh dosbox          # 仅 DOSBox-Pure
```

### 检查 BIOS 文件状态

```bash
./scripts/check_bios_files.sh
```

### 添加 BIOS 文件（可选 — 仅 NeoGeo / PGM / Mega-CD 游戏需要）

```bash
# FBNeo BIOS（neogeo.zip, pgm.zip 等）
cp /path/to/neogeo.zip app/src/main/assets/fbneo/
cp /path/to/pgm.zip    app/src/main/assets/fbneo/

# Genesis-Plus-GX Mega-CD BIOS
cp /path/to/bios_CD_E.zip app/src/main/assets/genesis/
cp /path/to/bios_CD_J.zip app/src/main/assets/genesis/
cp /path/to/bios_CD_U.zip app/src/main/assets/genesis/
```

启动 App 时 `NesApp.ensureFbNeoBios()` 和 `ensureGenesisBios()` 会自动解压到 `<filesDir>/fbneo/` 和 `<filesDir>/genesis/`。

### 环境

- Android Studio Hedgehog (2023.1.1) 或更新
- Android SDK 34
- NDK 26.1.10909125（或 26.3.11579264）
- CMake 3.22.1
- JDK 17
- Gradle 8.7+

### 构建

```bash
# 调试构建
./gradlew :app:assembleDebug

# 安装到已连接的设备
./gradlew :app:installDebug
```

### 构建注意事项

1. **J2ME 原生代码编译**：M3G 3D 渲染模块（`app/src/main/cpp/m3g/`）需要 NDK 编译。CMakeLists.txt 中已配置 `-Wno-int-conversion` 等编译器标志以兼容 NDK 26。

2. **DataBinding**：J2ME-Loader 的布局文件使用 DataBinding，已在 `build.gradle.kts` 中启用 `dataBinding { enabled = true }`。

3. **ProGuard/R8**：Release 构建启用了代码混淆。`proguard-rules.pro` 中已添加各核心 JNI 类的 keep 规则（`javax.**`、`ru.playsoftware.j2meloader.**`、`com.mascotcapsule.**`、`com.nesstation.app.core.jni.**` 等）。

4. **MultiDex**：J2ME-Loader 代码量较大，已启用 `multiDexEnabled = true`。

5. **预编译 .so 文件**：FBNeo / Genesis-Plus-GX / DOSBox-Pure / Geargrafx（PCE）的 libretro 核心通过 dlopen() 模式加载，预编译 .so 文件位于 `app/src/main/jniLibs/<abi>/`。FCEUmm / snes9x / mGBA 从源码编译（需要 git submodule）。

6. **`useStubCore`**：如果只想跑起来看 UI，可以在 `gradle.properties` 里设置 `useStubCore=true`，使用占位核心。

### 手动触发 GitHub Actions

在仓库页面 **Actions** 标签 → 选择 `Android Build` → **Run workflow** → 选好变体（`debug` / `release`）→ 运行。
APK 会在 workflow 完成后作为 artifact 上传。

---

## 🧩 游戏内菜单

游戏中按 **返回键** 唤起游戏内菜单（所有核心统一）：

| 菜单项 | 功能 |
| --- | --- |
| **继续游戏** | 关闭菜单，恢复游戏 |
| **暂停 / 恢复** | 暂停或恢复模拟 |
| **快进** | 2× / 4× / 6× / 8× 速度选择 |
| **存档** | 选择槽位（0-9）保存当前状态 |
| **读档** | 选择槽位（0-9）读取保存的状态 |
| **截图** | 保存当前画面到相册 |
| **设置** | 引擎专属选项（每个核心不同） |
| **退出** | 退出游戏，返回游戏库 |

**引擎专属设置**（在设置菜单内，按平台分组）：

- **NES**：NTSC 滤镜、调色板、区域、超频、裁剪过扫描
- **SNES**：图层开关、减少精灵闪烁、减少卡顿、音频插值、图形透明、高分辨率、阻止无效 VRAM
- **GBA**：GBC/GBA 颜色预设、帧跳类型、强制 RTC、允许相反方向输入
- **PCE/TG16**：主机类型、画面比例、过扫描、精灵数量上限、调色板、CD-ROM BIOS、TurboTap、MB128、允许相反方向输入
- **DOS**：机器型号、CPU 速度、声卡、鼠标输入、键盘布局、Voodoo、暗屏超时
- **Arcade**：画面比例、旋转、竖屏模式、CPU 速度、跳帧、采样率、音频插值、NeoGeo 模式（MVS/AES）、记忆卡、BIOS 管理
- **MD/SEGA**：区域、系统型号、画面比例、渲染模式、NTSC 滤镜、LCD 滤镜、过扫描、GG 扩展屏幕、手柄类型（3/6 键）、超频、跳帧、Mega-CD 快速启动、SMS FM 音源、BIOS 管理

---

## 🧩 添加新核心

### 从源码编译的核心（NES / SNES / GBA）
1. 添加 git submodule 到 `core/<name>/`
2. 在 `core/cmake/CMakeLists.txt` 中添加 `add_library(...)` 配置
3. 创建 `core/jni/<name>_bridge.cpp` 和 `<name>_loader.cpp`
4. 创建 `app/src/main/java/.../core/jni/<Name>Native.kt`（JNI 接口）
5. 创建 `app/src/main/java/.../core/engine/<Name>Engine.kt`（高层引擎类）
6. 在 `EmulatorEngine.forPlatform()` 中添加平台路由
7. 在 `PadLayoutStore.kt` 中添加引擎专属设置字段
8. 在 `EmulatorScreen.kt` 中添加设置 UI 和 `applyCoreOptions()` 分支

### dlopen 模式的核心（PCE / Arcade / MD / DOS）
1. 用 `./scripts/download_prebuilt_cores.sh <name>` 下载预编译 .so
2. 步骤同上 3-8
3. CMakeLists.txt 中 `target_link_libraries(... dl)` 添加 `dl` 库
4. 在 `NesApp.kt` 中设置 `appContext` 并调用 `ensureLoaded()`
5. 在 `NesApp.kt` 中添加 `ensure<Bios>()` 方法（如需要 BIOS）
6. 在 `assets/<name>/` 中添加 BIOS 占位 README

---

## 📜 许可证

| 组件 | 许可证 |
| --- | --- |
| 应用代码 | MIT |
| FCEUmm（NES 核心） | GPLv2 — 见 `assets/legal/LICENSE-FCEUmm.txt` |
| snes9x（SNES 核心） | 非商业 — 见 `assets/legal/LICENSE-snes9x.txt` |
| mGBA（GBA 核心） | MPL-2.0 — 见 `assets/legal/LICENSE-mGBA.txt` |
| DOSBox-Pure（DOS 核心） | GPLv2 — 见 `assets/legal/LICENSE-DOSBox-Pure.txt` |
| FBNeo（Arcade 核心） | 非商业 — 见 `assets/legal/LICENSE-FBNeo.txt` |
| Genesis-Plus-GX（MD 核心） | GPLv2 — 见 `assets/legal/LICENSE-Genesis-Plus-GX.txt` |
| J2ME-Loader | Apache License 2.0 |
| M3G 3D 引擎 | Apache License 2.0 |
| HQ2X / HQ4X 算法 | Maxim Stepin（免费使用） |
| XBR 算法 | Hyllian / Zenju（免费使用） |

详细的 ROM / BIOS 法律声明见 `app/src/main/assets/legal/ROM_NOTICE.txt`。

---

## 🙌 致谢

- **FCEUX / FCEUmm 团队** — NES 模拟核心
- **snes9x 团队** — SNES 模拟核心
- **mGBA 团队**（Vicki Pfau 等）— GB/GBC/GBA 模拟核心
- **DOSBox-Pure 团队**（Markus Mertama 等）— DOS 模拟核心
- **FBNeo 团队**（基于 Dave 的 FinalBurn）— Arcade 模拟核心
- **Genesis-Plus-GX 团队**（Eke-Eke，基于 Charles MacDonald 的 Genesis Plus）— SEGA 模拟核心
- **J2ME-Loader 项目**（[nikita-shakarun](https://github.com/nikita-shakarun/j2me-loader)） — J2ME/Java ME 模拟引擎
- **libretro 项目** — 提供 .so 预编译核心的 buildbot
- **Analogue / Pico-8** — 视觉风格灵感
- **XBR 算法** by Hyllian / Zenju
- **HQ4x 算法** by Maxim Stepin
