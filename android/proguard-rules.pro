# Keep rule file for the Gear Forge app (point 19).
# R8 is enabled for release builds; these rules keep the classes that are looked up
# reflectively or through consumer-library entry points, and silence known harmless
# warnings from libraries that reference optional classes.

# ---------------------------------------------------------------------------
# Kotlin / coroutines
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ---------------------------------------------------------------------------
# libGDX (native .so libs are packaged in android/libs; no Java code is linked,
# but keep rules are provided so a future libGDX dependency keeps its classes)
# ---------------------------------------------------------------------------
-keep class com.badlogic.gdx.** { *; }
-dontwarn com.badlogic.**

# ---------------------------------------------------------------------------
# Jetpack Compose
# ---------------------------------------------------------------------------
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# ---------------------------------------------------------------------------
# AdMob / UMP (play-services-ads, user-messaging-platform)
# ---------------------------------------------------------------------------
-dontwarn com.google.android.gms.ads.**
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.gms.common.** { *; }
-dontwarn com.google.android.ump.**
-keep class com.google.android.ump.** { *; }

# ---------------------------------------------------------------------------
# Google Play Billing
# ---------------------------------------------------------------------------
-dontwarn com.android.billingclient.**
-keep class com.android.billingclient.** { *; }

# ---------------------------------------------------------------------------
# App model classes
# SavedConfigs serializes these explicitly via JSONObject (no reflection), but the
# enum constants are resolved with `valueOf`/`entries`, so keep the enum contract.
# ---------------------------------------------------------------------------
-keep class com.gearforge.core.GearParams { *; }
-keep class com.gearforge.core.BoreSpec { *; }
-keep class com.gearforge.core.Mesh { *; }
-keep class com.gearforge.core.GearAssembly { *; }
-keep class com.gearforge.core.PlanetaryAssembly { *; }
-keep class com.gearforge.core.Vec2 { *; }
-keep class com.gearforge.core.Vec3 { *; }
-keep class com.gearforge.core.PlanarShape { *; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------
# Misc
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
