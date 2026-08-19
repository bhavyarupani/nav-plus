# GraphHopper — keep reflection-accessed classes
-keep class com.graphhopper.** { *; }
-dontwarn com.graphhopper.**

# MapLibre
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# Moshi
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}
-keep class **JsonAdapter { *; }

# Hilt
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <init>(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
