# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in proguard-android-optimize.txt

# Keep Better xCloud injected JavaScript bridge (if any)
-keepclassmembers class com.betterxcloud.app.** {
    @android.webkit.JavascriptInterface <methods>;
}
