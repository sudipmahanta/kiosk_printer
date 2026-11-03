# CSN printer SDK: its serial-port and compression classes are reached from
# native code (JNI) by name, so release shrinking must not rename or drop them.
-keep class com.csnprintersdk.** { *; }
-keep class csnprint_serialport_api.** { *; }
-keep class csnprint_compress_libs_api.** { *; }
-dontwarn com.csnprintersdk.**
