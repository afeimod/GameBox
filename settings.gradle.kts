pluginManagement {
    // resolutionStrategy: 直接映射到主 jar 工件，不依赖 plugin marker。
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application",
                "com.android.library" -> {
                    useModule("com.android.tools.build:gradle:${requested.version}")
                }
                "org.jetbrains.kotlin.android",
                "org.jetbrains.kotlin.jvm",
                "org.jetbrains.kotlin.multiplatform" -> {
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
                }
                "com.google.devtools.ksp" -> {
                    useModule("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:${requested.version}")
                }
            }
        }
    }
    repositories {
        // CI 预下载的关键插件 jar 放在本地 Maven 仓库，优先从这里解析。
        val localRepo = providers.environmentVariable("LOCAL_MAVEN_REPO").orNull
        if (localRepo != null) {
            maven { url = uri(localRepo) }
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val localRepo = providers.environmentVariable("LOCAL_MAVEN_REPO").orNull
        if (localRepo != null) {
            maven { url = uri(localRepo) }
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}

rootProject.name = "NesStation"
include(":app")
