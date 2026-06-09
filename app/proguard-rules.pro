# R8 rules for the companion (phone) release. Like the watch, obfuscation is a courtesy so the
# reverse-engineered Rivian *cloud* protocol isn't trivially readable from the shipped APK; the
# wire calls are unchanged. All JSON is hand-built via org.json (no reflective serialization),
# and the Activity/Service are kept automatically (referenced from AndroidManifest), so only
# OkHttp's optional, runtime-absent TLS providers need silencing.

# OkHttp references these only if present at runtime; they aren't on Android, so R8 warns. Mute it.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
# Animal Sniffer annotations pulled in transitively by OkHttp/Okio.
-dontwarn org.codehaus.mojo.animal_sniffer.*
