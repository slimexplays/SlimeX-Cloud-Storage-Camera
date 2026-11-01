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

# ---- Tink (transitive dependency of androidx.security:security-crypto) ------
# Tink is annotated with Error Prone annotations, which are compile-time only and are not shipped
# in the APK, so R8 sees them as missing classes.
-dontwarn com.google.errorprone.annotations.**

# ---- Security / crypto -----------------------------------------------------
# JCE providers are resolved by name at runtime; nothing needs keeping, only silencing.
-dontwarn javax.crypto.**
-dontwarn java.security.**

# ---- WorkManager @HiltWorker generated adapters ----------------------------
-keep class **_HiltModules* { *; }
-keep class **_GeneratedInjector { *; }
-keep class **__Factory { *; }
-keep class **__MembersInjector { *; }

# ---- Keep line numbers for crash reports without leaking source content ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
