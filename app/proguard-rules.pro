# Keep JNI bridge
-keep class com.nesstation.app.core.jni.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclassmembers class com.nesstation.app.core.engine.NesEngine {
    public *;
}
# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
