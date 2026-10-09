# ---------------------------------------------------------------------------
# 通用：保留注解与签名信息（kotlinx.serialization 依赖这些）
# ---------------------------------------------------------------------------
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# kotlinx.serialization（PlaylistStore 用 @Serializable / Json.decodeFromString）
# 反射路径：序列化器靠 $$serializer 与 Companion.serializer() 拿到，
# 少了这几条 release 包会在读播放列表时崩。
# ---------------------------------------------------------------------------
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ipodplayer3.app.**$$serializer { *; }
-keepclassmembers class com.ipodplayer3.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.ipodplayer3.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 枚举走 valueOf(String) 反射（EqPreset / IpodThemeId），别被裁剪
-keepclassmembers enum com.ipodplayer3.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------
# Media3 / ExoPlayer
# 库自带 consumer rules，这里只兜住可选依赖缺失导致的警告。
# ---------------------------------------------------------------------------
-dontwarn androidx.media3.**
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.debug.**

# ---------------------------------------------------------------------------
# Compose / Coil：两者都自带 consumer rules，无需额外 keep。
# 注意：不要把整个 com.ipodplayer3.app.data.** 全量 keep ——
# 那等于让 R8 对这个包失效，模型类本身没有反射依赖。
# ---------------------------------------------------------------------------
