# NesStation

一个为 Android 手机与 Android TV 打造的高质感 FC / NES 模拟器。
核心采用业界精度与兼容性最好的 **FCEUmm**（FCEUX 的现代化 C/C++ 重构版），
通过 NDK 编译为原生库，提供 60FPS 流畅运行、即时存档、跨设备同步和 TV 模式 D-pad 焦点导航。

> 主界面参考 Pico-8 / Analogue Pocket 的视觉语言：像素云朵天空 + 玻璃拟态卡片 + 圆角高亮。

---

## ✨ 主要特性

| 模块 | 功能 |
| --- | --- |
| **核心** | FCEUmm 核心（NTSC / PAL），自动存档槽位 ×5 |
| **画面** | 多种滤镜（Nearest、Bilinear、CRT-Shader）、画面比例、横竖屏旋转、扫描线 |
| **音频** | 低延迟 OpenSL / AAudio 音频后端，音量独立可调 |
| **输入** | 屏幕手柄（NES 标准布局）、蓝牙手柄 / 键盘 / TV D-pad，自定义按键映射 |
| **游戏库** | 自动扫描 `.nes` ROM、收藏、最近游玩、搜索、封面 |
| **存档** | 即时存档、即时读档、电池存档（Flash / SRAM / Mapper 电池）、自动存档 |
| **截图** | 一键截图，保存到相册 |
| **快进/慢放** | 2×/4× 快进，慢动作 |
| **TV 模式** | Android TV 适配，Leanback 启动、横屏 D-pad 焦点、远距离 UI |
| **设置** | 主题、TV 模式开关、按键重映射、性能调优、清理缓存 |

---

## 📁 目录结构

```
NesStation/
├── app/                # 手机 / 默认 module
│   ├── src/main/       # Kotlin + Compose 主代码
│   ├── src/main/jni/   # 原生层（壳入口）
│   └── src/main/res/
├── core/               # FCEUmm 核心源码 + JNI（以 git submodule 形式集成）
│   ├── fceumm/         # 第三方核心源码
│   ├── jni/            # C++ ↔ Kotlin 桥接
│   └── cmake/          # CMakeLists
├── tv/                 # TV mode 专有 UI（焦点、Leanback）
├── .github/workflows/  # 手动触发构建
└── docs/               # 截图、设计说明
```

> 注意：本仓库 **不包含任何受版权保护的游戏 ROM**。请仅使用你自己 dump 的、合法的 ROM。

---

## 🚀 快速开始

### 克隆

```bash
git clone https://github.com/<your-username>/NesStation.git
cd NesStation
git submodule update --init --recursive
```

如果你只想先跑起来看看 UI，可以在 gradle.properties 里加 `useStubCore=true`，
这样会用 `core/native-stub` 下的占位库（不会跑真正的游戏，但能编译过）。

### 环境

- Android Studio Hedgehog (2023.1.1) 或更新
- Android SDK 34
- NDK 26.1.10909125
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

### 手动触发 GitHub Actions

在仓库页面 **Actions** 标签 → 选择 `Android Build` → **Run workflow** → 选好变体（`debug` / `release`）→ 运行。
APK 会在 workflow 完成后作为 artifact 上传。

---

## 🎮 控制 / 手柄映射

| NES | 键盘（TV/手柄） | 屏幕按钮 |
| --- | --- | --- |
| A | X | A 键 |
| B | Z | B 键 |
| Start | Enter | Start |
| Select | Shift | Select |
| Up/Down/Left/Right | 方向键 | 十字键 |

支持自定义映射：进入 **设置 → 按键映射**。

---

## 🧩 添加核心

核心以 git submodule 形式存在。如果你 fork 后希望替换成其他核心（如 Nestopia、Mesen），
可以修改 `core/fceumm` 路径并调整 `core/jni/bridge.cpp` 的接口即可。

---

## 📜 许可证

- 应用代码：MIT
- FCEUmm：GPLv2（请遵守上游协议）

---

## 🙌 致谢

- FCEUX / FCEUmm 团队
- libretro 项目灵感
- Analogue / Pico-8 视觉风格
