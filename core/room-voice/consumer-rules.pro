# Upstream JNI looks up Java constructors, enum names and @CalledByNative methods.
# The published AAR does not provide consumer rules. Preserve the JNI ABI in R8 builds.
-keep class org.webrtc.** { *; }
# Current WebRTC also initializes Chromium JNI Zero directly from JNI_OnLoad.
# These classes have no Java caller, so retaining org.webrtc alone is insufficient.
-keep class org.jni_zero.** { *; }
