# Project-specific ProGuard rules.
# Minification is currently disabled for release builds, but these rules are
# required before enabling R8 because the nPerf adapter is loaded by reflection.

-keep class com.netlife.speedtestnl.nperf.NperfVendorAdapter { *; }
-keep class * implements com.netlife.speedtestnl.nperf.NperfEngine { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature

# Add the official nPerf vendor rules here when the licensed SDK is delivered.
