package com.android.gallery3d.photowidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 小部件出图的统一入口：解码缩略图是有成本的，一律放到后台协程，主线程只做调度。
 */
object WidgetUpdater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun updateAsync(context: Context, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val appContext = context.applicationContext
        scope.launch { update(appContext, appWidgetIds) }
    }

    fun updateAsync(context: Context, appWidgetId: Int) = updateAsync(context, intArrayOf(appWidgetId))

    /** 真正的出图（在后台协程里跑）。 */
    suspend fun update(context: Context, appWidgetIds: IntArray) {
        val manager = AppWidgetManager.getInstance(context)
        appWidgetIds.forEach { PhotoWidgetProvider.updateWidget(context, manager, it) }
    }

    /** 供 goAsync() 场景复用同一个 scope。 */
    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}
