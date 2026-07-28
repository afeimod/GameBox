plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nesstation.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nesstation.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // Pin NDK to match the version CI installs; can be overridden via
        // -PndkVersion=26.3.11579264 if you have a different one locally.
        ndkVersion = (project.findProperty("ndkVersion") as String?) ?: "26.3.11579264"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            // Allow CI to narrow the ABIs via -PabiFilter=arm64-v8a
            val abiFilter = (project.findProperty("abiFilter") as String?)
            if (abiFilter.isNullOrBlank()) {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            } else {
                abiFilters += abiFilter.split(",").map { it.trim() }
            }
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-21"
                )
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    // Pin Compose to the BOM 2024.06 family and force Core / Activity /
    // Lifecycle to the 1.13 / 1.8 / 1.9 line. This stops transitive
    // upgrades pulling in Compose 1.10 / Core 1.16 which require
    // compileSdk 35 and AGP 8.6+.
    configurations.all {
        resolutionStrategy {
            force(
                "androidx.compose.ui:ui:1.6.8",
                "androidx.compose.ui:ui-graphics:1.6.8",
                "androidx.compose.ui:ui-text:1.6.8",
                "androidx.compose.ui:ui-tooling-preview:1.6.8",
                "androidx.compose.foundation:foundation:1.6.8",
                "androidx.compose.foundation:foundation-layout:1.6.8",
                "androidx.compose.animation:animation:1.6.8",
                "androidx.compose.animation:animation-core:1.6.8",
                "androidx.compose.material3:material3:1.2.1",
                "androidx.compose.material:material-icons-core:1.6.8",
                "androidx.compose.material:material-icons-extended:1.6.8",
                "androidx.compose.runtime:runtime:1.6.8",
                "androidx.core:core:1.13.1",
                "androidx.core:core-ktx:1.13.1",
                "androidx.activity:activity:1.9.0",
                "androidx.activity:activity-compose:1.9.0",
                "androidx.lifecycle:lifecycle-runtime:2.8.2",
                "androidx.lifecycle:lifecycle-runtime-ktx:2.8.2",
                "androidx.lifecycle:lifecycle-viewmodel:2.8.2",
                "androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2",
                "androidx.lifecycle:lifecycle-runtime-compose:2.8.2",
                "androidx.transition:transition:1.5.1"
            )
        }
    }

    sourceSets {
        getByName("main") {
            // `jniLibs` is still supported. `jni` is deprecated and will be
            // removed in AGP 9.0 — we keep the JNI sources inside the
            // CMake build via `externalNativeBuild` instead, so no `jni`
            // source set is needed.
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // Native build is opt-in: we only configure externalNativeBuild when
    // a CMakeLists.txt is actually present next to the project. This makes
    // the project buildable on its own (without core/), and is what people
    // who only want the UI / Kotlin code get. To enable the native core,
    // either drop core/ next to app/ or set:
    //   -PuseStubCore=true   (use the built-in stub, no real gameplay)
    //   -PuseStubCore=false  (use the real FCEUmm core; requires submodule)
    val useStub = (project.findProperty("useStubCore") as String?)?.toBoolean() ?: true
    val stubPath = file("../../core/native-stub/CMakeLists.txt")
    val realPath = file("../../core/cmake/CMakeLists.txt")
    val cmakePath = when {
        useStub && stubPath.exists() -> stubPath
        !useStub && realPath.exists() -> realPath
        stubPath.exists() -> stubPath   // fall back to stub even if not requested
        realPath.exists() -> realPath
        else -> null
    }
    if (cmakePath != null) {
        externalNativeBuild {
            cmake {
                path = cmakePath
                version = "3.22.1"
            }
        }
    } else {
        // No native core available — make sure nothing tries to look for it.
        logger.warn(
            "NesStation: no CMakeLists.txt found at core/native-stub/ or core/cmake/. " +
            "Building without a native core — emulator will throw UnsatisfiedLinkError " +
            "at loadLibrary(\"nescore\") time. To fix, populate core/ or pass " +
            "-PuseStubCore=true once you do."
        )
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        // Equivalent of android:extractNativeLibs="false" — keeps .so inside
        // the APK instead of extracting at install time. This used to be set
        // in AndroidManifest.xml but AGP 8+ wants it in the build file.
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.0")
    // Navigation 2.8.x requires Kotlin 2.0; pinned to 2.7.7 to stay on K1.9.
    // 2.7.7 works fine with Compose 1.6.8 (BOM 2024.06) — but if you see a
    // runtime AbstractMethodError, bump to 2.8.x + Kotlin 2.0.
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // TV support — these deps are always present. The UI switches between
    // phone and TV layouts at runtime via LocalContext.packageManager
    // (see ui/NesApp.kt), so we don't need separate `tv` flavor configs.
    //
    // We pin to the last alpha of tv-foundation that still works with
    // compileSdk 34 / AGP 8.5.x. tv-foundation 1.0.0 stable pulled in
    // Compose 1.10 which forces compileSdk 35.
    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")
    implementation("androidx.tv:tv-material:1.0.0-alpha10")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    // Core / Coroutines
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Image
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Leanback (TV launcher integration)
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-preference:1.0.0")

    // Documents (SAF)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
