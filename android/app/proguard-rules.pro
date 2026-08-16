# ============================================================
# ZONA-OSIER — ProGuard Rules
# ============================================================

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit + Kotlin Serialization
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Shizuku
-keep class rikka.shizuku.** { *; }

# Room entities — semuanya pakai @Entity annotation
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase

# ObjectBox
-keep class io.objectbox.** { *; }

# Vosk
-keep class com.alphacephei.vosk.** { *; }

# Picovoice Eagle
-keep class ai.picovoice.eagle.** { *; }

# JGit
-keep class org.eclipse.jgit.** { *; }

# Gson TypeConverters
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# LLM Model Client (semua model client class)
-keep class com.zonaosier.model.provider.** { *; }

# Keep CharacterCard entity (Gson serialisasi)
-keep class com.zonaosier.memory.entity.CharacterCard { *; }
-keep class com.zonaosier.memory.entity.ToolPolicy { *; }
-keep class com.zonaosier.memory.entity.ModelBinding { *; }

# sherpa-onnx (jika digunakan)
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ML Kit Face Detection
-keep class com.google.mlkit.vision.face.** { *; }

# AndroidX security crypto
-keep class androidx.security.crypto.** { *; }
