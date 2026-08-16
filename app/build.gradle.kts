plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
}

android {
    namespace = "com.nesstation.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nesstation.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "2.3.9"
        ndkVersion = (project.findProperty("ndkVersion") as String?) ?: "26.3.11579264"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // J2ME: FULL_EMULATOR = true (runtime DEX loading mode)
        buildConfigField("boolean", "FULL_EMULATOR", "true")

        // 对战平台服务器地址（打包时内置，用户无需手动配置）。
        // 可通过 gradle 参数覆盖：-PbattleServerHost=xxx -PbattleServerHttpPort=xxx -PbattleServerTcpPort=xxx
        val battleServerHost = (project.findProperty("battleServerHost") as String?) ?: "192.168.1.100"
        val battleServerHttpPort = (project.findProperty("battleServerHttpPort") as String?) ?: "8080"
        val battleServerTcpPort = (project.findProperty("battleServerTcpPort") as String?) ?: "9090"
        buildConfigField("String", "BATTLE_SERVER_HOST", "\"$battleServerHost\"")
        buildConfigField("String", "BATTLE_SERVER_HTTP_PORT", "\"$battleServerHttpPort\"")
        buildConfigField("String", "BATTLE_SERVER_TCP_PORT", "\"$battleServerTcpPort\"")

        multiDexEnabled = true

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
        viewBinding = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
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
            multiDexKeepProguard = file("multidex-config.pro")
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

    lint {
        abortOnError = false
        disable += listOf("MissingTranslation", "ExtraTranslation")
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.rxjava2)
    kapt(libs.androidx.room.compiler)

    implementation(libs.coil.compose)

    implementation(libs.androidx.leanback)
    implementation(libs.androidx.leanback.preference)

    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.webkit)

    // J2ME dependencies
    implementation(project(":dexlib"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.multidex)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.gson)
    implementation(libs.guava)
    implementation(libs.rxandroid)
    implementation(libs.zip4j)

    // J2ME additional dependencies
    implementation(libs.acra)
    implementation(libs.volley)
    implementation(libs.mididriver)
    implementation(libs.pngj)
    implementation(libs.mobile.ffmpeg)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.filepicker)
    implementation(libs.ambilwarna)
    compileOnly(libs.auto.service.annotations)
    kapt(libs.auto.service)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
