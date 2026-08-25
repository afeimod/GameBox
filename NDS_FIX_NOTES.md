# GameBox NDS 三项问题修复说明

修复版本：基于 GameBox-3.0.1-5 源码（对应输出 GameBox-3.0.1-6-fixed.zip）

参考架构：melonDS 2.0.1 官方 Android 版（用户提供 APK，经 nm 符号分析确认其
触摸采用直接像素坐标架构 `onScreenTouch(x,y) → NDS::TouchScreen(u16,u16)`）。

---

## 一、修复的问题

### 问题 1：NDS 自由布局（画面缩放选"自定义"）时全局滤镜失效

**根因**：`videoScale == "custom"` 且 NDS 平台时投屏走 `NdsDualScreenView`
（纯 Canvas 视图）分支：
- 叠加类滤镜（扫描线/CRT/点阵）的 `FilterOverlay` 只绘制在 SurfaceView 分支；
- 放大类滤镜（HQ2X/HQ4X/XBR）在原生 `applyFilterAndBlit` 中执行，需要
  ANativeWindow，而 custom 模式不设置 surface → 滤镜代码完全不执行。

**修复**（双管齐下，与其他缩放模式观感完全一致）：
| 文件 | 修改 |
| --- | --- |
| `NdsDualScreenView.kt` | 新增 `videoFilter` 属性；onDraw 绘制完双屏后把与 FilterOverlay **完全相同**的图案（scanline/crt/dot）平铺到每个屏幕矩形上；CRT 额外绘制按矩形尺寸缓存的边缘暗角（RadialGradient） |
| `nds_loader.cpp` | `cb_video` 检测无 surface 时，在**模拟线程**上运行与 SurfaceView 路径**同一段** CPU 滤镜代码（HQ2X/HQ4X/XBR），输出写入新增的 `s_filteredFrame` |
| `NdsEngine.kt` | custom 模式改为经 `getFilteredFrameBuffer` 拉取放大后的合成帧（无滤镜时回落原始帧） |
| `EmulatorScreen.kt` | 滤镜图案生成函数提取为共享 `NdsFilterPatterns` 对象，两个分支共用 |

放大帧的切片比例与原始帧等比（2x/4x），`computeSrcRects` 的比例推导对
放大帧同样成立，无需额外处理。

### 问题 2：NDS 下屏触摸没有作用

**根因**：触摸链路依赖"合成帧归一化坐标"间接层（触点 → view 归一化 →
-0x8000..0x7FFF → 核心按 `screen_layout_data` 反解回下屏像素），任何一环
与核心几何不一致（自由布局、屏幕间距、GL 渲染器的 2px gap、布局切换）都会
静默断链。C 模拟验证旧链路在默认配置数学正确，但架构脆弱。

**修复**（官方 melonDS 架构：直接像素坐标，绕过全部中间层）：
| 文件 | 修改 |
| --- | --- |
| `input.cpp` / `input.h` | 新增全局原子量 `touch_direct_*`；`update_input` 的 Touch 分支优先消费直接像素坐标（0..255/0..191，Clamp 后直接 `NDS::TouchScreen`），旧 POINTER 路径保留供 Hybrid 布局 |
| `nds_loader.cpp` / `nds_bridge.cpp` / `NdsNative.kt` / `NdsEngine.kt` | 新增 `setTouchInputDirect(x, y, pressed)` 全链路 JNI 接口 |
| `NdsDualScreenView.kt` | 触点在下屏矩形内直接线性映射为下屏像素坐标 —— 与核心布局/间距/GL gap **完全解耦** |
| `EmulatorScreen.kt` | SurfaceView 分支新增 `handleNdsTouch`：按屏幕布局（含 gap、GL 修正）计算下屏精确矩形后直接映射。支持 Top/Bottom、Bottom/Top、Left/Right、Right/Left、Bottom Only；Hybrid 布局保留旧路径兜底；触在上屏自动释放 |

**验证**：`scripts/input_cpp_test`（6 项逻辑测试全过）+ 
`scripts/direct_touch_e2e.c`（7 场景端到端映射全过，含 gap=12、GL gap=2、
左右布局、Bottom Only、Bottom/Top）。

### 问题 3：性能比较差

| # | 瓶颈 | 修复 | 文件 |
| --- | --- | --- | --- |
| 1 | 模拟线程每帧 `getFrameBuffer` JNI 拷贝 ~400KB（surface 模式下原生已 blit，纯浪费） | surface 模式跳过拉取；截图按需独立拉取不受影响 | `NdsEngine.kt` |
| 2 | `NdsDualScreenView` 每个垂直同步都重绘（90/120Hz 屏对 60fps 帧流 2 倍冗余 setPixels + 纹理上传） | 新增单调帧号 `frameStamp`，Choreographer 仅在新帧到达时 invalidate | `nds_loader.cpp` + `NdsDualScreenView.kt` |
| 3 | `blitToSurface` 快路径逐像素字节交换（~98K 像素/帧在模拟线程上） | NEON `vld4q_u8/vst4q_u8` 行转换（每迭代 16 像素，约 4 倍提速），非 NEON 平台保留标量回退 | `core_shared.h` |
| 4 | `cb_environment` 每次调用 LOGI（GET_VARIABLE_UPDATE 每帧查询 → 每秒 60+ 条 logcat 写入） | 删除逐次日志 | `nds_loader.cpp` |
| 5 | `GET_VARIABLE` 返回 `c_str()`，UI 线程 `setCoreOption` 可在核心 strcmp 前 realloc 字符串 → 悬垂指针竞态 | 持锁拷贝到 thread_local 缓冲 | `nds_loader.cpp` |

## 二、附带修复：CI 构建失败

构建日志 `7_Build Release APK.txt` 显示 CI 因 `opengl.cpp` 缺
`#include <libretro.h>` 失败（6 个 unknown type 错误）。zip 源码已含该
include（手补）。本次用 g++ + 真实头文件对全部修改文件做了语法检查：

- `opengl.cpp` ✅（CI 失败文件，验证用户手补有效）
- `nds_loader.cpp` ✅　`nds_bridge.cpp` ✅　`input.cpp` ✅

## 三、修改文件清单（11 个）

```
core/melonds/libretro/input.cpp      直接触摸路径（核心包装层）
core/melonds/libretro/input.h        touch_direct_* 声明
core/jni/nds_loader.cpp              过滤帧管线/直接触摸/帧号/竞态/日志
core/jni/nds_loader.h                新 API 声明
core/jni/nds_bridge.cpp              JNI 桥接新接口
core/jni/nds_bridge.h                Engine 类新方法
core/jni/shared/core_shared.h        NEON 行转换优化
app/.../core/jni/NdsNative.kt        5 个新 external 函数
app/.../core/engine/NdsEngine.kt     帧拉取策略 + 新方法
app/.../ui/emulator/NdsDualScreenView.kt  全面重写（滤镜+直接触摸+帧节流）
app/.../ui/emulator/EmulatorScreen.kt     参数传递 + 直接触摸 + 图案共享
```

## 四、行为说明

- **触摸释放语义**：触点离开下屏区域即释放（比旧"粘滞在边界"更符合直觉）；
  Hybrid 布局保持上游旧行为。
- **Hybrid 布局滤镜**：叠加图案正常绘制；放大滤镜在无 surface 时同样生效
  （核心软件合成 hybrid 帧 → 原生滤镜 → 放大帧切片）。
- **兼容性**：`setTouchInput`（旧接口）保留并可用；两路径共享 `touching`
  状态，任意顺序切换不会产生"卡触摸"。
- **默认设置不变**：软件渲染器 + JIT 开启 + 触摸模式 "Touch"。

## 五、验证方式（建议）

1. `./gradlew :app:assembleDebug` 构建（本次已做 C++ 语法级验证，Kotlin 部分
   请以 IDE/构建为准）。
2. 自由布局 + 扫描线/CRT/点阵/HQ2X/HQ4X 逐个切换，确认滤镜生效且与 4:3
   模式观感一致。
3. 自由布局拖动下屏矩形到任意位置，触摸下屏确认命中（如《新超级马里奥》
   菜单、《塞尔达》地图）。
4. 设置屏幕间距 0/12、切换 Top/Bottom 与 Left/Right 布局，确认触摸位置
   准确。
5. 对比修复前后同场景帧率（surface 模式跳过帧拷贝 + NEON blit + 日志
   削减，预期 3D 场景有可感知提升；开启 HQ2X/HQ4X 滤镜时提升最明显）。
