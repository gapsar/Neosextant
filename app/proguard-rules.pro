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

# --- Chaquopy (Python runtime) ---
# Keep all Chaquopy classes used for Python interop
-keep class com.chaquo.python.** { *; }
-keepclassmembers class com.chaquo.python.** { *; }

# Keep app data classes that may be accessed reflectively
-keep class io.github.gapsar.neosextant.** { *; }

# Keep Astropy/numpy native bindings
-keepclassmembers class * {
    native <methods>;
}

# Preserve source file/line info for crash reports
-keepattributes SourceFile,LineNumberTable