# GameBox 修复说明（本次提交）

本次共修复 4 个问题，全部为源码级修复，无需额外资源；直接用 Android Studio 打开工程构建 APK 即可。

---

## 修复 1：PS2 游戏始终 4:3，不跟随全局「画面缩放」设置

**根因**：PS2 核心（ARMSX2）是"推模型"核心，它会在拿到的 Surface 内部按自己的 AspectRatio 配置画黑边。旧代码把 PS2 专属设置 `ps2_aspect_ratio`（默认 `auto`）直接透传给核心，`auto` 在 ARMSX2 里映射为 `RAuto4_3_3_2` → 永远按 4:3 输出。全局 `videoScale` 只约束了 SurfaceView 的形状，核心仍在里面画 4:3 黑边 → 表现为"始终 4:3"。

**修复**（`EmulatorScreen.kt` applyCoreOptions PS2 分支）：
- PS2 专属设置为 `auto`（默认）时，改为跟随全局「画面缩放」推导：
  - 全局 4:3 → 核心 4:3；全局 16:9 → 核心 16:9；
  - 全局 stretch / custom / 2:3 / 8:3 / 3:2 / 8:7 → 核心 Stretch（拉满 Surface，形状由 Compose 侧控制，与其它平台行为完全一致）。
- 仅当用户在 PS2 专属设置里显式锁定 4:3 / 16:9 时才优先专属设置。
- `videoScale` 加入核心选项的 LaunchedEffect 触发列表 → **游戏中**改全局画面缩放也即时生效。
- 两处 PS2「画面比例」下拉（设置页核心设置 + 游戏内菜单）的 "auto" 选项文案改为「跟随全局画面缩放」，语义明确。

**涉及文件**：`ui/emulator/EmulatorScreen.kt`、`ui/settings/CoreSettingsPanel.kt`

---

## 修复 2：全局 FPS 显示不准（PS2 永远 ~60，PS1 只显示限制器目标值）

**根因**：
- PS2（ARMSX2）：前端没有模拟循环，旧实现靠"心跳线程"按固定间隔（1000/targetHz ms）调用 onFrame 计数 —— 无论游戏实际跑多快/多慢，FPS 永远显示 ~60。
- PS1（PCSX-ReARMed）：旧实现用模拟循环步进计数，它恒等于帧率限制器目标值（NTSC 60 / PAL 50），游戏内部掉帧（很多 PS1 游戏是 30/20fps）也看不出来。

**修复**：
- 新增 `EmulatorEngine.realtimeFps()` 接口：核心能提供真实帧率时返回 >0，UI 优先采用。
- **PS2**：直接轮询 ARMSX2 核心内部的 `PerformanceMetrics::GetFPS()`（`NativeApp.getFPS()`），即 PCSX2 OSD 同源数据 —— 掉帧数值下降、快进数值上升。
- **PS1**：native 层（`core/jni/psx_loader.cpp`）在视频回调 `cb_video` 里统计核心**真实提交**的帧数（非空 data 才计，重复帧/跳帧不计），Kotlin 每秒轮询并按真实流逝时间换算帧率。
- 其它平台（NES/SFC/GBA/NDS/MD/PCE/DOS/ARCADE）保持原有帧计数 —— 拉模型核心每步恰好一帧，计数本身就是真实帧率，无需改动。

**涉及文件**：`core/engine/EmulatorEngine.kt`、`core/engine/Psx2Engine.kt`、`core/jni/Psx2Native.kt`、`core/engine/PsxEngine.kt`、`core/jni/PsxNative.kt`、`core/jni/psx_loader.cpp`、`core/jni/psx_loader.h`、`core/jni/psx_bridge.cpp`、`core/jni/psx_bridge.h`、`ui/emulator/EmulatorScreen.kt`

---

## 修复 3：主页卡片透明度调节"没有应用到所有卡片"

**根因**：`FsdTile` 的 Compose 修饰符顺序错误 —— `.background(不透明深蓝底).alpha(x)` 中，背景画在 alpha 层之外，永远 100% 不透明。拖动滑杆只能淡出渐变表面/图标/文字，卡片本体永不透光，看起来就是"透明度没有生效"。

**修复**（`ui/fsd/FsdTileMenu.kt`）：
- 把 `.alpha(...)` 移到 `.clip()/.background()` **之前**，整张卡片（底色、蓝/黄渐变表面、图标、徽标、文字）一起变透明。
- 选中磁贴的黄色高亮边框保持在 alpha 层之外（焦点指示不受透明度影响）。
- 磁贴选项对话框新增「**将此透明度应用到所有卡片**」按钮 —— 一次设置全部磁贴统一生效（`ui/fsd/FsdHomeScreen.kt`）。

---

## 修复 4：在线游戏 / SWF 页面与游戏库效果不一致

**根因**：在线游戏和 SWF 列表仍使用旧版浅色像素风（PixelBackdrop + 平铺白色卡片网格），没有跟进游戏库的 FSD 桌面改版。

**修复**（两个页面整体重构为与游戏库同款效果）：
- FSD 深蓝壁纸（FsdBackdrop）+ 顶部系统状态条（FsdTopBar）+ 底部状态条（FsdBottomBar）
- **3D 封面流**（FsdCoverFlow：居中放大、两侧缩放淡出、倒影）替代平铺网格
- 新增 `FsdIconCoverCard` 封面卡片组件（`ui/fsd/FsdCoverCard.kt`）：深蓝底 + 白边框 + 强调色对角渐变"封面" + 居中大图标 + 徽标（WEB/SWF/PC/手机）+ 底部渐变标题条 —— 与游戏库 FsdGameCover 同一视觉语言
- 补齐 FsdBreadcrumb 面包屑、FsdTitleBanner 标题横幅、FsdCounter「N of M」计数、FsdButtonHints 手柄按键提示
- FsdToolButton 提为公共组件（`ui/fsd/FsdScaffold.kt`），三个页面共用
- D-pad/手柄导航（TV 友好）：左右切卡、OK/A 启动、Y 选项、B 返回主页
- SWF「浏览文件」Tab 改为 FSD 深色列表行；扫描按钮改为 FsdToolButton
- **原有功能全部保留**：添加在线游戏（UA 模式）、删除/移除确认弹窗、SWF 双 Tab、文件夹浏览、扫描添加、Snackbar 提示

**涉及文件**：`ui/online/OnlineGamesScreen.kt`（重构）、`ui/swf/SwfListScreen.kt`（重构）、`ui/fsd/FsdCoverCard.kt`（新增）、`ui/fsd/FsdScaffold.kt`、`ui/library/LibraryScreen.kt`

---

## 构建

与原工程完全一致，无新增依赖、无资源变更：

```
./gradlew assembleRelease
# 或 Android Studio: Build > Generate Signed APK
```

首次构建会按 `core/cmake/CMakeLists.txt` 编译各平台 native 核心（含 ARMSX2 的 libemucore_4k.so），PS1 相关的 C++ 改动（psx_loader/psx_bridge）会一并编译。
