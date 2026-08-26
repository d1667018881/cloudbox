# ============================================================
# CloudBox ProGuard 规则
# 说明：本项目刻意不开启 R8 对模型类的混淆，
# 因为 Gson 反射解析 JSON 依赖字段名与 Kotlin 属性名一致。
# ============================================================

# ---- Retrofit / OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# ---- Gson 模型（DTO）禁止混淆：JSON 字段名映射依赖反射 ----
-keep class com.cloudbox.app.core.data.dto.** { *; }
-keep class com.cloudbox.app.core.domain.model.** { *; }

# ---- Room ----
-dontwarn androidx.room.paging.**
-keep class * extends androidx.room.RoomDatabase { *; }

# ---- Hilt / Dagger ----
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel *;
}

# ---- Jsoup ----
-keep class org.jsoup.** { *; }

# ---- ZXing ----
-keep class com.google.zxing.** { *; }

# ---- Coroutines ----
-dontwarn kotlinx.coroutines.**

# ---- Kotlin 反射 ----
-keepattributes *Annotation*
-keepclassmembers class **$WhenMappings { *; }
