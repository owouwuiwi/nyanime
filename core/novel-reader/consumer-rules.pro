# Readium's JavaScript calls these annotated methods by their original names.
# EPUB-owned scripts are stripped; only the trusted navigator assets use the bridge.
-keepclassmembers class org.readium.r2.navigator.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes RuntimeVisibleAnnotations
