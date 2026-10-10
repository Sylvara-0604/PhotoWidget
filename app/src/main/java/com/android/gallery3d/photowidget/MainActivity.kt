package com.android.gallery3d.photowidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.android.gallery3d.photowidget.data.MediaRepository
import com.android.gallery3d.photowidget.ui.theme.PhotoWidgetTheme
import com.android.gallery3d.photowidget.widget.WidgetConfigStore

/**
 * 应用主界面：申请照片权限 + 引导添加小部件。
 * 小部件本身无 UI 无法申请权限，所以权限要在 App 里先拿到（旧版靠 App 启动时拿）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotoWidgetTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen()
                }
            }
        }
    }
}

/** 照片权限状态：没授权 / 只授权了"选中的照片" / 完全授权。 */
private enum class AccessState { NONE, PARTIAL, FULL }

private fun accessState(context: android.content.Context): AccessState = when {
    !MediaRepository.hasPhotoPermission(context) -> AccessState.NONE
    MediaRepository.isPartialAccess(context) -> AccessState.PARTIAL
    else -> AccessState.FULL
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var access by remember { mutableStateOf(accessState(context)) }
    // 小部件数量取"桌面上真实存在的"（会顺带清理孤儿配置），只在个位数/几十条的规模上算，很便宜
    var configured by remember { mutableStateOf(WidgetConfigStore.liveConfiguredIds(context).size) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { access = accessState(context) }

    // 关键修复：从系统设置里授予权限后回到 App，也必须重新核对（此前只在首帧 + 授权回调里查一次，
    // 所以"明明已授权却仍显示需要权限"）。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = accessState(context)
                configured = WidgetConfigStore.liveConfiguredIds(context).size
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.main_placeholder),
            style = MaterialTheme.typography.bodyMedium,
        )
        when (access) {
            AccessState.NONE -> {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.permission_message),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { launcher.launch(MediaRepository.requiredPermissions()) }) {
                    Text(stringResource(R.string.permission_grant))
                }
            }

            AccessState.PARTIAL -> {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.permission_partial),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { launcher.launch(MediaRepository.requiredPermissions()) }) {
                    Text(stringResource(R.string.permission_grant))
                }
            }

            AccessState.FULL -> {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.permission_granted),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (configured > 0) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = pluralStringResource(R.plurals.configured_widgets, configured, configured),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(16.dp))
        // 入口：跳到系统"应用信息"页，用户可在这里重新授权或撤销
        TextButton(onClick = { openAppSettings(context) }) {
            Text(stringResource(R.string.permission_manage))
        }
    }
}

private fun openAppSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
