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
