# kotlinx.serialization 生成的 serializer 需要保留
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.dayline.backup.** {
    kotlinx.serialization.KSerializer serializer(...);
}
