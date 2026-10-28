# The Go library is reached through JNI by class and method name; the tunnel module is small, keep it whole.
-keep class org.amnezia.awg.** { *; }
-dontwarn javax.annotation.**
