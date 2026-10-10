package com.android.gallery3d.photowidget.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * MediaStore 查询（只支持 API 33+，已删掉所有低版本兜底分支：不再有 READ_EXTERNAL_STORAGE、
 * 不再有 BitmapFactory 解码、不再有用 DATA 列反推 bucket id 的 <29 路径）。
 *
 * 旧版这条线是 Gallery2 私有的 LocalPhotoSource / DataManager / MediaSet / Path 世界；
 * 这里全部换成纯 MediaStore（相册按 BUCKET_ID 分组、图片 datetaken 倒序、全部图片时排除下载目录）。
 */
object MediaRepository {

    data class Bucket(val id: Long, val name: String, val count: Int, val coverUri: Uri)

    data class Photo(val id: Long, val uri: Uri, val dateTaken: Long, val width: Int, val height: Int)

    private const val DOWNLOADS_BUCKET = "Download"

    // ---- 权限 ----

    /** 需要申请的权限：API 34+ 追加"仅选中的照片"，让用户能只授权部分照片。 */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
        } else {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        }

    /** 完全授权或"仅选中的照片"部分授权都算可用。 */
    fun hasPhotoPermission(context: Context): Boolean {
        if (granted(context, Manifest.permission.READ_MEDIA_IMAGES)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    /** 是否只是部分授权（可提示用户补全授权）。 */
    fun isPartialAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !granted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ---- 查询 ----

    /** 全部相册（按照片数倒序），排除下载目录。 */
    fun queryBuckets(context: Context): List<Bucket> {
        if (!hasPhotoPermission(context)) return emptyList()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val counts = HashMap<Long, Int>()
        val buckets = LinkedHashMap<Long, Bucket>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                if (isDownloads(name)) continue
                val bucketId = cursor.getLong(bucketIdColumn)
                counts[bucketId] = (counts[bucketId] ?: 0) + 1
                if (!buckets.containsKey(bucketId)) {
                    buckets[bucketId] = Bucket(bucketId, name, 0, imageUri(cursor.getLong(idColumn)))
                }
            }
        }
        return buckets.values
            .map { it.copy(count = counts[it.id] ?: 0) }
            .sortedByDescending { it.count }
    }

    /**
     * 照片列表（datetaken DESC）。
     * @param bucketId 指定相册；null = 全部图片（排除下载目录，与旧版 SELECTION 一致）
     *
     * **顺带查 WIDTH/HEIGHT**：同一张 cursor 多取两列，零额外查询成本；
     * 出图时用它预判"系统缩略图够不够大"，避免白跑一次系统缩略图生成（见 ThumbnailLoader）。
     */
    fun queryPhotos(context: Context, bucketId: Long?): List<Photo> {
        if (!hasPhotoPermission(context)) return emptyList()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val selection: String?
        val selectionArgs: Array<String>?
        if (bucketId != null) {
            selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
            selectionArgs = arrayOf(bucketId.toString())
        } else {
            selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IS NOT NULL " +
                "AND ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} != ?"
            selectionArgs = arrayOf(DOWNLOADS_BUCKET)
        }
        val photos = ArrayList<Photo>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val widthColumn = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                photos += Photo(
                    id = id,
                    uri = imageUri(id),
                    dateTaken = if (dateColumn >= 0) cursor.getLong(dateColumn) else 0L,
                    width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0,
                    height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0,
                )
            }
        }
        return photos
    }

    fun imageUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /** 堆叠形态的取图顺序：SHUFFLE 打乱，ALBUM 保持时间倒序。 */
    fun orderFor(photos: List<Photo>, shuffle: Boolean): List<Photo> =
        if (shuffle) photos.shuffled() else photos

    private fun isDownloads(name: String): Boolean =
        name.equals(DOWNLOADS_BUCKET, ignoreCase = true) || name.equals("Downloads", ignoreCase = true)
}
