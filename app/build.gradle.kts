plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
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
        ndkVersion = (project.findProperty("ndkVersion") as String?) ?: "26.3.11579264"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk {
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
        kotlinCompilerExtensionVersion = "1.5.0"
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
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    val useStub = (project.findProperty("useStubCore") as String?)?.toBoolean() ?: false
    val stubPath = file("../core/native-stub/CMakeLists.txt")
    val realPath = file("../core/cmake/CMakeLists.txt")
    val cmakePath = when {
        useStub && stubPath.exists() -> stubPath
        !useStub && realPath.exists() -> realPath
        stubPath.exists() -> stubPath
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
        logger.error(
            "NesStation: no CMakeLists.txt found at core/native-stub/ or core/cmake/. " +
            "Building without a native core — emulator will crash at loadLibrary(\"nescore\"). " +
            "Fix: ensure core/ exists next to app/, or pass -PuseStubCore=true."
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
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // TV support
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    // Lifecycle / ViewModel
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Core / Coroutines
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Image
    implementation(libs.coil.compose)

    // Leanback (TV launcher integration)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.leanback.preference)

    // Documents (SAF)
    implementation(libs.androidx.documentfile)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
