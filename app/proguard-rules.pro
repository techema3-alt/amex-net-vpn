# JSch loads its algorithms by class name, keep it whole
-keep class com.jcraft.jsch.** { *; }
# JNI looks this class up by name
-keep class hev.htproxy.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keepattributes Signature,InnerClasses,*Annotation*
-dontwarn **
