package com.android.gallery3d.photowidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.android.gallery3d.photowidget.R
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 小部件几何。
 *
 * 位图尺寸策略（重要，别改回去）：
 *  - **卡片位图尺寸跟随实例真实尺寸**（[cardBitmapSizeFor]，按卡片宽高比在可用区内取最大内接矩形）——
 *    出图与显示做到 1:1，不会被 StackView/launcher 再缩一次（缩一次边框与照片都会发虚，v1.12 修）。
 *  - 令牌尺寸（[cardBitmapSize]：160x96dp 照片 + 2x(3dp 边框 + 2dp 阴影)）只在拿不到实例尺寸时兜底，
 *    也用作"卡片宽高比"的来源（1.604）。
 *  - 两者都过 [capArea] 封顶面积，避免 RemoteViews 跨进程位图过大。
 *  - 用户拖动尺寸 → 位图尺寸变化 → `ThumbnailLoader` 的 LruCache key 变化（重建一次，可接受）。
 */
object WidgetSizing {

    /** 跨进程位图面积上限（像素²）：ARGB_8888 下约 0.8MB，留足 Binder 余量。 */
    private const val MAX_BITMAP_AREA = 200_000

    /**
     * 卡片位图：实例在桌面上的像素尺寸。
     *
     * ⚠️ 两个坑（2026-10-02 在 440dpi 设备上实测踩到）：
     * 1. `getAppWidgetOptions` 的宽度/高度是 **dp**（官方文档口径 "in dips"），要 **dp × density → 像素**；
     * 2. **不能直接用 MIN/MAX_WIDTH**：MIN 是窄档（横放）、MAX 是宽档（横屏）。实测该机 options：
     *    `min=282x130dp  max=545x288dp  sizes=[282.5x288.0 | 282.5x287.3 | 545.5x130.2 | 545.5x130.2]`，
     *    而小部件在桌面上实占 **竖屏 282.5x288dp**。用 MAX 算出的位图宽了 1.93 倍；
     *    改用 MIN 也不行（282x130 是横放档，按卡片比例套用得 357x223dp→981x613px，宽 2.7 倍）。
     *
     * 现在优先用 **`OPTION_APPWIDGET_SIZES`（API 31+，`List<SizeF>`，单位 dp）**：取其中**面积最大**的一档
     * （orientation 变化时 launcher 会刷新该列表，当前朝向的那档面积通常最大；竖屏档/横向档自动区分），
     * 再 `dp × density → 像素`；MIN 只在拿不到 sizes 时兜底。
     */
    fun widgetSize(context: Context, appWidgetId: Int): Pair<Int, Int> {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val density = context.resources.displayMetrics.density
        val defaultWidth = context.resources.getDimensionPixelSize(R.dimen.widget_min_width)
        val defaultHeight = context.resources.getDimensionPixelSize(R.dimen.widget_min_height)

        // 1) 首选 appWidgetSizes：取面积最大的一档（= 当前朝向实际占的那档）
        val best = sizesFrom(options)
            .filter { it.first > 0f && it.second > 0f }
            .maxByOrNull { it.first * it.second }
        if (best != null) {
            return capArea(
                (best.first * density).roundToInt().coerceAtLeast(1),
                (best.second * density).roundToInt().coerceAtLeast(1),
            )
        }

        // 2) 兜底：MIN（窄档）—— 只保证"不比实际小"，宁可略大也不要糊
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val width = if (widthDp > 0) (widthDp * density).roundToInt() else defaultWidth
        val height = if (heightDp > 0) (heightDp * density).roundToInt() else defaultHeight
        return capArea(width, height)
    }

    /** 从 options 取 `OPTION_APPWIDGET_SIZES`（`List<SizeF>`，单位 dp）。兼容 Bundle 的两种取法。 */
    private fun sizesFrom(options: android.os.Bundle): List<Pair<Float, Float>> {
        val key = AppWidgetManager.OPTION_APPWIDGET_SIZES
        @Suppress("DEPRECATION")
        val raw = options.get(key)
        val list = when (raw) {
            is Array<*> -> raw.mapNotNull { it as? android.util.SizeF }
            is ArrayList<*> -> raw.mapNotNull { it as? android.util.SizeF }
            else -> emptyList()
        }
        return list.map { it.width to it.height }
    }

    /**
     * 堆叠卡片照片的尺寸 = 1.1 的 160×96dp 令牌（计算用；实际出图尺寸见 [cardBitmapSizeFor]）。
     */
    fun cardPhotoSize(context: Context): Pair<Int, Int> {
        val res = context.resources
        return capArea(
            res.getDimensionPixelSize(R.dimen.stack_photo_width),
            res.getDimensionPixelSize(R.dimen.stack_photo_height),
        )
    }

    /** 堆叠卡片位图尺寸（"照片 + 2×(边框 + 阴影)"的完整外框，按令牌 dp 算）。 */
    fun cardBitmapSize(context: Context): Pair<Int, Int> {
        val (photoWidth, photoHeight) = cardPhotoSize(context)
        val frame = cardBorderWidth(context) + cardShadowRadius(context)
        return capArea(photoWidth + 2 * frame, photoHeight + 2 * frame)
    }

    /**
     * 卡片位图的**实际出图尺寸**：按小部件在桌面上的真实尺寸取，而不是 dp 令牌直算。
     *
     * 为什么：令牌 160×96dp + 3dp 边框 + 2dp 阴影 = 468×292px（density 2.75），
     * 而实测前台卡片在桌面上显示 452×275px（StackView 给的上限），于是位图又被缩 0.96×——
     * **边框那 8px 就跟着被缩小、照片也白白过一道滤波**。
     * 这里改成跟随实例尺寸（保持卡片 160:96 的宽高比、取能放下的最大值），
     * 让"出图尺寸 = 显示尺寸"做到 1:1；尺寸变化只发生在用户拖动小部件时（LruCache 重建一次，可接受）。
     *
     * 取不到实例尺寸时自动退回令牌尺寸。
     */
    fun cardBitmapSizeFor(context: Context, appWidgetId: Int): Pair<Int, Int> {
        val token = cardBitmapSize(context)
        val (availWidth, availHeight) = widgetSize(context, appWidgetId)
        if (availWidth <= 0 || availHeight <= 0) return token
        val ratio = token.first.toFloat() / token.second.toFloat()
        // 在可用区域内按卡片宽高比取最大内接矩形
        var width = availWidth
        var height = (width / ratio).roundToInt()
        if (height > availHeight) {
            height = availHeight
            width = (height * ratio).roundToInt()
        }
        if (width <= 0 || height <= 0) return token
        return capArea(width, height)
    }

    /**
     * 诊断：返回 (options 原值, 换算出的可用像素, 最终卡片位图)。用来核对
     * "options 到底是 dp 还是 px" 以及 "出图尺寸是否等于显示尺寸"。
     */
    /**
     * 诊断：返回 (options 原值, 换算出的可用像素, 最终卡片位图)。用来核对
     * "options 单位是否 dp" 以及 "出图尺寸是否等于显示尺寸"（换设备/改密度时最有用的两行）。
     */
    fun debugSizes(context: Context, appWidgetId: Int): String {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val density = context.resources.displayMetrics.density
        fun opt(key: String) = options.getInt(key, -1)
        val (w, h) = widgetSize(context, appWidgetId)
        val (cw, ch) = cardBitmapSizeFor(context, appWidgetId)
        val sizes = sizesFrom(options).joinToString("|") { "%.1fx%.1f".format(it.first, it.second) }
        return "density=$density availPx=${w}x$h cardPx=${cw}x$ch " +
            "min=${opt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}x" +
            "${opt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)}dp " +
            "max=${opt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)}x" +
            "${opt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)}dp sizes=[$sizes]"
    }

    /** 诊断用：把实例可用尺寸与选项值打成一行，便于核对"出图尺寸 vs 显示尺寸"。 */
    fun describeOptions(context: Context, appWidgetId: Int): String {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val density = context.resources.displayMetrics.density
        fun opt(key: String) = options.getInt(key, -1)
        return "density=$density min=${opt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}x" +
            "${opt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)} " +
            "max=${opt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)}x" +
            "${opt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)}"
    }

    /** 卡片圆角（令牌，数值取自 1.1 实测）。 */
    fun cardCornerRadius(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.widget_card_corner)

    /** 卡片边框宽度 = 照片相对底板的内缩量（令牌，数值取自 1.1 实测 ≈1dp）。 */
    fun cardBorderWidth(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.widget_card_border)

    /** 卡片柔和阴影厚度（令牌，数值取自 1.1 实测）。 */
    fun cardShadowRadius(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.widget_card_shadow)

    private fun capArea(width: Int, height: Int): Pair<Int, Int> {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val area = w.toLong() * h.toLong()
        if (area <= MAX_BITMAP_AREA) return w to h
        val scale = sqrt(MAX_BITMAP_AREA.toDouble() / area.toDouble())
        return (w * scale).roundToInt().coerceAtLeast(1) to
            (h * scale).roundToInt().coerceAtLeast(1)
    }
}
