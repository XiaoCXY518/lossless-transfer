# 无损传输 —— 混淆规则
# 当前 release 未开启 minify（isMinifyEnabled = false），保留此文件以备后续开启。

# 保持 NanoHTTPD 反射相关类
-keep class fi.iki.elonen.** { *; }

# 保持 OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# libtorrent4j —— 官方要求保留 swig JNI 类
-keep class org.libtorrent4j.swig.libtorrent_jni { *; }
-dontwarn org.libtorrent4j.**