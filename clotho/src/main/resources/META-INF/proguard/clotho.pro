# Keep MainThreadHost providers that are loaded through java.util.ServiceLoader (JVM style).
# R8 and ProGuard read this file from the jar. The Android main lane does not need it: the
# clotho-android module installs it through androidx.startup, not through ServiceLoader.
-keep class * implements org.codirex.clotho.MainThreadHost {
    <init>();
}
