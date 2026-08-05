# NesStation (GameBox)

一个为 Android 手机与 Android TV 打造的高质感多平台复古游戏模拟器。

- **NES / FC 模拟**：核心采用业界精度与兼容性最好的 **FCEUmm**（FCEUX 的现代化 C/C++ 重构版），通过 NDK 编译为原生库，提供 60FPS 流畅运行、即时存档、跨设备同步和 TV 模式 D-pad 焦点导航。
- **J2ME / Java ME 模拟**：内置 **J2ME-Loader** 引擎，支持运行 `.jar` 格式的 Java ME 游戏/MIDlet，通过 DexClassLoader 动态加载，完整兼容 MIDP 2.0 / CLDC 1.1 规范。

> 主界面参考 Pico-8 / Analogue Pocket 的视觉语言：像素云朵天空 + 玻璃拟态卡片 + 圆角高亮。

---

## ✨ 主要特性

| 模块 | 功能 |
| --- | --- |
| **NES 核心** | FCEUmm 核心（NTSC / PAL），自动存档槽位 ×5 |
| **J2ME 核心** | J2ME-Loader 引擎，支持 `.jar` 安装与运行，MIDP 2.0 / CLDC 1.1，M3G 3D 渲染 |
| **画面 — NES** | 多种滤镜（Nearest、Bilinear、CRT-Shader）、画面比例、横竖屏旋转、扫描线 |
| **画面 — J2ME** | 9 种视频滤镜（无滤镜、扫描线、CRT、点阵、XBR、4XBR、XBR+点阵、4XBR+点阵、HQ4x），滤镜直接作用于游戏画面而非全屏覆盖 |
| **音频** | 低延迟 OpenSL / AAudio 音频后端（NES），J2ME 独立音频系统（MIDI Driver） |
| **输入** | 屏幕手柄（NES 标准布局）、蓝牙手柄 / 键盘 / TV D-pad，自定义按键映射；J2ME 虚拟键盘 + 按键重映射 |
| **游戏库** | 横向平台分类（NES / Java），自动扫描 `.nes` ROM，手动安装 `.jar`，收藏、最近游玩、搜索、封面 |
| **自定义图标** | 所有界面长按游戏卡片可自定义图标，支持从相册选取图片 |
| **存档** | 即时存档、即时读档、电池存档（Flash / SRAM / Mapper 电池）、自动存档 |
| **截图** | 一键截图，保存到相册 |
| **快进/慢放** | 2×/4× 快进，慢动作（NES） |
| **J2ME 悬浮菜单** | 全屏沉浸式刘海屏适配，浮动菜单支持滤镜选择、暂停/恢复、屏幕布局、退出 |
| **TV 模式** | Android TV 适配，Leanback 启动、横屏 D-pad 焦点、远距离 UI |
| **设置** | 主题、TV 模式开关、按键重映射、性能调优、清理缓存 |

---

## 📁 目录结构

```
NesStation/
├── app/                              # 主 module（手机 + TV + J2ME）
│   ├── src/main/
│   │   ├── java/com/nesstation/app/  # Kotlin + Compose 主代码
│   │   │   ├── core/
│   │   │   │   ├── model/            # GameEntry, GamePlatform 等数据模型
│   │   │   │   ├── storage/          # RomStore, JavaGameStore（存储层）
│   │   │   │   ├── engine/           # NesEngine（NES 模拟引擎）
│   │   │   │   └── jni/              # NES JNI 桥接
│   │   │   └── ui/                   # Compose UI（Home, Library, Settings 等）
│   │   ├── java/javax/microedition/  # J2ME-Loader 引擎代码
│   │   │   ├── shell/                # MicroActivity（J2ME 游戏入口）
│   │   │   ├── lcdui/                # UI 渲染层（Canvas, Image 等）
│   │   │   │   ├── graphics/         # 滤镜系统（J2meBitmapFilter, J2meFilterShaders 等）
│   │   │   │   └── keyboard/         # 虚拟键盘
│   │   │   ├── media/                # 音频/媒体
│   │   │   └── util/                 # 工具类
│   │   ├── java/ru/playsoftware/     # J2ME-Loader 配置/设置模块
│   │   ├── cpp/m3g/                  # M3G 3D 渲染原生代码
│   │   ├── jni/                      # NES 原生层（壳入口）
│   │   └── res/                      # 资源文件（含 J2ME 布局/字符串）
│   ├── build.gradle.kts              # 构建配置（含 J2ME 依赖、DataBinding、ProGuard）
│   ├── proguard-rules.pro            # ProGuard/R8 混淆规则
│   └── multidex-config.pro           # MultiDex 保留规则
├── dexlib/                           # J2ME DEX 转换库（独立 module）
├── core/                             # FCEUmm 核心源码 + JNI
│   ├── fceumm/                       # 第三方核心源码（git submodule）
│   ├── jni/                          # C++ ↔ Kotlin 桥接
│   └── cmake/                        # CMakeLists
├── .github/workflows/                # CI 构建
└── docs/                             # 截图、设计说明
```

> 注意：本仓库 **不包含任何受版权保护的游戏 ROM 或 JAR 文件**。请仅使用你自己合法获取的游戏文件。

---

## 🎮 J2ME / Java ME 集成

### 安装 Java 游戏

1. 在游戏库界面切换到 **Java** 分类标签
2. 点击右上角 **+** 按钮
3. 选择 `.jar` 文件（支持多选）
4. 安装完成后游戏会出现在 Java 分类列表中

### 启动 Java 游戏

点击游戏卡片即可启动。Java 游戏通过 `Config.startApp()` 启动 `MicroActivity`，使用 DexClassLoader 动态加载游戏的 DEX 文件。

### J2ME 悬浮菜单

游戏中通过悬浮菜单可进行以下操作：

| 菜单项 | 功能 |
| --- | --- |
| **滤镜** | 弹出滤镜选择对话框，支持 9 种滤镜（见下方滤镜系统） |
| **暂停/恢复** | 暂停或恢复 MIDlet 线程 |
| **屏幕布局** | 切换屏幕缩放/布局模式 |
| **退出** | 弹出退出确认对话框 |

游戏界面强制全屏沉浸式模式，支持刘海屏/挖孔屏（`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`）。

### J2ME 视频滤镜系统

J2ME 游戏内置 9 种视频滤镜，所有滤镜**直接作用于游戏渲染管线**，而非全屏覆盖：

| 模式 | 滤镜名称 | 类型 | 实现方式 |
| --- | --- | --- | --- |
| 0 | 无滤镜 | — | 原始画面，最近邻缩放 |
| 1 | 扫描线 | 遮罩 | GL mode: GLSL 片段着色器 / 非 GL: Canvas 遮罩绘制 |
| 2 | CRT | 遮罩 | GL mode: GLSL 片段着色器 / 非 GL: Canvas 遮罩绘制 |
| 3 | 点阵 | 遮罩 | GL mode: GLSL 片段着色器 / 非 GL: Canvas 遮罩绘制 |
| 4 | XBR | 像素处理 | CPU 边缘自适应插值，2× 放大后 NEAREST 绘制 |
| 5 | 4XBR | 像素处理 | CPU 边缘自适应插值，4× 放大后 NEAREST 绘制 |
| 6 | XBR+点阵 | 像素处理 + 遮罩 | XBR 处理后叠加点阵遮罩 |
| 7 | 4XBR+点阵 | 像素处理 + 遮罩 | 4XBR 处理后叠加点阵遮罩 |
| 8 | HQ4x | 像素处理 | CPU HQ4x 算法，4× 放大后 NEAREST 绘制 |

**渲染管线架构：**

- **GL 模式**（`graphicsMode == 1`）：
  - 像素处理滤镜：`J2meBitmapFilter.applyFilter()` 在 CPU 上处理游戏 Bitmap → 生成放大后的 Bitmap → 上传为 GL 纹理 → 使用 passthrough 着色器 + NEAREST 过滤绘制
  - 遮罩滤镜：编译对应的 GLSL 片段着色器 → 在 GPU 上应用遮罩效果
  - 无滤镜：使用默认着色器，LINEAR 或 NEAREST 过滤

- **非 GL 模式**（`graphicsMode == 0/2/3`）：
  - 所有滤镜通过 `J2meBitmapFilter.drawFiltered()` 在 Canvas 上处理
  - 像素处理滤镜：先处理 Bitmap 再绘制
  - 遮罩滤镜：先绘制原始 Bitmap 再叠加遮罩图案

**关键文件：**

| 文件 | 作用 |
| --- | --- |
| `J2meBitmapFilter.java` | CPU 端滤镜实现（XBR/4XBR/HQ4x 像素处理 + 扫描线/CRT/点阵 Canvas 遮罩） |
| `J2meFilterShaders.java` | GLSL 片段着色器源码（扫描线/CRT/点阵遮罩着色器） |
| `Canvas.java` | 渲染管线核心，管理 GL/non-GL 模式下的滤镜路由和着色器切换 |
| `ShaderProgram.java` | GLSL 着色器程序管理（编译、链接、uniform 绑定） |
| `MicroActivity.java` | 悬浮菜单与滤镜选择对话框 |

**J2ME 滤镜偏好独立存储：** 滤镜选择保存在 `j2me_prefs` SharedPreferences 中，与 NES 滤镜设置完全隔离，避免串滤镜。

---

## 🚀 快速开始

### 克隆

```bash
git clone https://github.com/<your-username>/NesStation.git
cd NesStation
git submodule update --init --recursive
```

如果你只想先跑起来看看 UI，可以在 gradle.properties 里加 `useStubCore=true`，
这样会用 `core/native-stub` 下的占位库（不会跑真正的 NES 游戏，但能编译过）。

### 环境

- Android Studio Hedgehog (2023.1.1) 或更新
- Android SDK 34
- NDK 26.1.10909125（或 26.3.11579264）
- CMake 3.22.1
- JDK 17
- Gradle 8.7+

### 构建

```bash
./gradlew :app:assembleDebug
# 安装
./gradlew :app:installDebug
```

### TV 版

```bash
./gradlew :app:assembleTvDebug
```

### 构建注意事项

1. **J2ME 原生代码编译**：M3G 3D 渲染模块（`app/src/main/cpp/m3g/`）需要 NDK 编译。CMakeLists.txt 中已配置 `-Wno-int-conversion` 等编译器标志以兼容 NDK 26。

2. **DataBinding**：J2ME-Loader 的布局文件使用 DataBinding，已在 `build.gradle.kts` 中启用 `dataBinding { enabled = true }`。

3. **ProGuard/R8**：Release 构建启用了代码混淆。`proguard-rules.pro` 中已添加 J2ME 相关类的 keep 规则（`javax.**`、`ru.playsoftware.j2meloader.**`、`com.mascotcapsule.**` 等），防止 J2ME 运行时类被混淆器裁剪导致 `ExceptionInInitializerError`。

4. **MultiDex**：J2ME-Loader 代码量较大，已启用 `multiDexEnabled = true`。`multidex-config.pro` 指定了需要保留在主 DEX 中的类。

5. **包名统一**：J2ME-Loader 原始包名 `ru.playsoftware.j2meloader` 已统一使用 `com.nesstation.app` 作为 Application ID，`BuildConfig` 引用已全部替换。

### 手动触发 GitHub Actions

在仓库页面 **Actions** 标签 → 选择 `Android Build` → **Run workflow** → 选好变体（`debug` / `release`）→ 运行。
APK 会在 workflow 完成后作为 artifact 上传。

---

## 🎮 控制 / 手柄映射

### NES

| NES | 键盘（TV/手柄） | 屏幕按钮 |
| --- | --- | --- |
| A | X | A 键 |
| B | Z | B 键 |
| Start | Enter | Start |
| Select | Shift | Select |
| Up/Down/Left/Right | 方向键 | 十字键 |

支持自定义映射：进入 **设置 → 按键映射**。

### J2ME

J2ME 游戏使用 J2ME-Loader 的虚拟键盘系统，支持：
- 屏幕虚拟按键（可自定义布局）
- 蓝牙手柄 / 键盘映射
- 长按游戏卡片 → 设置 → 进入 J2ME 配置页面进行按键重映射

---

## 🧩 添加核心

### NES 核心

核心以 git submodule 形式存在。如果你 fork 后希望替换成其他核心（如 Nestopia、Mesen），
可以修改 `core/fceumm` 路径并调整 `core/jni/bridge.cpp` 的接口即可。

### J2ME 核心

J2ME 引擎代码已直接集成在 `app/src/main/java/javax/microedition/` 目录下，无需额外 submodule。
DEX 转换库在 `dexlib/` module 中。如需更新 J2ME-Loader 引擎，替换对应 Java 源码即可。

---

## 📜 许可证

- 应用代码：MIT
- FCEUmm：GPLv2（请遵守上游协议）
- J2ME-Loader：Apache License 2.0（请遵守上游协议）
- M3G 3D 引擎：Apache License 2.0

---

## 🙌 致谢

- FCEUX / FCEUmm 团队 — NES 模拟核心
- J2ME-Loader 项目（[nikita-shakarun](https://github.com/nikita-shakarun/j2me-loader)） — J2ME/Java ME 模拟引擎
- libretro 项目灵感
- Analogue / Pico-8 视觉风格
- XBR 算法 by Hyllian / Zenju
- HQ4x 算法 by Maxim Stepin
