package com.android.gallery3d.photowidget.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 缩略图管线（替代 Coil —— 本机离线缓存里没有 Coil，也不允许联网拉依赖）。
 *
 * ## 为什么不能只用 `loadThumbnail`
 *
 * 2026-10-02 真机实测：`ContentResolver.loadThumbnail(req=440x264)` 的**实际交付远小于请求**
 * （132x287 / 137x274 / 177x289 / 180x319 / 183x274，仅请求的 29%~45%），
 * 而 `coverScale` 又会把它放大 2.3~3.3 倍填满目标 ⇒ 成图永久性发虚。
 * 根因是 MediaStore 缩略图有尺寸上限（约 512 长边），且它在"缩略图不够大"时
 * **自己也要解码一遍原图**去生成 → 旧路径在多数图上等于"解两遍原图、还很糊"。
 *
 * ## 现在的策略（两级 + 后台升级）
 *
 * 1. **能靠系统就拿系统的**：源图长边 ≥ [THUMB_SOURCE_MIN_SIDE] 时先试 `loadThumbnail`
 *    （命中系统缓存是几十毫秒）。交付尺寸达标 → 直接收工。
 * 2. **不够就自己解原图**：`ImageDecoder.setTargetSize` 让编解码器**在解码过程中缩放**
 *    （不是先解全图再缩），并自动处理 EXIF 旋转；再用 [coverScale] 精确 center-crop。
 * 3. **预判省掉白跑**：源图长边 < 512 时系统必然交付不够大 → **跳过快路径**，
 *    省掉一次"框架解原图 + 写缩略图缓存"。宽高来自 `MediaRepository.queryPhotos()` 顺带查的列。
 * 4. **先快后清（视觉掩盖）**：首帧返回快路径位图保证立刻有图；若它是放大来的（面积 < [SHARP_MIN_AREA]），
 *    后台协程解码"清晰版"并按同一个 key 存进**另一层缓存** [sharp]，下次请求该位置即命中。
 *
 * ## 尺寸契约（别改回去）
 *
 * 调用方（`PhotoWidgetService` / `PhotoWidgetProvider`）传的必须是**卡片位图的完整尺寸**
 * （照片净尺寸 + 2×(边框 + 阴影)），不是照片净尺寸 —— 图在这里就裁好了，
 * `CardRenderer` 只负责在它上面画底板/边框/阴影，不再二次缩放。
 * 尺寸一变 key 就变，所以位图尺寸要**跟随实例尺寸**（`WidgetSizing.cardBitmapSizeFor`），
 * 不要用 dp 令牌直算，否则与显示尺寸差几 % 又会被 StackView 缩一次。
 */
object ThumbnailLoader {

    private const val TAG = "PhotoWidget"

    /** MediaStore 缩略图的长边上限（实测口径）：源图长边低于此值，系统必然给不出够大的缩略图。 */
    private const val THUMB_SOURCE_MIN_SIDE = 512

    /** 快路径位图面积低于此值就认为"是放大来的、不够清晰"，需要后台升级。（440x264 ≈ 116k） */
    const val SHARP_MIN_AREA = 150_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 第一级缓存：对外交付的位图（按 uri+目标像素尺寸）。 */
    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** 第二级缓存：后台解出来的"清晰版"（与快路径同 key，优先返回）。 */
    private val sharp = object : LruCache<String, Bitmap>(cacheSizeKb() / 2) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** 同一 key 的并发请求合并成一次解码。 */
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    private fun cacheSizeKb(): Int {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (maxMemoryKb / 8).coerceAtLeast(4 * 1024)
    }

    /** 异步取图（可在 Compose 的 produceState / 协程里直接 await）。 */
    suspend fun load(context: Context, uri: Uri, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val appContext = context.applicationContext
        val key = key(uri, width, height)
        sharp.get(key)?.let { return it }
        cache.get(key)?.let { return it }
        val deferred = inFlight.getOrPut(key) {
            scope.async { decode(appContext, uri, width, height, sourceWidth = 0, sourceHeight = 0) }
        }
        return try {
            val bitmap = deferred.await()
            if (bitmap != null) cache.put(key, bitmap)
            bitmap
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    /**
     * 卡片位图（给小部件用，非挂起环境）。
     *
     * @param sourceWidth/sourceHeight 原图像素尺寸（MediaStore 的 WIDTH/HEIGHT）；<=0 表示未知，
     *                       此时保守走"先试系统缩略图"。
     * @param onSharpReady 后台清晰版解好后的回调（在 IO 线程）。给 RemoteViews 用来触发一次刷新。
     */
    fun loadCardBlocking(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        onSharpReady: (() -> Unit)? = null,
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val appContext = context.applicationContext
        val key = key(uri, width, height)
        sharp.get(key)?.let { return it }
        val placed = cache.get(key)
        if (placed != null) {
            if (!isSharp(key)) {
                maybeUpgrade(appContext, uri, width, height, key, placed, sourceWidth, sourceHeight, onSharpReady)
            }
            return placed
        }
        val bitmap = runBlocking {
            val deferred = inFlight.getOrPut(key) {
                scope.async { decode(appContext, uri, width, height, sourceWidth, sourceHeight) }
            }
            try {
                deferred.await()
            } finally {
                inFlight.remove(key, deferred)
            }
        } ?: return null
        cache.put(key, bitmap)
        maybeUpgrade(appContext, uri, width, height, key, bitmap, sourceWidth, sourceHeight, onSharpReady)
        return bitmap
    }

    /** 位图是不是放大来的（面积不足）→ 值不值得后台再解一遍原图。 */
    private fun needsSharp(bitmap: Bitmap): Boolean =
        bitmap.width.toLong() * bitmap.height.toLong() < SHARP_MIN_AREA

    /**
     * 后台升级为清晰版：解原图 → 存进 [sharp] → 回调让 RemoteViews 刷新一次。
     * 只在"当前交付是放大来的"时触发，避免对本来够清晰的图白做一遍解码。
     */
    private fun maybeUpgrade(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
        key: String,
        current: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        onSharpReady: (() -> Unit)?,
    ) {
        if (!needsSharp(current)) return
        if (upgrading.contains(key)) return
        upgrading.add(key)
        scope.launch {
            try {
                val better = decodeFromOriginal(context, uri, width, height, sourceWidth, sourceHeight)
                    ?: return@launch
                sharp.put(key, better)
                Log.d(TAG, "sharp $uri ${better.width}x${better.height}")
                onSharpReady?.invoke()
            } catch (t: Throwable) {
                Log.w(TAG, "sharp decode failed: $uri", t)
            } finally {
                upgrading.remove(key)
            }
        }
    }

    /** 正在后台升级的 key，避免重复排队。 */
    private val upgrading = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun isSharp(key: String): Boolean = sharp.get(key) != null

    /** 给非挂起环境（AppWidgetProvider / RemoteViewsFactory）用。 */
    fun loadBlocking(context: Context, uri: Uri, width: Int, height: Int): Bitmap? =
        runBlocking { load(context, uri, width, height) }

    fun clear() {
        cache.evictAll()
        sharp.evictAll()
    }

    private fun key(uri: Uri, width: Int, height: Int) = "$uri@${width}x$height"

    /**
     * 出图主决策。
     * @param sourceWidth/sourceHeight 原图尺寸；长边 >0 且 < [THUMB_SOURCE_MIN_SIDE] 时跳过快路径。
     */
    private fun decode(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Bitmap? {
        val sourceLongSide = maxOf(sourceWidth, sourceHeight)
        val systemThumbCouldFit = sourceLongSide <= 0 || sourceLongSide >= THUMB_SOURCE_MIN_SIDE
        if (systemThumbCouldFit) {
            loadSystemThumbnail(context, uri, width, height)?.let { return it }
        }
        return decodeFromOriginal(context, uri, width, height, sourceWidth, sourceHeight)
            ?: loadSystemThumbnail(context, uri, width, height)
    }

    /** 快路径：系统缩略图（精确 center-crop 到目标尺寸）。 */
    private fun loadSystemThumbnail(context: Context, uri: Uri, width: Int, height: Int): Bitmap? {
        val loaded = runCatching {
            context.contentResolver.loadThumbnail(uri, Size(width, height), null)
        }.onFailure {
            // 解不出来会退回加载态（卡片变灰板），所以必须留痕，方便 logcat 定位是哪张图
            Log.w(TAG, "loadThumbnail failed: $uri @${width}x$height", it)
        }.getOrNull() ?: return null
        Log.d(TAG, "thumb $uri req=${width}x$height got=${loaded.width}x${loaded.height}")
        val exact = coverScale(loaded, width, height)
        if (exact !== loaded) loaded.recycle()
        return exact
    }

    /**
     * 慢路径（也是清晰度来源）：直接解码原文件到目标尺寸。
     *
     * `ImageDecoder.setTargetSize` 让编解码器**在解码过程中**按需缩放（不必先解全图再缩），
     * 且会自动套用 EXIF 旋转（API 28+），所以这里不需要额外处理方向。
     *
     * ⚠️ **别把 (卡片宽, 卡片高) 直接交给 setTargetSize**：2026-10-02 实测（Android 17 设备）
     * 它不按长宽比走 —— 竖图 1920x3840 也被解成 405x253（等于横向压扁）。
     * 这里改成**自己按原图长宽比算目标**（保证覆盖卡片、且最短边也够大），再 cover 裁切。
     *
     * @param sourceWidth/sourceHeight 原图像素尺寸（来自 MediaStore）；<=0 时退回 1.1 倍保守值。
     */
    private fun decodeFromOriginal(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
    ): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        // 调用方没给原图宽高（例如"选一张图片"形态）时，现查一次 MediaStore 的 WIDTH/HEIGHT，
        // 否则 fallback 目标 (1.1x) 与卡片比例不符 → 会被 setTargetSize 压扁。
        val srcW: Int
        val srcH: Int
        if (sourceWidth > 0 && sourceHeight > 0) {
            srcW = sourceWidth
            srcH = sourceHeight
        } else {
            val probed = querySourceSize(context, uri)
            srcW = probed?.first ?: 0
            srcH = probed?.second ?: 0
        }
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            val targetW: Int
            val targetH: Int
            if (srcW > 0 && srcH > 0) {
                // 等比放大/缩小到"刚好覆盖 width x height"
                val scale = max(
                    width.toFloat() / srcW,
                    height.toFloat() / srcH,
                )
                targetW = (srcW * scale).roundToInt().coerceAtLeast(1)
                targetH = (srcH * scale).roundToInt().coerceAtLeast(1)
            } else {
                targetW = (width * 1.1f).toInt().coerceAtLeast(1)
                targetH = (height * 1.1f).toInt().coerceAtLeast(1)
            }
            decoder.setTargetSize(targetW, targetH)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        Log.d(
            TAG,
            "orig $uri src=${srcW}x$srcH decoded=${decoded.width}x${decoded.height} " +
                "card=${width}x$height",
        )
        val exact = coverScale(decoded, width, height)
        if (exact !== decoded) decoded.recycle()
        exact
    }.onFailure {
        Log.w(TAG, "decodeFromOriginal failed: $uri @${width}x$height", it)
    }.getOrNull()

    /** 查 MediaStore 里这张图的原始宽高（取不到返回 null）。 */
    private fun querySourceSize(context: Context, uri: Uri): Pair<Int, Int>? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(
                android.provider.MediaStore.Images.Media.WIDTH,
                android.provider.MediaStore.Images.Media.HEIGHT,
            ),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val w = c.getInt(0)
                val h = c.getInt(1)
                if (w > 0 && h > 0) w to h else null
            } else {
                null
            }
        }
    }.getOrNull()

    /** 等比缩放到刚好铺满目标并居中裁剪。 */
    private fun coverScale(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) return source
        val scale = max(
            targetWidth.toFloat() / source.width,
            targetHeight.toFloat() / source.height,
        )
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val left = (targetWidth - scaledWidth) / 2
        val top = (targetHeight - scaledHeight) / 2
        canvas.drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            Rect(left, top, left + scaledWidth, top + scaledHeight),
            paint,
        )
        return output
    }
}
