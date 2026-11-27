# SubTV (Targamgi) R8 obfuscation & shrinking rules.
# Obfuscates account, integrity, gate, and UI Kotlin classes while preserving JNI, WebRTC, and TV installers.

-optimizationpasses 2
-repackageclasses 'o'
-allowaccessmodification

# Strip source file names from stack traces to hinder reverse engineering
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod

# Keep all native JNI methods and their containing classes
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.couchsubs.mobile.remoteplay.NativeBridge { *; }
-keep class com.couchsubs.mobile.remoteplay.NativeOutcome { *; }
-keepclassmembers class * {
    public void onEvent(int, int, java.lang.String);
    public void onFrame(java.nio.ByteBuffer, int, int, int, int, boolean);
}

# Keep third-party libraries that rely on JNI / reflection
-keep class org.webrtc.** { *; }
-keep class com.jcraft.jsch.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.jcraft.jsch.**
-dontwarn org.webrtc.**
-dontwarn java.lang.invoke.**
-dontwarn javax.naming.**
-dontwarn javax.xml.**
-dontwarn org.slf4j.**
