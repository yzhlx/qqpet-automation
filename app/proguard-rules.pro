# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Our classes are reached by name: Shizuku user services, AIDL stubs, scrcpy and hidden-API reflection.
# Shrinking only the libraries is where nearly all the size is, so nothing of ours is renamed or removed.
-keep class com.ynk.virtualdisplay.** { *; }
-keep class com.genymobile.scrcpy.** { *; }
-keep class android.** { *; }
# ML Kit finds its OCR components by class name from manifest metadata; stripped, TextRecognition.getClient is null.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.vision.** { *; }
-keep class com.google.firebase.components.** { *; }
-dontwarn android.**
-dontwarn com.android.**
-dontwarn org.lsposed.**
-keepattributes SourceFile,LineNumberTable,*Annotation*
