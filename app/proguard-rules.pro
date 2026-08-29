# 릴리스에서 Log.d/v 제거 — 앱 전체에서 로그를 지우는 단일 메커니즘
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# kotlinx.serialization — @Serializable 클래스의 serializer 보존
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class **$* implements kotlinx.serialization.internal.GeneratedSerializer {
    static **$* INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.nhportfolio.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Navigation 타입 세이프 Route (@Serializable sealed interface)
-keep class dev.nhportfolio.Route { *; }
-keep class dev.nhportfolio.Route$* { *; }

# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.debug.**
