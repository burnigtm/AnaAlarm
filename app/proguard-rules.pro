# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
# AI request/response DTOs
-keepclassmembers class com.anaalarm.ai.** {
    *** Companion;
}
-keepclasseswithmembers class com.anaalarm.ai.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.anaalarm.ai.**$$serializer { *; }
# Settings export/import models (DataExport) — release/internal minify must keep serializers
-keepclassmembers class com.anaalarm.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.anaalarm.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.anaalarm.data.**$$serializer { *; }
