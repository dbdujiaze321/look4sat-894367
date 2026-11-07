# ---------------------------------------------------------------------------
# Look4Sat offline build - R8 rules
#
# Everything below exists for a specific failure mode, not as boilerplate. The
# default proguard-android-optimize.txt already covers Compose, OkHttp, Room
# and kotlinx.coroutines through their consumer rules.
# ---------------------------------------------------------------------------

# kotlinx.serialization, Room and Compose all read annotations and generic
# signatures at runtime; R8 removes them unless told otherwise.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod

# --- kotlinx.serialization -------------------------------------------------
# Serializers are reached through the generated Companion.serializer() function,
# which R8 cannot see through. Without this the navigation route keys fail to
# deserialize after the process is restored, and the app drops back to the start
# destination instead of the screen the user was on.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.rtbishop.look4sat.**$$serializer { *; }
-keepclassmembers class com.rtbishop.look4sat.** {
    *** Companion;
}
-keepclasseswithmembers class com.rtbishop.look4sat.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**

# --- Components instantiated by the platform -------------------------------
# Receivers are named only in the manifest, which R8 does honour, but the
# application class and the alarm scheduler are also touched from a cold
# broadcast process, so they are pinned explicitly.
-keep class com.rtbishop.look4sat.alarm.** { *; }
-keep class com.rtbishop.look4sat.MainApplication { *; }
-keep class com.rtbishop.look4sat.MainActivity { *; }

# --- osmdroid --------------------------------------------------------------
# Tile sources are selected by name at runtime; shrinking them breaks the
# offline tile pack lookup.
-dontwarn org.osmdroid.**
-keep class org.osmdroid.tileprovider.tilesource.** { *; }

# --- Optional dependencies that are absent at runtime ----------------------
# OkHttp probes for these and degrades gracefully when they are missing.
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# Release hardening.
#
# The orbit kernel, the merge engine and the alarm policy used to be pinned with
# -keepnames so a field stack trace stayed readable. That is a debugging
# convenience, and it is exactly the map a reverse engineer wants: it labels
# "this is the propagator, this is the TLE merge". Released builds therefore
# obfuscate them, and the mapping file is kept private next to the signing key.
# ---------------------------------------------------------------------------

# Merge every renamed class into one package and drop the original access
# widening barriers, so the package structure no longer hints at the
# architecture.
-repackageclasses 'o'
-allowaccessmodification

# Class names that appear as string constants (reflection, logging tags,
# serialised names) follow the rename instead of staying behind as a legend.
-adaptclassstrings

# Source file names are a free hint about the code layout. Line numbers stay:
# without them the mapping file cannot resolve a crash report.
-renamesourcefileattribute SourceFile

# Debug logging is compiled out of release builds. It is noise for the user and
# a running commentary for whoever is reading the decompiled code.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}
-assumenosideeffects class kotlin.io.ConsoleKt {
    public static *** print*(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void print(...);
    public void println(...);
}
