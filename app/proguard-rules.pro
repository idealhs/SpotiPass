# Xposed 入口类（xposed_init 声明）
-keep class com.spotipass.module.SpotiPassEntry

# AndroidManifest 中声明的组件
-keep class com.spotipass.module.SpotiPassConfigActivity
-keep class com.spotipass.module.SpotiPassConfigProvider

# 模块在 Spotify 进程内通过反射/Xposed 调用的类，需要保留全部成员
-keep class com.spotipass.module.SpotiPass { *; }
-keep class com.spotipass.module.SpotiPassPrefs { *; }
-keep class com.spotipass.module.SpotiPassPrefs$* { *; }
-keep class com.spotipass.module.SpotiPassConfigClient { *; }
-keep class com.spotipass.module.SpotiPassConfigClient$* { *; }
-keep class com.spotipass.module.SpotiPassFloatingButton { *; }
-keep class com.spotipass.module.SpotiPassConfigDialog { *; }
-keep class com.spotipass.module.SpotiPassRuntimeLog { *; }
-keep class com.spotipass.module.SpotiPassIcon { *; }
-keep class com.spotipass.module.SpotiPassKeys { *; }
