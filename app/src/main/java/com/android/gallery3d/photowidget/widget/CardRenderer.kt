package com.android.gallery3d.photowidget.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * 卡片渲染：把照片画成一张卡片（底板边框 + 圆角 + 柔和阴影），全部烘焙进位图。
 *
 * 为什么要"边框"这一层：v1.5 曾把照片铺满整块底板，结果卡片看上去**没有边框**
 * （用户看截图指出「没有边框」）。1.1 的样子是"底板比照片大一圈 + 外圈柔和阴影"，
 * 这里按同样结构画：底板(plate) → 照片内缩 borderWidth → 外圈阴影。
 *
 * 数值来源（1.1 的 9-patch 资产实测，内容 15×15）：
 *  - 中间 9×9 不透明白板（无描边线），外圈 3 art px 柔和阴影（alpha 2→82 ≈ 32%）
 *  - 440dpi 设备按 hdpi(240) 缩放 1.83× → 阴影环 ≈5.5px ≈ 2dp；底板比照片外扩 ≈1 art px ≈ 1dp
 *  圆角/边框/阴影三个数值都写在 dimens 令牌里，**不调 Display.getRoundedCorner()**。
 */
object CardRenderer {

    fun renderCard(
        source: Bitmap,
        width: Int,
        height: Int,
        cornerRadius: Int,
        borderWidth: Int,
        shadowRadius: Int,
        plateColor: Int,
        shadowColor: Int,
    ): Bitmap {
        val outWidth = width.coerceAtLeast(1)
        val outHeight = height.coerceAtLeast(1)
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val inset = shadowRadius.coerceAtLeast(0).toFloat()
        val plate = RectF(inset, inset, outWidth - inset, outHeight - inset)
        val radius = cornerRadius
            .coerceIn(0, (minOf(plate.width(), plate.height()) / 2f).toInt())
            .toFloat()

        // 1) 柔和阴影（对应 1.1 那圈 ~2dp、约 32% 透明度的阴影）
        if (shadowRadius > 0) {
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = shadowColor
                maskFilter = BlurMaskFilter(shadowRadius.toFloat(), BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRoundRect(plate, radius, radius, shadowPaint)
        }

        // 2) 底板 = 边框本体（照片内缩后露出的那一圈）
        val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = plateColor }
        canvas.drawRoundRect(plate, radius, radius, platePaint)

        // 3) 照片内缩 borderWidth，按同一圆角裁进底板 → 露出一圈边框
        val border = borderWidth.coerceAtLeast(0).toFloat()
        val inner = RectF(
            plate.left + border,
            plate.top + border,
            plate.right - border,
            plate.bottom - border,
        )
        if (inner.width() > 1f && inner.height() > 1f) {
            val innerRadius = (radius - border).coerceAtLeast(0f)
            val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.save()
            canvas.clipPath(
                Path().apply { addRoundRect(inner, innerRadius, innerRadius, Path.Direction.CW) },
            )
            canvas.drawBitmap(source, null, inner, photoPaint)
            canvas.restore()
        }

        return output
    }

    /** 卡片底板上"照片之外"的部分——给调用方算照片该按多大出图。 */
    fun photoInsetPx(borderWidth: Int, shadowRadius: Int): Int =
        (borderWidth + shadowRadius).coerceAtLeast(0)
}