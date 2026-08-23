# R8 rules for the release build.
#
# Most of this app is ordinary code that R8 can shrink freely. Two things are
# not, and both fail at run time rather than at build time — which is why the
# release APK is checked on a real device before it is published, not just
# checked that it builds.

# --- BouncyCastle ----------------------------------------------------------
#
# The provider does not reference its algorithms directly. It builds class
# names as strings and looks them up reflectively, so R8 sees those classes as
# unused and removes them; the app then dies with NoSuchAlgorithmException at
# the first handshake. Only the packages reached that way are kept — the TLS
# stack itself is called normally and can still be shrunk.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# Optional JDK integrations this app never touches.
-dontwarn org.bouncycastle.jsse.**
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.jce.provider.X509LDAPCertStoreSpi
-dontwarn org.bouncycastle.x509.util.LDAPStoreHelper

# --- kotlinx.serialization -------------------------------------------------
#
# The compiler plugin writes a `Companion.serializer()` for each serializable
# class and the runtime finds it by name. The library ships most of these rules
# itself; the generated members are named explicitly because a rule that is
# already covered costs nothing and a missing one costs a crash.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Diagnostics -----------------------------------------------------------
#
# Keep line numbers so a crash report from a release build points somewhere,
# and hide the original file name, which is what the numbers are relative to.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
