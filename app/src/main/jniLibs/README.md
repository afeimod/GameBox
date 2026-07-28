# jniLibs/ — native library output

This directory receives the compiled `libnescore.so` produced by the
Android NDK build.

> Normally you do **not** check these files into git. The Android Gradle
> Plugin (AGP) populates this directory at build time via the
> `externalNativeBuild { cmake { ... } }` configuration in
> `app/build.gradle.kts`.

## Layout

```
jniLibs/
├── arm64-v8a/libnescore.so      # 64-bit ARM (most modern phones, Android TV)
├── armeabi-v7a/libnescore.so    # 32-bit ARM (older devices)
└── x86_64/libnescore.so         # 64-bit x86 (emulator, Chrome OS)
```

## When the file is missing

If you cloned the repo and ran the app but `System.loadLibrary("nescore")`
throws `UnsatisfiedLinkError`, the .so wasn't built yet. Two fixes:

### Fix 1 — Let AGP build it for you
```bash
./gradlew :app:assembleDebug
```
The CMake build will populate `jniLibs/<abi>/libnescore.so` automatically.

### Fix 2 — Use the stub core
In `gradle.properties`, set:
```
useStubCore=true
```
This switches CMake to `core/native-stub/CMakeLists.txt`, which produces a
no-op `libnescore.so` that lets the app start without the FCEUmm sources.
The UI will render but the emulator will not run real games.

## Where the .so comes from

| Setting                 | Source                                |
| ----------------------- | ------------------------------------- |
| `useStubCore=true`      | `core/native-stub/CMakeLists.txt`     |
| `useStubCore=false`     | `core/cmake/CMakeLists.txt` + FCEUmm  |

The CI workflow uploads the resulting APK (which already contains the
.so) as an artifact, so you can sideload the binary without ever
building locally.
