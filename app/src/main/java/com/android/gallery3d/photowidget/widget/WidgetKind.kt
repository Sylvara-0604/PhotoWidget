package com.android.gallery3d.photowidget.widget

/** 小部件形态：与旧版 Gallery2 gadget 的三个选项一一对应。 */
enum class WidgetKind {
    /** 选一个相册：显示该相册全部照片（旧版 type 2） */
    ALBUM,

    /** 选一张图片：固定显示这一张（旧版 type 0 相框 / type 1 单图） */
    SINGLE,

    /** 随机显示所有图片（旧版 type 1，排除下载目录） */
    SHUFFLE;

    companion object {
        fun from(name: String?): WidgetKind = entries.firstOrNull { it.name == name } ?: SHUFFLE
    }
}
