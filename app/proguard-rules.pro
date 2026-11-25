# R8 rules for the release build (debug builds are not shrunk).
# Masal is open source: shrink and optimise, but keep the names, so stack traces stay readable.
-dontobfuscate

# NewPipe Extractor (YouTube trailers, seerr.md) looks up some of its classes by name (time-ago
# patterns per language) and carries protobuf messages; kept whole, it is small.
-keep class org.schabi.newpipe.extractor.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# Rhino runs YouTube's player JavaScript (signature and n parameter) and generates and loads
# classes at run time.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter

# Desktop Java APIs that these libraries reference but never use on Android.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn javax.lang.model.**
-dontwarn org.mozilla.javascript.tools.**
-dontwarn jdk.dynalink.**
