# GameBox 修复说明（本次提交）

本次共修复 7 个问题，全部为源码级修复，无需额外资源；直接用 Android Studio 打开工程构建 APK 即可。

---

## 修复 7：PS2 Vulkan 渲染（启用 + 编译链路修正）与补全缺失的渲染增强设置

**背景**：ARMSX2（PCSX2 fork）核心支持 Vulkan GS 渲染器，但本仓库先前通过 `set(USE_VULKAN OFF ... FORCE)` 关闭了它，且 shaderc 构建所需的 vendored 依赖（spirv-tools / spirv-headers / glslang）缺失，导致 Vulkan 即便打开也无法编译。

**修复**（编译链路）：
- **移除强制关闭**：`core/cmake/CMakeLists.txt` 删掉 `set(USE_VULKAN OFF ... FORCE)` —— Vulkan 按 ARMSX2 默认（`USE_VULKAN=ON`）启用。
- **vendored 依赖补齐**：下载 spirv-tools / spirv-headers / glslang 到 ARMSX2 的 `3rdparty` 并解包；`spirv-tools/external/spirv-headers` 建立符号链接（spirv-tools 的 external CMake 会回落到该目录，否则 SPIRV-Headers target 重复定义）。
- **SPIRV-Headers 目录作用域修正**：`SearchForStuff.cmake` 显式 `set(SPIRV-Headers_SOURCE_DIR ... CACHE STRING "" FORCE)` —— CMake 目录作用域隔离导致 spirv-tools 子目录看不到 spirv-headers 的 `project()` 自动 CACHE 写入，之前会错误地重复 `add_subdirectory(spirv-headers)`。
- `SHADERC_SKIP_TESTS` / `SHADERC_SKIP_EXECUTABLES` 已置 ON（SearchForStuff），glslang 测试默认关闭，无 googletest 拉取风险。

**补全缺失的设置**：ARMSX2 native 已暴露、但 GameBox UI 未接入的 4 项渲染增强设置已补全（native 方法 `renderTvShader` / `renderShadeBoost` / `renderHalfpixeloffset` / `renderPreloading`）：
- **电视滤镜 (TV Shader)** `0..7`：无/Scanline/Diagonal/Triangular/Wave/Lottes CRT/4xRGSS/NxAGSS
- **画面增强 (ShadeBoost)** `disabled|enabled`：开启时亮度/对比度/饱和度/伽马固定 50
- **半像素偏移 (Half-pixel)** `0..5`：Off/Normal/Special/Special Aggressive/Native/Native+纹理偏移
- **纹理预加载** `0..2`：Off/Partial(推荐)/Full
- UI 三处同步：设置面板（`CoreSettingsPanel.kt` PS2·画面）、游戏内快捷菜单（`EmulatorScreen.kt`）、存储层（`PadLayoutStore.kt` 新增 4 字段 + load/save/copy + LaunchedEffect 触发列表），JNI 映射在 `Psx2Native.kt`。

**涉及文件**：`core/cmake/CMakeLists.txt`、`ARMSX2-master/cmake/SearchForStuff.cmake`、`ARMSX2-master/3rdparty/`（vendored 依赖）、`app/.../jni/Psx2Native.kt`、`app/.../storage/PadLayoutStore.kt`、`app/.../settings/CoreSettingsPanel.kt`、`app/.../emulator/EmulatorScreen.kt`

---

## 修复 6：PS2 首次启动 ANR / CHD 黑屏 / CHD 扫描卡顿

**根因**：
- **首启 ANR**：SAF（content://）来源的 PS2/PSX/MD-CD/PCE-CD 游戏走 `loadGameFolder`，首次进入时在**主线程**同步递归复制整个文件夹（CHD/ISO 数 GB）→ 主线程阻塞 → ANR；二次进入命中 fast-path（`destLauncher.exists() && length()>0`）立即返回，所以表现为"第一次挂死、第二次正常"。单文件 content:// 分支同样在主线程 `copyTo` 复制大文件。
- **CHD 黑屏**：旧 fast-path 只看目标文件是否存在/非空。若上次进程在复制大 CHD 中途被杀，残留的**截断文件**会被永久当作完整镜像返回——核心能读出 CHD 头部却读不到游戏数据 → BIOS 动画后黑屏，且无法自愈。
- **扫描卡顿**：`LibraryScreen.refreshList()`（按钮/导入回调触发）在**主线程**同步重扫所有已导入文件夹，每个新文件还要 `extractTitle` 读文件头；含大量 CHD 的目录尤其明显，且 `RomStore.add()` 每次全量重写 SharedPreferences。

**修复**：
- **大文件复制移 IO 线程**（`EmulatorScreen.kt`）：DOS / Mega-CD / PCE-CD / PSX+PS2 四个 `loadGameFolder` 分支及单文件 content:// 大文件 `copyTo`，全部用 `withContext(Dispatchers.IO) { ... }` 包裹——首次启动不再阻塞主线程。
- **复制完成标记自愈黑屏**：`loadGameFolder` 新增 `<filesDir>/<subDir>/<safeId>/.<safeId>.copy_ok` 标记文件。fast-path 额外要求标记存在；仅当整份复制成功写盘后才写标记；复制失败/中途被杀则删掉残留目录下次自动重拷——截断 CHD 不再被永久缓存。
- **refreshList 异步化**（`LibraryScreen.kt`）：`refreshList()` 整体改为 `rememberCoroutineScope() + launch(Dispatchers.IO)`；新增 `postMessage` 参数，导入等操作成功后把专属提示透传给异步扫描汇总——不再有"提示被扫描结果覆盖"或"主线程卡顿"。9 处调用点（文件选择器/SAF DOS/SAF 普通/权限扫描/JAR/图标/存储权限/内置浏览器 DOS/内置浏览器文件夹）已全部改传 `postMessage`。

**涉及文件**：`ui/emulator/EmulatorScreen.kt`、`ui/library/LibraryScreen.kt`

---

## 修复 5：GBA 等游戏在统一存档目录下"内部存档丢失/存不上"

**根因**：SRAM（电池存档）只在 `unload()` 时写盘一次（`DisposableEffect.onDispose`）。Android 随时可能回收后台进程（切应用、来电、低内存、游戏内跳转），进程被杀后本次会话的进度全部丢失 —— 用户感知为"统一存档模式下存不上"。另：`saveSramToDisk` 用 `fopen("wb")` 写盘，若存档目录不存在会静默失败。

**修复**（防御性刷盘 + 目录创建）：
- **周期刷盘**：GBA 模拟线程每 600 帧（约 10 秒）调用一次新的 `flushSave()`，把当前 SAVE_RAM 缓冲写盘。
- **暂停即刷**：`setPaused(true)` 立即刷一次；模拟线程检测到暂停也补刷一次 —— 切后台/游戏内暂停时进度已落盘。
- **共享 mkdir**：`core_shared.h::saveSramToDisk` 在写盘前自动创建存档父目录（`mkdir 0755`，`EEXIST` 静默）—— 该函数被 NES/SNES/GBA/MD/PCE/PSX/FBNeo 等所有核心共用，一处修复全部生效。
- `flushSave()` 在 core 未加载 / 无 ROM 时为 no-op，可安全周期调用。

**涉及文件**：`core/jni/shared/core_shared.h`、`core/jni/gba_loader.h`、`core/jni/gba_loader.cpp`、`core/jni/gba_bridge.h`、`core/jni/gba_bridge.cpp`、`app/.../core/jni/GbaNative.kt`、`app/.../core/engine/GbaEngine.kt`

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

---

# Java (J2ME) 模块 7 项问题修复说明（本次提交）

**涉及文件**：
`app/src/main/java/com/nesstation/app/core/storage/PadLayoutStore.kt`、
`app/src/main/java/com/nesstation/app/core/engine/J2meEngine.kt`、
`app/src/main/java/com/nesstation/app/ui/emulator/J2meOverlay.kt`、
`app/src/main/java/com/nesstation/app/ui/emulator/EmulatorScreen.kt`、
`app/src/main/java/com/nesstation/app/ui/settings/SettingsScreen.kt`、
`app/src/main/java/com/nesstation/app/ui/settings/CoreSettingsPanel.kt`、
`app/src/main/java/com/nesstation/app/ui/library/LibraryScreen.kt`

---

## 修复 1：游戏内设置补全（含分辨率），不再调用 J2ME-Loader 原生设置

**旧问题**：Java 游戏的"游戏设置"只能从游戏库长按菜单跳转到 J2ME-Loader 的
`ConfigActivity`（原生自带设置）；游戏内设置面板只有 4 项，且分辨率等关键项缺失。
更严重的是**时序 bug**：`J2meEngine.loadRom()` 内部调用
`MicroLoader.applyConfiguration()`，用每游戏 `config.json` 的值覆盖
`Canvas.setScale/setShowFps/setLimitFps` 等 —— 组合时先于 loadRom 执行的那次
`applyCoreOptions` 会被整体覆盖，游戏内改的设置进游戏就失效。

**修复**：
- **设置重放**：`LaunchedEffect(game)` 中 loadRom 成功后（仅 JAVA 平台）重新执行
  `applyCoreOptions(engine, padLayout, platform)`，GameBox 设置的优先级高于旧
  config.json，且运行中改动即时生效（J2ME 全部字段已加入触发列表）。
- **游戏内设置面板 JAVA 分支补全**（均有真实逻辑实现）：
  | 设置项 | 存储 (PadLayout) | 应用 |
  |---|---|---|
  | 游戏分辨率 (default/auto/128×128…640×360) | `javaResolution` | `Displayable.setVirtualSize` + 立即 `Canvas.updateSize()` |
  | 画面缩放比例 (25%~400%) | `javaScaleRatio` | `Canvas.setScale(gravity, type, ratio)` |
  | 帧率限制 (不限/60/50/40/30/25/15) | `javaFpsLimit` | `Canvas.setLimitFps` |
  | 触摸输入支持 | `javaTouchInput` | `Canvas.setHasTouchInput`（控制 MIDlet `hasPointerEvents()`） |
  | 数字键兼作方向键 | `javaNumDualDispatch` | 引擎 `phoneDualDispatch` 双派发 |
  | 按键映射（10 个键位 × 手机全部按键） | `javaButtonKeyMap` | 引擎自定义映射表 |
  （原有：输入模式 / 屏幕缩放 fit·stretch·center / 显示 J2ME 帧数 / 即时绘制）
- **移除原生设置入口**：游戏库长按菜单对 JAVA 游戏不再调用
  `JavaGameStore.openSettings()` → `Config.startApp(..., showSettings=true)`，
  改为提示"设置已整合到游戏内菜单"。两套设置互相覆盖的问题从此不存在。
- **附带修复**：嵌入式模式强制 `ContextHolder.setVk(null)` 禁用旧版
  VirtualKeyboard —— 它按 config.json 默认 `showKeyboard=true` 被创建，
  不可见（无 OverlayView 承载）却会拦截物理按键、并按手机键盘高度压缩画面。

## 修复 2：全局设置加入 Java 设置

- `SettingsScreen` 核心设置列表新增 **"Java / J2ME"** 入口（此前
  `CoreSettingsPanel` 的 JAVA 块因无入口而不可达）。
- `CoreSettingsPanel` JAVA 块从 4 项补全为与游戏内面板一致的完整设置
  （输入模式/画面缩放/游戏分辨率/缩放比例/帧率限制/FPS/即时渲染/触摸/数字键兼作方向/按键映射）。

## 修复 3：Java 手柄布局编辑补上 X/Y

- 旧版 `PadLayoutEditor` 的 `showXY` 不包含 JAVA，Java 游戏手柄的 X（右软键）、
  Y（* 键）两个按键无法在布局编辑器中调整。现已加入，X/Y 与其他平台一样
  支持拖动定位 + 尺寸滑杆（横竖屏各一套，写入 btnX/btnY/btnXP/btnYP）。

## 修复 4：布局设置加入按键映射（虚拟按键 → 手机全部按键）

- 新增映射 UI：A / B / X / Y / START / SELECT / 方向 ↑↓←→ 共 10 个键位，
  每个可映射到**手机的全部按键**：数字 1-9、0、*、#、方向上下左右、
  确认(FIRE)、左/右软键、清除(CLEAR)、挂机(END)、接听(SEND)、GAME_A~GAME_D。
- 存储：`PadLayout.javaButtonKeyMap`（仅记录被修改项，如 `"a=-5,y=42,start=-11"`）。
- 应用：`J2meEngine.setCustomKeyMap()` 解析后，`setPad1` 发码时优先使用自定义
  映射，未修改的键位保持内置默认（A=确认、B=左软键、X=右软键、Y=*、
  START=挂机、SELECT=#、方向=KEY_UP/DOWN/LEFT/RIGHT）。
- UI 同步三处：游戏内设置面板、全局设置 Java/J2ME 核心页、存储层。

## 修复 5："123" 数字键盘 1-9 不起作用

两个根因 + 一个兼容性增强：
1. **位掩码笔误**：`dualKeyMap` 中 "8"→KEY_DOWN 写成了 `0x8000000`（bit 27，
   不对应任何按键），手机键盘模式按 8 永远不会补发方向键。已修正为
   `0x1000000`（与 `J2ME_BTN_NUM_8` 一致）。
2. **双派发只在手机键盘模式生效**：手柄模式点 "123" 呼出的数字盘只发裸数字码
   （'1'..'9'），依赖 `getGameAction()` 或 GameCanvas 键位的游戏可以正常译码，
   但直接比较 `keyCode == KEY_UP/KEY_FIRE` 等原始常量的游戏完全无反应。
   现改为由设置 **"数字键兼作方向键"（默认开启）** 控制：2/4/6/8/5 在发送
   数字键的同时补发 上/左/右/下/确认（真机行为），手柄数字盘与手机键盘
   两种模式同样生效；纯数字输入场景可在设置里关闭。
3. 结合修复 4 的按键映射，任何游戏的特殊键位需求都可由用户自行映射解决。

## 修复 6：切换手机键盘后也支持布局调整

- 手机键盘的数字盘（4×3）与功能键行（L/F/R/C）旧版位置/大小硬编码。
- 新增 `PadLayout.javaPhoneGrid` / `javaPhoneTop`（归一化中心坐标 + 单键尺寸），
  `J2mePhoneOverlay` 全部几何改由存储驱动（横竖屏共用，键距随尺寸联动）。
- 新增 **`J2mePhoneLayoutEditor`**（J2ME 专用布局编辑器，与 DOS 编辑器同样的
  路由方式）：拖动移动数字键盘/功能键行，点选后滑杆调大小，黄色虚线框高亮
  选中项；拖动手势与通用编辑器同款稳定实现（`pointerInput(Unit)` +
  `rememberUpdatedState`，拖动过程不会被重组取消）。
- 游戏内菜单 → "虚拟按键布局"：手机键盘模式自动进入专用编辑器，
  手柄模式进入通用编辑器（含修复 3 的 X/Y）。

## 修复 7：Java 游戏窗口接收全局画面缩放

- 旧版 `J2meGameView` 恒为 `fillMaxSize`，全局"画面缩放"(videoScale) 对
  Java 游戏完全无效（设置面板里却显示该项）。
- 现在 JAVA 游戏视图与 `GameSurfaceView` 同款处理：
  - `stretch`（默认）→ 铺满（与旧行为一致）；
  - `4:3 / 2:3 / 3:2 / 8:7 / 16:9` → 按比例约束窗口（竖屏顶部对齐、横屏居中）；
  - `custom` → 四角自定义矩形（复用现有四角编辑器与 `customRect` 存储）。
- 分层语义：窗口形状由全局 videoScale 决定，窗口内画面适配仍由 J2ME 专属的
  `javaScaleType`（fit/stretch/center）与 `javaScaleRatio` 控制，二者叠加生效。

---

## 数据兼容性说明

- 新增字段全部有默认值：旧存档（SharedPreferences `pad_layout_v2`）无需迁移，
  首次读取即得与旧版一致的行为（分辨率=default 不干预、缩放比例=100%、
  帧率=不限制、触摸=开、数字键兼方向=开、按键映射=空、手机键盘=旧版等效
  默认位置）。
- 每游戏 `config.json` 不再覆盖 GameBox 设置（修复 1 的重放机制），
  但保留为"游戏分辨率=默认"时的初始值来源。

---

# GameBox 3.5.0 补充修复（第五批）

## 修复 1：Java 虚拟按键太透明（尤其方向键）

**根因**：`J2meOnScreenController` 复用全局透明度再打对折
`opacity = padLayout.opacity * 0.5f`（默认 0.7 → 实际 0.35），叠加方向键
底色 `0xFF2C2C38`（近黑）—— 深色游戏画面上几乎隐形。

**修复**：
- 新增 J2ME 专属透明度 `javaOpacity`（默认 0.8，范围 0.3–1.0），不再
  打对折、不再与全局互相影响；
- 方向键配色增亮：底色 0xFF2C2C38 → 0xFF39445A 蓝灰 + 白色十字描边 +
  中心亮斑，任何背景下按键轮廓清晰；
- 透明度可在 3 处调节：通用布局编辑器（J2ME 时写 javaOpacity）、
  手机键盘布局编辑器、游戏内设置 → J2ME 专属设置「虚拟按键透明度」。

## 修复 2：所有核心的虚拟按键布局设置都加入透明度调节

- 通用 `PadLayoutEditor`（NES/GB/SFC/GBA/MD/PCE/NDS/PSX/PS2/街机/JAVA
  手柄模式）头部新增「透明度」滑条（写全局 opacity，0.3–1.0）；
- `J2mePhoneLayoutEditor`（JAVA 手机键盘模式）新增「透明度」滑条
  （写 javaOpacity）；
- DOS 编辑器此前已有透明度滑条，保持不变。

## 修复 3：Java 游戏触屏支持无效果（触屏没反应）

**根因**：J2ME 手柄/手机覆盖层是游戏视图（AndroidView 内的
GlesView/CanvasView）的**高 z 兄弟节点**，Compose 命中测试不会穿透到
覆盖层下方 —— 覆盖层可见期间，游戏画面收不到任何触摸事件
（两个覆盖层都是 `fillMaxSize + pointerInput` 全屏消费）。
NDS 之前已用 `onUnhandledTouch` 转发解决同类问题，J2ME 缺失该链路。

**修复**（完整转发链）：
- `Canvas.java` 新增 `postTouchAction(actionMasked, pointerId, x, y)`：
  与 `ViewCallbacks.onTouch` 完全相同的坐标换算（视图坐标 → 虚拟画布
  坐标）与事件投递，让 MIDlet 的 pointerPressed/Dragged/Released 触发；
- `J2meEngine.postTouch(...)`：转发到当前 Canvas（异常安全）；
- `J2meOverlay` 两个模式（手柄/手机键盘）：`hitTest` 未命中任何按键的
  指针标记为"游戏画面触摸"，DOWN/MOVE/UP 连同 pointerId 转发给宿主；
  已被更高 z 控件（模式切换 / "123" 按钮）消费的按下不转发（防误触）；
- `EmulatorScreen`：J2meGameView 挂 `onGloballyPositioned` 追踪视图位置，
  根坐标减视图位置得局部坐标后调用 `engine.postTouch` 注入。

## 修复 4：Java 设置按游戏单独保存（不再全局共用）

**背景**：J2ME 游戏分辨率五花八门（128×128 / 176×208 / 240×320 /
360×640 …），缩放/帧率限制/触摸支持等全局共用导致切换游戏要反复改。

**实现**：
- `PadLayoutStore.kt` 新增 `JavaGameSettings`（java* 子集快照 +
  JSON 序列化）与 `JavaGameSettingsStore`（SharedPreferences 文件
  `java_game_settings`，key = 游戏目录名，重装同名游戏仍命中）；
- 进入 Java 游戏：有专属配置则覆盖进会话状态（无则用全局默认）；
- 游戏内改 J2ME 设置：java* 子集写入该游戏专属配置，全局 prefs 的
  java* 字段保持全局快照 —— 其他 Java 游戏完全不受影响；
- 设置面板显示「本游戏使用专属设置 / 将保存为专属设置」状态与
  「恢复全局默认设置」按钮；
- 专属配置覆盖：输入模式 / 屏幕缩放 / 游戏分辨率 / 画面缩放比例 /
  帧率限制 / 显示帧数 / 即时绘制 / 触摸输入 / 数字键兼作方向键 /
  虚拟按键透明度 / 手机键盘布局 / 按键映射。

## 修复 5：长按 Java 游戏卡片的「游戏设置」接入真实逻辑

- 旧版：只弹 Toast 指引进游戏内设置（等于没有入口）；
- 新版：打开 `JavaGameSettingsDialog` —— 编辑该游戏专属配置
  （与游戏内设置同一存储），含上述全部设置项 + 透明度滑条 +
  「恢复全局默认」；保存后下次进入游戏生效。

## 修复 6：重新补上 config.json 兜底（防 "MicroLoader.init() returned false"）

本基线不包含上一批的配置兜底修复，已重新应用：
- `MicroLoader.init()`：config.json 缺失/损坏时自动生成默认配置并
  持久化（对齐 ConfigActivity 首次生成行为），不再启动失败；
- `JavaGameStore.installJar()`：安装时同步创建默认 config.json；
- `JavaGameStore.deleteGame()`：卸载时同步删除 configs 目录。

## 版本号

- `app/build.gradle.kts`：versionCode 4 → 5，versionName 3.3.0 → **3.5.0**；
- 设置 → 关于「版本」：3.3.0 → 3.5.0。

**涉及文件**：`core/storage/PadLayoutStore.kt`、`core/storage/JavaGameStore.kt`、
`ui/emulator/J2meOverlay.kt`、`ui/emulator/EmulatorScreen.kt`、
`ui/library/LibraryScreen.kt`、`ui/settings/SettingsScreen.kt`、
`core/engine/J2meEngine.kt`、`javax/microedition/lcdui/Canvas.java`、
`javax/microedition/shell/MicroLoader.java`、`app/build.gradle.kts`
