# v1.14-release：已启用 R8（isMinifyEnabled = true + isShrinkResources = true）
# 小部件与配置 Activity 由 AndroidManifest 引用，AAPT 会生成 keep 规则；
# Compose / material3 / coroutines 自带 consumer rules，暂无需手工 keep。
# 如后续 release 出现 NoClassDefFoundError，先查被反射使用的类（RemoteViews 布局与 @RemoteView 相关）。
