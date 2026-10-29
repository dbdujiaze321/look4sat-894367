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

# --- Orbit kernel, merge engine, alarm policy ------------------------------
# None of these use reflection, so they are not "kept" in the shrinking sense;
# the names are kept so a stack trace from the field still points at a readable
# class instead of a/ b/ c/. These are the classes whose behaviour is covered by
# the precision and merge tests, and they must remain identifiable.
-keepnames class com.rtbishop.look4sat.core.domain.predict.** { *; }
-keepnames class com.rtbishop.look4sat.core.domain.merge.** { *; }
-keepnames class com.rtbishop.look4sat.core.domain.alarm.** { *; }
-keepnames class com.rtbishop.look4sat.core.domain.model.SatelliteCategory { *; }

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
