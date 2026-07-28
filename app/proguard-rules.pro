# ====================================================================
# RetroBox ProGuard / R8 rules
# ====================================================================

# ---- General ----
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes AnnotationDefault
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# ---- Kotlin ----
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# ---- AndroidX / Compose ----
# Compose is mostly metadata-driven; keep the runtime metadata so the
# compiler plugin information is preserved.
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ---- Retrofit ----
# Retain generic type information for use by reflection.
-keepattributes Signature, InnerClasses, EnclosingMethod
# Retrofit does reflection on method annotations. Keep them.
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
# Guard against R8 stripping the invokedynamic-based lambda factory used by Retrofit.
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

# ---- Gson ----
# Gson uses generic type information stored in signature attributes.
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }
# Keep model classes used for (de)serialization. Adjust the package as
# the project grows to include data classes.
-keep class com.retrobox.data.model.** { *; }
-keep class com.retrobox.network.model.** { *; }
# Keep serializable classes.
-keep class * implements java.io.Serializable {
    static <fields>;
    private <fields>;
    <fields>;
    *;
}

# ---- Jsoup ----
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# ---- Libsu ----
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**
# Keep IPC service stubs.
-keep class * extends com.topjohnwu.superuser.ipc.RootService { *; }
