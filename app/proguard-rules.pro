# SlimeX Cloud Camera - ProGuard / R8 rules

# ---- Kotlinx Serialization -------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.slimex.cloudcamera.**$$serializer { *; }
-keepclassmembers class com.slimex.cloudcamera.** {
    *** Companion;
}
-keepclasseswithmembers class com.slimex.cloudcamera.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.slimex.cloudcamera.** { *; }

# ---- OkHttp / Okio ---------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepclassmembers class * extends java.lang.Enum { *; }

# ---- Hilt / Dagger ---------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}
-dontwarn dagger.hilt.**

# ---- CameraX ---------------------------------------------------------------
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ---- App models used reflectively by WorkManager ---------------------------
-keep class * extends androidx.work.ListenableWorker { *; }
