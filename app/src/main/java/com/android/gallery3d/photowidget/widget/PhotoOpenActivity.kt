package com.android.gallery3d.photowidget.widget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.android.gallery3d.photowidget.R

/**
 * 点击跳板：小部件点某张照片时，由这里转发 ACTION_VIEW。
 *
 * 为什么需要它：小部件（StackView 集合）的点击必须用 `setPendingIntentTemplate` + fill-in intent，
 * 而 Android 14+（targetSdk 34+）**禁止用 FLAG_MUTABLE 创建隐式 Intent 的 PendingIntent**——
 * 旧版做法 `PendingIntent.getActivity(ctx, 0, Intent(ACTION_VIEW), FLAG_MUTABLE)` 会直接
 * 抛 IllegalArgumentException 让进程崩溃（本工程实测踩过）。
 * 正解：模板用**显式** Intent 指向本 Activity，再由这里把 fill-in 带过来的 uri 转成
 * ACTION_VIEW 打开（uri 由 fill-in 写入 data/type）。
 */
class PhotoOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        val mimeType = intent.type ?: "image/*"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
