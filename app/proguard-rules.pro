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
