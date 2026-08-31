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
