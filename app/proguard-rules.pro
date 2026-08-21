# Keep ONNX Runtime native bridge classes.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep Kotlin metadata for reflection used by ONNX Runtime data loaders.
-keep class kotlin.Metadata { *; }

# ML Kit text recognition: keep its model loader classes.
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }

# Keep our JNI bridge classes (none currently, but future-proof).
-keepclasseswithmembernames class * {
    native <methods>;
}

# Don't warn about missing Java models that ship as resources.
-dontwarn org.slf4j.**
-dontwarn org.apache.log4j.**
