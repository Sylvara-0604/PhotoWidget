package com.android.gallery3d.photowidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import com.android.gallery3d.photowidget.R
import com.android.gallery3d.photowidget.data.MediaRepository
import com.android.gallery3d.photowidget.data.ThumbnailLoader

/**
 * StackView 的数据源（懒加载：宿主真正要某个位置时才解码那张图）。
 *
 * 位图尺寸跟随**实例尺寸**（`WidgetSizing.cardBitmapSizeFor`）→ 出图与显示 1:1，
 * 不会再出现"位图比显示大几 % 被 StackView 缩一次"（边框随之发虚）。
 * 卡片样式（圆角 + 柔和阴影）由 `CardRenderer` 烘焙，它只做叠加、不再缩放。
 *
 * 清晰度：`ThumbnailLoader.loadCardBlocking` 先给快路径（系统缩略图，立刻有图），
 * 若是放大来的就在后台解原图，解好后回调这里刷新一次集合视图换成清晰版。
 */
class PhotoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        PhotoViewsFactory(applicationContext, intent)
}

class PhotoViewsFactory(
    private val context: Context,
    intent: Intent,
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId: Int =
        intent.getIntExtra(PhotoWidgetProvider.EXTRA_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    private val kind: WidgetKind =
        WidgetKind.from(intent.getStringExtra(PhotoWidgetProvider.EXTRA_KIND))
    private val bucketId: Long =
        intent.getLongExtra(PhotoWidgetProvider.EXTRA_BUCKET_ID, -1L)

    private var photos: List<MediaRepository.Photo> = emptyList()
    private var targetWidth = 0
    private var targetHeight = 0

    override fun onCreate() {
        reload()
    }

    override fun onDataSetChanged() {
        reload()
    }

    override fun onDestroy() {
        photos = emptyList()
    }

    override fun getCount(): Int = photos.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position < 0 || position >= photos.size) return getLoadingView()
        val photo = photos[position]
        val card = buildCard(photo) ?: return getLoadingView()
        val views = RemoteViews(context.packageName, R.layout.appwidget_photo_item)
        views.setImageViewBitmap(R.id.appwidget_photo_item, card)
        val fillIn = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(photo.uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        views.setOnClickFillInIntent(R.id.appwidget_photo_frame, fillIn)
        return views
    }

    override fun getLoadingView(): RemoteViews =
        RemoteViews(context.packageName, R.layout.appwidget_loading_item)

    override fun getViewTypeCount(): Int = 2

    override fun getItemId(position: Int): Long =
        if (position in photos.indices) photos[position].id else 0L

    override fun hasStableIds(): Boolean = true

    private fun reload() {
        val (cardWidth, cardHeight) = WidgetSizing.cardBitmapSizeFor(context, appWidgetId)
        targetWidth = cardWidth
        targetHeight = cardHeight
        Log.d(
            TAG,
            "reload widget=$appWidgetId card=${targetWidth}x$targetHeight " +
                WidgetSizing.debugSizes(context, appWidgetId),
        )
        val source = MediaRepository.queryPhotos(
            context,
            bucketId.takeIf { it >= 0L && kind == WidgetKind.ALBUM },
        )
        photos = MediaRepository.orderFor(source, shuffle = kind == WidgetKind.SHUFFLE)
    }

    private fun buildCard(photo: MediaRepository.Photo): Bitmap? {
        val (cardWidth, cardHeight) = WidgetSizing.cardBitmapSizeFor(context, appWidgetId)
        val decoded = ThumbnailLoader.loadCardBlocking(
            context = context,
            uri = photo.uri,
            width = cardWidth,
            height = cardHeight,
            sourceWidth = photo.width,
            sourceHeight = photo.height,
            onSharpReady = { notifySharpReady() },
        ) ?: return null
        return CardRenderer.renderCard(
            source = decoded,
            width = cardWidth,
            height = cardHeight,
            cornerRadius = WidgetSizing.cardCornerRadius(context),
            borderWidth = WidgetSizing.cardBorderWidth(context),
            shadowRadius = WidgetSizing.cardShadowRadius(context),
            plateColor = ContextCompat.getColor(context, R.color.widget_card_plate),
            shadowColor = ContextCompat.getColor(context, R.color.widget_card_shadow_color),
        )
    }

    /** 清晰版就绪：让宿主重新要一遍视图（此时 sharp 缓存已命中，不会二次解码）。 */
    private fun notifySharpReady() {
        val manager = AppWidgetManager.getInstance(context)
        manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.appwidget_stack_view)
    }

    private companion object {
        const val TAG = "PhotoWidget"
    }
}
