# KubeDroid ProGuard/R8 rules
# Keep these focused on libraries that rely on reflection or ServiceLoader.

# -----------------------------
# Kubernetes Java client
# -----------------------------
# OpenAPI model classes are serialized/deserialized reflectively.
-keep class io.kubernetes.client.openapi.models.** { *; }
-keep class io.kubernetes.client.custom.** { *; }
-keep class io.kubernetes.client.util.Watch$Response { *; }
-keep class io.kubernetes.client.openapi.JSON { *; }

# Keep generic type info used by JSON adapters.
-keepattributes Signature

# -----------------------------
# OkHttp / Okio
# -----------------------------
# OkHttp is mostly shrinker-safe; keep common metadata used in stack traces and reflection.
-keepattributes Exceptions,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-dontwarn okhttp3.**
-dontwarn okio.**

# -----------------------------
# Kotlin Coroutines
# -----------------------------
# Main dispatcher and exception handler are loaded via ServiceLoader.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Android coroutine dispatcher implementations.
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }

# -----------------------------
# Release shrinker compatibility
# -----------------------------
# Optional dependencies referenced by Kubernetes auth/SnakeYAML internals that are not present on Android.
-dontwarn com.google.auth.oauth2.AccessToken
-dontwarn com.google.auth.oauth2.GoogleCredentials
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
