package com.cryptoassistant.app.feature.common

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cryptoassistant.app.feature.security.SecuritySession

class OverlayPermissionState(
    val granted: Boolean,
    val request: () -> Unit,
)

/**
 * 悬浮窗权限状态：回到前台时重新检测，不把“曾经授权”当作“当前仍有效”。
 */
@Composable
fun rememberOverlayPermission(): OverlayPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        SecuritySession.endExternalFlow()
        granted = Settings.canDrawOverlays(context)
    }

    LifecycleResumeEffect(Unit) {
        granted = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }

    return OverlayPermissionState(granted = granted) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri(),
        )
        // 系统授权页停留时间不计入应用锁的离开时长。
        SecuritySession.beginExternalFlow()
        runCatching { launcher.launch(intent) }
            .onFailure { SecuritySession.endExternalFlow() }
    }
}
