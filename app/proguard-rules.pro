# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Global optimization settings
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification

# Keep line numbers for debugging stack traces in release builds
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Remove Log statements
# Strip verbose/debug logging only. Info-level lines carry the camera, capture and OCR
# diagnostics that the in-app "Share logs" export relies on in release builds.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# PdfBox-Android rules
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.harmony.**
-dontwarn com.tom_roush.fontbox.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View

# Keep AndroidX components
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-keep class com.google.android.material.** { *; }

# Keep any classes referenced from XML layouts
-keep public class * extends androidx.constraintlayout.widget.ConstraintLayout
-keep public class * extends androidx.coordinatorlayout.widget.CoordinatorLayout
-keep public class * extends androidx.recyclerview.widget.RecyclerView
-keep public class * extends androidx.viewpager.widget.ViewPager
-keep public class * extends androidx.fragment.app.Fragment

# ProGuard rules for OpenCV

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the classes and methods used by JNI
-keep class org.opencv.** { *; }
-keep class org.opencv.core.** { *; }
-keep class org.opencv.imgproc.** { *; }
-keep class org.opencv.features2d.** { *; }
-keep class org.opencv.imgcodecs.** { *; }

# Keep constructors that are called from native code
-keepclasseswithmembers class * {
    public <init>(org.opencv.core.Mat);
}

# Keep methods that are called from native code
-keepclasseswithmembers class * {
    void set*(int, double);
    void set*(int, int);
    double get*(int);
    int get*(int);
}

# Keep all native libraries
-keepattributes *Annotation*

# Don't warn about missing dependencies
-dontwarn org.opencv.**

-keep class ai.onnxruntime.** { *; }

# ---- Gson/Registry JSON model keeps ----
# Keep JSON layout stable for persisted registry across releases.
# Gson uses reflection; obfuscation must not rename these classes/fields.
-keep class de.schliweb.makeacopy.data.CompletedScanEntry { *; }
-keep class de.schliweb.makeacopy.data.CompletedScansRegistry$RegistryFile { *; }

-keep class de.schliweb.makeacopy.utils.ocr.OcrModelManager { *; }

# ---- Room database keeps ----
# Room uses reflection and generated code that references entity/DAO classes by name.
-keep class de.schliweb.makeacopy.data.library.AppDatabase { *; }
-keep class de.schliweb.makeacopy.data.library.ScanEntity { *; }
-keep class de.schliweb.makeacopy.data.library.CollectionEntity { *; }
-keep class de.schliweb.makeacopy.data.library.ScanCollectionCrossRef { *; }
-keep class de.schliweb.makeacopy.data.library.ScansDao { *; }
-keep class de.schliweb.makeacopy.data.library.CollectionsDao { *; }
-keep class de.schliweb.makeacopy.data.library.ScanCollectionJoinDao { *; }

# Ensure generic type info is retained (Gson reads List<CompletedScanEntry> from field signature)
-keepattributes Signature
