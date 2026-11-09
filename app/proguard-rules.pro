# App ProGuard rules
-dontobfuscate

-optimizations !class/merging/*

# Keep utility classes used by Application and services
-keep class com.kingzcheung.xime.util.** { *; }

-dontwarn com.sun.nio.file.**
-dontwarn kotlin.Cloneable$DefaultImpls

# 插件系统（宿主侧 com.kingzcheung.xime.plugin.* + plugin-core）整体保留：
# JS 桥/序列化/动态约定多，防止 R8 裁剪造成 release 行为缺失
# （库侧自完备规则见 plugin-core/consumer-rules.pro）
-keep class kotlin.Metadata { *; }

-keep class com.kingzcheung.xime.plugin.** { *; }
-keepclassmembers class com.kingzcheung.xime.plugin.** { *; }

-keep class com.kingzcheung.xime.rime.** { *; }
-keep class com.kingzcheung.xime.**Jni** { *; }

-keepattributes SourceFile,LineNumberTable

-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

-processkotlinnullchecks remove
# 跨设备剪贴板 P2P：JNI 按类名/方法名查找符号，混淆后 native 方法会失联
-keep class com.kingzcheung.xime.p2p.P2pNative { *; }
