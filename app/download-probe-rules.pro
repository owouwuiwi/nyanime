# Only used with -PdownloadProbe=true; production builds keep the usual optimizer rules.
-keep class com.hippo.unifile.** { *; }
-keep class eu.kanade.tachiyomi.data.download.** { *; }
-keep class eu.kanade.tachiyomi.network.OkHttpExtensionsKt { *; }
-keep class mihon.core.archive.ZipWriter { *; }
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
