package com.android.gallery3d.photowidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.android.gallery3d.photowidget.R
import com.android.gallery3d.photowidget.data.MediaRepository
import com.android.gallery3d.photowidget.data.ThumbnailLoader

/**
 * 小部件入口。
 *
 * 形态（三种，与旧版一一对应）：
 *  - ALBUM / SHUFFLE → `appwidget_main`：**StackView 做手势宿主**（滑动切图、多张同时堆叠、自带斜率），
 *    数据由 `PhotoWidgetService`（RemoteViewsService）**懒加载**；卡片由 `CardRenderer` **自绘**
 *    （阴影 → 底板(＝边框) → 照片，令牌取自 1.1 实测；**不用 9-patch**，v1.10 起 res 里已无任何 9-patch）；
 *  - SINGLE → `photo_frame`：固定一张。
 *
 * **不自动翻页**：`updatePeriodMillis=0`，切图只由用户手势触发；媒体库变化时刷新内容。
 * 出图在后台协程（onUpdate 用 goAsync 保住广播生命周期）。
 */
class PhotoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 不推进游标、不自动换图，只按当前状态出图
        WidgetUpdater.updateAsync(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        // 诊断：实例尺寸/密度变化时打一行（换设备、改密度、拖小部件都会走到这里）
        android.util.Log.d(
            "PhotoWidget",
            "optionsChanged widget=$appWidgetId " + WidgetSizing.describeOptions(context, appWidgetId) +
                " card=" + WidgetSizing.cardBitmapSizeFor(context, appWidgetId),
        )
        // 尺寸变化：集合视图重新绑数据（堆叠密度随之自适应）
        WidgetUpdater.updateAsync(context, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetConfigStore.delete(context, it) }
    }

    override fun onEnabled(context: Context) {
        MediaContentObserver.start(context)
    }

    override fun onDisabled(context: Context) {
        MediaContentObserver.stop(context)
    }

    companion object {

        const val EXTRA_APP_WIDGET_ID = "appWidgetId"
        const val EXTRA_KIND = "widget-kind"
        const val EXTRA_BUCKET_ID = "album-bucket-id"

        fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            manager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
        }

        fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
            val config = WidgetConfigStore.load(context, appWidgetId)
                ?: return messageViews(context, R.string.widget_not_configured)
            if (!MediaRepository.hasPhotoPermission(context)) {
                return messageViews(context, R.string.permission_message)
            }
            return when (config.kind) {
                WidgetKind.SINGLE -> singleViews(context, config)
                WidgetKind.ALBUM, WidgetKind.SHUFFLE -> stackViews(context, config)
            }
        }

        /** 空态/提示态：显示空视图，隐藏 StackView。 */
        private fun messageViews(context: Context, messageRes: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.appwidget_main)
            views.setViewVisibility(R.id.appwidget_empty_view, View.VISIBLE)
            views.setViewVisibility(R.id.appwidget_stack_view, View.GONE)
            views.setTextViewText(R.id.appwidget_empty_text, context.getString(messageRes))
            return views
        }

        /** 堆叠形态：StackView + RemoteViewsService（懒加载），点击走显式跳板。 */
        private fun stackViews(context: Context, config: WidgetConfig): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.appwidget_main)
            val serviceIntent = Intent(context, PhotoWidgetService::class.java).apply {
                putExtra(EXTRA_APP_WIDGET_ID, config.appWidgetId)
                putExtra(EXTRA_KIND, config.kind.name)
                putExtra(EXTRA_BUCKET_ID, config.bucketId ?: -1L)
                data = Uri.parse("widget://photowidget/${config.appWidgetId}")
            }
            views.setRemoteAdapter(R.id.appwidget_stack_view, serviceIntent)
            views.setEmptyView(R.id.appwidget_stack_view, R.id.appwidget_empty_view)
            views.setPendingIntentTemplate(
                R.id.appwidget_stack_view,
                clickTemplate(context, config.appWidgetId),
            )
            return views
        }

        /** 单图形态（固定一张，不可翻页）。位图同样跟随实例尺寸，出图与显示 1:1。 */
        private fun singleViews(context: Context, config: WidgetConfig): RemoteViews {
            val uri = config.imageUri?.let { Uri.parse(it) }
                ?: return messageViews(context, R.string.widget_not_configured)
            val (width, height) = WidgetSizing.cardBitmapSizeFor(context, config.appWidgetId)
            val card = ThumbnailLoader.loadCardBlocking(
                context = context,
                uri = uri,
                width = width,
                height = height,
                sourceWidth = 0, // 单图形态拿不到 MediaStore 宽高，走"先试系统缩略图"的保守路径
                sourceHeight = 0,
                onSharpReady = { refreshSingle(context, config.appWidgetId) },
            ) ?: return messageViews(context, R.string.appwidget_empty_text)
            val views = RemoteViews(context.packageName, R.layout.photo_frame)
            views.setImageViewBitmap(R.id.photo, card)
            views.setOnClickPendingIntent(
                R.id.photo,
                viewIntent(context, config.appWidgetId, uri),
            )
            return views
        }

        /** 后台清晰版就绪后重出一张（此时 sharp 缓存命中，不会二次解码）。 */
        private fun refreshSingle(context: Context, appWidgetId: Int) {
            WidgetUpdater.updateAsync(context, appWidgetId)
        }

        /**
         * 点击模板：每个 item 用 `setOnClickFillInIntent` 补上自己的 uri。
         * **必须显式 Intent**：Android 14+ 禁止 FLAG_MUTABLE 配隐式 Intent（v1.0 因此崩过）。
         */
        private fun clickTemplate(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, PhotoOpenActivity::class.java)
            return PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /** 单图形态的点击：目标固定，用 FLAG_IMMUTABLE。 */
        private fun viewIntent(context: Context, requestCode: Int, uri: Uri): PendingIntent {
            val intent = Intent(context, PhotoOpenActivity::class.java).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
