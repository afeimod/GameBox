# NesStation 项目速查

## 一键交付

| 文件 | 作用 |
| --- | --- |
| `app/` | 手机 / TV 主 module（Compose + NDK） |
| `core/cmake/CMakeLists.txt` | FCEUmm 真实核心的 CMake 入口 |
| `core/native-stub/` | 占位核心（无 submodule 时用） |
| `core/jni/` | 我们的 C++ 桥接 + 后端占位 |
| `.github/workflows/android-build.yml` | 手动触发的 APK 构建 |
| `gradle.properties` | `useStubCore=true` 控制是否启用占位核心 |

## 上手三步

```bash
# 1. clone
git clone https://github.com/<你的账号>/NesStation.git
cd NesStation
git submodule update --init --recursive

# 2. 关掉 stub（如果用真核心；想先用 stub 跳过这步）
sed -i 's/useStubCore=true/useStubCore=false/' gradle.properties

# 3. 打开 Android Studio（>= Hedgehog），Hedgehog 会自动同步并构建
# 或者命令行：
./gradlew :app:assembleDebug
```

## 核心决策记录

| 决策 | 理由 |
| --- | --- |
| 核心选 FCEUmm | 精度最高、跨平台、活跃维护、GPL 友好 |
| UI 用 Jetpack Compose | 声明式、TV 焦点自动处理、与你的高端感要求契合 |
| 不用 libretro frontend | 我们的 JNI 桥足够小，避免 libretro 整套 callback 污染 |
| Compose for TV 1.0 stable | alpha 版本的 lazy layouts 在 1.0 被砍，所以用 foundation 的 LazyRow |
| Action 只手动触发 | 你要求 |
| 默认 `useStubCore=true` | 防止新 clone 的人没有拉 submodule 也能编过 |

## 已知 TODO（留待 PR）

1. `core/jni/rom_loader.cpp` 还是占位：需要接 FCEUI_LoadGame + FCEUI_Emulate + FCEUI_GetCurrentVidFrame
2. `core/jni/audio_backend.cpp` / `video_backend.cpp` 都没真接 OpenSL ES / GLES
3. `EmulatorScreen` 的 DPad 用 `awaitPointerEventScope`，可以换成 `pointerInput { detectDragGestures }` 体验更顺
4. `RomScanner.scanSafTree` 还没接 `Intent.ACTION_OPEN_DOCUMENT_TREE` 的回传
5. `EmulatorService` 的前台服务写好了但 manifest 里没注册 `<service>`（已注册）
6. TV 端 `TvHomeScreen` 还没有 `BackHandler` 处理 D-pad Back 键

## 跑 CI

1. 推到 GitHub
2. 进入 Actions → "Android Build" → Run workflow
3. 选 `debug` 或 `release`
4. 完成后下载 `NesStation-*-APK` artifact
