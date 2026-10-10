package com.android.gallery3d.photowidget.widget

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.appwidget.AppWidgetManager
import com.android.gallery3d.photowidget.R

/**
 * 媒体库变化监听：集合视图要通知"数据变了"（懒加载才会重新取图），单图形态直接重出图。
 */
object MediaContentObserver {

    private var observer: ContentObserver? = null

    fun start(context: Context) {
        if (observer != null) return
        val appContext = context.applicationContext
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val manager = AppWidgetManager.getInstance(appContext)
                // 只刷"桌面上真实存在"的小部件（live ∩ 已配置），孤儿配置不参与
                val ids = WidgetConfigStore.liveConfiguredIds(appContext)
                ids.forEach { manager.notifyAppWidgetViewDataChanged(it, R.id.appwidget_stack_view) }
                WidgetUpdater.updateAsync(appContext, ids.toIntArray())
            }
        }
        appContext.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver,
        )
        observer = contentObserver
    }

    fun stop(context: Context) {
        observer?.let { context.applicationContext.contentResolver.unregisterContentObserver(it) }
        observer = null
    }
}
