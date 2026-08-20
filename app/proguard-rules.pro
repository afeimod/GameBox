# ─── Keep the Application class and its companion ───────────────────────────
-keep class com.nesstation.app.NesApp { *; }
-keep class com.nesstation.app.NesApp$Companion { *; }

# ─── JNI bridge: keep native methods + the object that declares them ──────
-keep class com.nesstation.app.core.jni.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ─── Engine + its companion (singleton pattern) ───────────────────────────
-keep class com.nesstation.app.core.engine.NesEngine { *; }
-keep class com.nesstation.app.core.engine.NesEngine$Companion { *; }

# ─── Storage layer ─────────────────────────────────────────────────────────
-keep class com.nesstation.app.core.storage.** { *; }
# R8 的 -dontoptimize 是全局开关,不接受类参数(带类参数会报
# "Expected char '-'" 导致构建失败)。这里改为全局只禁用方法内联:
# EmulatorScreen 中 isButtonHidden/setButtonHidden 等多参数方法被 R8 内联后
# 触发 "expected 8 arg registers, method signature has 9 or more" 的 VerifyError(闪退),
# 禁用 inlining 即可规避,其余优化(裁剪/混淆)仍生效。
-optimizations !method/inlining/short,!method/inlining/unique

# ─── J2ME-Loader: keep all emulator classes (prevent R8 stripping) ────────
-keep class javax.** { *; }
-keep class com.kddi.** { *; }
-keep class com.siemens.mp.** { *; }
-keep class com.samsung.util.** { *; }
-keep class com.sonyericsson.accelerometer.** { *; }
-keep class com.sprintpcs.media.** { *; }
-keep class com.mascotcapsule.micro3d.v3.** { *; }
-keep class com.jblend.graphics.j3d.* { *; }
-keep class com.motorola.** { *; }
-keep class com.nokia.mid.** { *; }
-keep class com.sun.midp.midlet.** { *; }
-keep class com.vodafone.** { *; }
-keep class mmpp.media.** { *; }
-keep class org.microemu.** { *; }
-keep class ru.playsoftware.j2meloader.** { *; }
-keep class ru.woesss.** { *; }
-keep class com.nesstation.app.BuildConfig { *; }
-keep class com.arthenica.mobileffmpeg.** { *; }
-keep class org.acra.attachment.DefaultAttachmentProvider { *; }
-keep class ru.playsoftware.j2meloader.crashes.models.* { *; }
# Keep J2ME data binding classes
-keep class com.nesstation.app.databinding.** { *; }
# Keep J2ME activities (also declared in manifest, but be explicit)
-keep class ru.playsoftware.j2meloader.config.ConfigActivity { *; }
-keep class ru.playsoftware.j2meloader.config.ProfilesActivity { *; }
-keep class ru.playsoftware.j2meloader.settings.SettingsActivity { *; }
-keep class ru.playsoftware.j2meloader.settings.KeyMapperActivity { *; }
-keep class javax.microedition.shell.MicroActivity { *; }
-keep class ru.playsoftware.j2meloader.filepicker.** { *; }
# Keep enum methods
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── Room ──────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * implements androidx.room.Dao { *; }
-dontwarn androidx.room.paging.**

# ─── DataStore ─────────────────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ─── Keep Kotlin metadata so reflection-based libs work ───────────────────
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# ─── Compose / Lifecycle (R8 sometimes over-strips) ───────────────────────
-dontwarn androidx.compose.**
-dontwarn androidx.lifecycle.**
# javax.annotation
-dontwarn javax.annotation.processing.AbstractProcessor
-dontwarn javax.annotation.processing.SupportedOptions
-dontwarn javax.annotation.processing.Processor

# Gson
### The following rules are needed for R8 in "full mode" which only adheres to `-keepattribtues` if
### the corresponding class or field is matches by a `-keep` rule as well, see
### https://r8.googlesource.com/r8/+/refs/heads/master/compatibility-faq.md#r8-full-mode

# Keep class TypeToken (respectively its generic signature)
-keep class com.google.gson.reflect.TypeToken { *; }

# Keep any (anonymous) classes extending TypeToken
-keep class * extends com.google.gson.reflect.TypeToken

# Keep classes with @JsonAdapter annotation
-keep @com.google.gson.annotations.JsonAdapter class *

# Keep fields with @SerializedName annotation, but allow obfuscation of their names
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# Keep fields with any other Gson annotation
-keepclassmembers class * {
  @com.google.gson.annotations.Expose <fields>;
  @com.google.gson.annotations.JsonAdapter <fields>;
  @com.google.gson.annotations.Since <fields>;
  @com.google.gson.annotations.Until <fields>;
}

# Keep no-args constructor of classes which can be used with @JsonAdapter
# By default their no-args constructor is invoked to create an adapter instance
-keep class * extends com.google.gson.TypeAdapter {
  <init>();
}
-keep class * implements com.google.gson.TypeAdapterFactory {
  <init>();
}
-keep class * implements com.google.gson.JsonSerializer {
  <init>();
}
-keep class * implements com.google.gson.JsonDeserializer {
  <init>();
}
