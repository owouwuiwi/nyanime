# MediaPipe uses JNI names and protobuf reflection; retain the pinned SDK ABI.
# The logging implementation remains optimizable so the reviewed local factory
# can eliminate the unused statistics transport and its implementation.
-keep class !com.google.mediapipe.tasks.core.logging.**,com.google.mediapipe.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# forEnclosingClass inspects the real Java call stack, so its frames must not inline.
# https://github.com/google-ai-edge/mediapipe/issues/6138
-keep class com.google.common.flogger.** { *; }
