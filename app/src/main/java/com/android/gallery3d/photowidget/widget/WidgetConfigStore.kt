package com.android.gallery3d.photowidget.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * 小部件配置持久化。旧版用 SQLite（launcher.db，手写 SQL + 位图 blob）；这里只存几个标量，
 * 用 SharedPreferences 按 appWidgetId 分键即可，不需要数据库。
 *
 * 注意：**没有"轮换游标"**——不自动翻页，切图只由用户手势（StackView 滑动）触发。
 */
object WidgetConfigStore {

    private const val PREFS_NAME = "widget_configs"
    private const val KEY_KIND = "kind_"
    private const val KEY_BUCKET_ID = "bucket_id_"
    private const val KEY_BUCKET_NAME = "bucket_name_"
    private const val KEY_IMAGE_URI = "image_uri_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, config: WidgetConfig) {
        prefs(context).edit()
            .putString(KEY_KIND + config.appWidgetId, config.kind.name)
            .putLong(KEY_BUCKET_ID + config.appWidgetId, config.bucketId ?: -1L)
            .putString(KEY_BUCKET_NAME + config.appWidgetId, config.bucketName)
            .putString(KEY_IMAGE_URI + config.appWidgetId, config.imageUri)
            .apply()
    }

    fun load(context: Context, appWidgetId: Int): WidgetConfig? {
        val p = prefs(context)
        val kind = p.getString(KEY_KIND + appWidgetId, null) ?: return null
        val bucketId = p.getLong(KEY_BUCKET_ID + appWidgetId, -1L).takeIf { it >= 0L }
        return WidgetConfig(
            appWidgetId = appWidgetId,
            kind = WidgetKind.from(kind),
            bucketId = bucketId,
            bucketName = p.getString(KEY_BUCKET_NAME + appWidgetId, null),
            imageUri = p.getString(KEY_IMAGE_URI + appWidgetId, null),
        )
    }

    fun delete(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove(KEY_KIND + appWidgetId)
            .remove(KEY_BUCKET_ID + appWidgetId)
            .remove(KEY_BUCKET_NAME + appWidgetId)
            .remove(KEY_IMAGE_URI + appWidgetId)
            .apply()
    }

    /**
     * SharedPreferences 里存有配置的 id（可能包含**已从桌面删除**的孤儿配置：
     * 某些删除路径不会回调 `onDeleted`）。
     */
    private fun configuredIdSet(context: Context): Set<Int> =
        prefs(context).all.keys
            .filter { it.startsWith(KEY_KIND) }
            .mapNotNull { it.removePrefix(KEY_KIND).toIntOrNull() }
            .toSet()

    /** 桌面上**真实存在**的小部件 id（由 AppWidgetManager 给出，权威值）。 */
    fun liveWidgetIds(context: Context): Set<Int> {
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, PhotoWidgetProvider::class.java)
        return manager.getAppWidgetIds(provider).toSet()
    }

    /**
     * 给界面显示的"已添加的小部件"数量：**只数桌面上真实存在的**（live ∩ 已配置），
     * 顺手把孤儿配置（已删除的小部件留下的 SharedPreferences 键）清掉。
     * 之前的实现直接数 `kind_*` 键，会把孤儿算进来 —— 桌面 1 个却显示 2 个就是这么来的。
     */
    fun liveConfiguredIds(context: Context): Set<Int> {
        val live = liveWidgetIds(context)
        val configured = configuredIdSet(context)
        (configured - live).forEach { delete(context, it) }
        return configured intersect live
    }
}
