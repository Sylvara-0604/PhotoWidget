package com.android.gallery3d.photowidget.config

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.gallery3d.photowidget.R
import com.android.gallery3d.photowidget.data.MediaRepository
import com.android.gallery3d.photowidget.data.ThumbnailLoader
import com.android.gallery3d.photowidget.ui.theme.PhotoWidgetTheme
import com.android.gallery3d.photowidget.widget.WidgetConfig
import com.android.gallery3d.photowidget.widget.WidgetConfigStore
import com.android.gallery3d.photowidget.widget.WidgetKind
import com.android.gallery3d.photowidget.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 添加小部件时的配置页（对应旧版 WidgetConfigure + WidgetTypeChooser）。
 * 旧版是"透明 Activity 路由 + 系统 Gallery 的相册选择器/裁剪器"；这里用 Compose 自己画，
 * 选图走系统的照片选择器（PickVisualMedia），不再依赖 Gallery2 的 Activity。
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 旧版同样先置 RESULT_CANCELED：用户中途取消就不留下半配置的小部件
        setResult(Activity.RESULT_CANCELED)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            PhotoWidgetTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConfigScreen(
                        appWidgetId = appWidgetId,
                        onCancel = { finish() },
                        onDone = { config -> applyConfig(config) },
                    )
                }
            }
        }
    }

    private fun applyConfig(config: WidgetConfig) {
        WidgetConfigStore.save(this, config)
        // 出图放到后台协程（做缩略图解码需要时间，别卡主线程）
        WidgetUpdater.updateAsync(this, config.appWidgetId)
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, config.appWidgetId),
        )
        finish()
    }
}

private enum class Step { KIND, ALBUM, PERMISSION }

@Composable
private fun ConfigScreen(
    appWidgetId: Int,
    onCancel: () -> Unit,
    onDone: (WidgetConfig) -> Unit,
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(Step.KIND) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        step = if (granted.values.any { it }) Step.ALBUM else Step.PERMISSION
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            persistReadPermission(context, uri)
            onDone(WidgetConfig(appWidgetId, WidgetKind.SINGLE, imageUri = uri.toString()))
        }
    }

    when (step) {
        Step.KIND -> KindChooser(
            onAlbum = {
                if (MediaRepository.hasPhotoPermission(context)) {
                    step = Step.ALBUM
                } else {
                    permissionLauncher.launch(MediaRepository.requiredPermissions())
                }
            },
            onPhoto = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onShuffle = { onDone(WidgetConfig(appWidgetId, WidgetKind.SHUFFLE)) },
            onCancel = onCancel,
        )

        Step.ALBUM -> AlbumChooser(
            onPick = { bucket ->
                onDone(
                    WidgetConfig(
                        appWidgetId = appWidgetId,
                        kind = WidgetKind.ALBUM,
                        bucketId = bucket.id,
                        bucketName = bucket.name,
                    ),
                )
            },
            onCancel = onCancel,
        )

        Step.PERMISSION -> PermissionScreen(
            onRetry = { permissionLauncher.launch(MediaRepository.requiredPermissions()) },
            onCancel = onCancel,
        )
    }
}

@Composable
private fun KindChooser(
    onAlbum: () -> Unit,
    onPhoto: () -> Unit,
    onShuffle: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = stringResource(R.string.config_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))
        OptionRow(stringResource(R.string.widget_type_album), onAlbum)
        OptionRow(stringResource(R.string.widget_type_photo), onPhoto)
        OptionRow(stringResource(R.string.widget_type_shuffle), onShuffle)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun OptionRow(label: String, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider()
    }
}

@Composable
private fun AlbumChooser(
    onPick: (MediaRepository.Bucket) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val buckets by produceState(initialValue = emptyList<MediaRepository.Bucket>()) {
        value = withContext(Dispatchers.IO) { MediaRepository.queryBuckets(context) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.config_album_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(24.dp),
        )
        if (buckets.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.config_saving))
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(buckets, key = { it.id }) { bucket ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(bucket) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BucketThumbnail(bucket.coverUri)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bucket.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = bucket.count.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.cancel))
        }
    }
}

@Composable
private fun BucketThumbnail(uri: Uri) {
    val context = LocalContext.current
    val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.roundToPx() }
    val bitmap by produceState<Bitmap?>(initialValue = null, uri, sizePx) {
        value = ThumbnailLoader.load(context, uri, sizePx, sizePx)
    }
    Surface(
        modifier = Modifier.size(56.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PermissionScreen(onRetry: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.permission_message),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.permission_grant)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

/** 照片选择器给的是一次性读授权，要长期给小部件用就得持久化。 */
private fun persistReadPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}
