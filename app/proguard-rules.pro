# ====================================================================
# RetroBox ProGuard / R8 rules
# ====================================================================
# 当前 release 构建已关闭混淆 (isMinifyEnabled = false)。
# 以下规则为将来重新开启混淆时使用，确保 Gson 反序列化、枚举 valueOf、
# ViewModel 反射实例化等场景不会因类名/字段名被重命名而崩溃。
# ====================================================================

# ---- 通用属性保留 ----
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Deprecated
-keepattributes Exceptions

# ---- Kotlin ----
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }

# ---- 枚举安全（valueOf / values 不会被重命名）----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# 保持所有枚举类的常量字段名
-keepclassmembers enum * {
    <fields>;
}

# ---- AndroidX / Compose ----
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.navigation.** { *; }
-dontwarn androidx.compose.**

# ---- ViewModel 保留 ----
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(android.app.Application); }

# ---- Application / Activity 保留 ----
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity

# ====================================================================
# RetroBox 项目类保留
# ====================================================================

# ---- 数据层（Gson 序列化/反序列化）----
-keep class com.retrobox.data.** { *; }
-keep class com.retrobox.data.GameInfo { *; }
-keep class com.retrobox.data.Platform { *; }
-keep class com.retrobox.data.GameRepository { *; }
-keep class com.retrobox.data.PreferenceManager { *; }

# ---- 下载层（Gson + Retrofit 反序列化）----
-keep class com.retrobox.download.** { *; }
-keep class com.retrobox.download.GiteeContent { *; }
-keep class com.retrobox.download.GiteeContent$* { *; }
-keep class com.retrobox.download.GameDownloadInfo { *; }
-keep class com.retrobox.download.GamePlatform { *; }
-keep class com.retrobox.download.DownloadTask { *; }
-keep class com.retrobox.download.DownloadStatus { *; }
-keep class com.retrobox.download.GiteeApiService { *; }

# ---- 输入层（JSONObject 序列化 + 枚举 valueOf）----
-keep class com.retrobox.input.** { *; }
-keep class com.retrobox.input.GamepadConfig { *; }
-keep class com.retrobox.input.GamepadConfig$* { *; }
-keep class com.retrobox.input.ButtonConfig { *; }
-keep class com.retrobox.input.KeyMapping { *; }
-keep class com.retrobox.input.ButtonLayout { *; }
-keep class com.retrobox.input.GamepadButtonId { *; }
-keep class com.retrobox.input.EmulatorPlatform { *; }

# ---- 模拟器核心层 ----
-keep class com.retrobox.emulator.** { *; }
-keep class com.retrobox.emulator.CoreInfo { *; }
-keep class com.retrobox.emulator.CoreStatus { *; }
-keep class com.retrobox.emulator.EmulatorCore { *; }
-keep class com.retrobox.emulator.cores.** { *; }

# ---- UI 组件层 ----
-keep class com.retrobox.ui.components.** { *; }
-keep class com.retrobox.ui.components.GamepadPreset { *; }
-keep class com.retrobox.ui.components.GamepadTheme { *; }

# ---- ViewModel 层 ----
-keep class com.retrobox.ui.viewmodel.** { *; }

# ---- Application 入口 ----
-keep class com.retrobox.RetroBoxApp { *; }
-keep class com.retrobox.MainActivity { *; }

# ====================================================================
# 第三方库规则
# ====================================================================

# ---- Retrofit ----
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# ---- OkHttp / Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ---- Gson ----
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.internal.bind.** { *; }
# Gson TypeToken 匿名类需要保留泛型签名
-keep class * extends com.google.gson.reflect.TypeToken
# Gson 序列化对象：保留无参构造器和所有字段
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- Jsoup ----
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# ---- Libsu ----
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**
-keep class * extends com.topjohnwu.superuser.ipc.RootService { *; }

# ---- Coil ----
-dontwarn coil.**
-keep class coil.** { *; }

# ---- Accompanist ----
-dontwarn com.google.accompanist.**
-keep class com.google.accompanist.** { *; }

# ---- Serializable ----
-keep class * implements java.io.Serializable {
    static <fields>;
    private <fields>;
    <fields>;
    *;
}
