package com.android.gallery3d.photowidget.widget

/** 单个小部件实例的配置（对应旧版 launcher.db 的 widgets 表，改存 SharedPreferences）。 */
data class WidgetConfig(
    val appWidgetId: Int,
    val kind: WidgetKind,
    /** ALBUM：相册 bucket id */
    val bucketId: Long? = null,
    /** ALBUM：相册名（仅用于界面显示） */
    val bucketName: String? = null,
    /** SINGLE：图片 uri 字符串 */
    val imageUri: String? = null,
)
