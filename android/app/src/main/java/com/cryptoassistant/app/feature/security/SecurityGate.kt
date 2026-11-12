package com.cryptoassistant.app.feature.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.AppSettings
import com.cryptoassistant.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * 应用锁门禁：只包 `MainActivity` 的内容。
 *
 * <p>设置还没加载时先渲染空白，避免先闪一帧业务内容；锁定后只显示 [LockScreen]，
 * 密钥、联系人、日志与设置都不进入组合。悬浮球、文件入口和球面板不经过这里。</p>
 */
@Composable
fun SecurityGate(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val state by SecuritySession.state.collectAsStateWithLifecycle()
    LaunchedEffect(
        settings.securityLockEnabled,
        settings.appLockEnabled,
        settings.lockTimeoutMinutes,
    ) {
        SecuritySession.configure(
            enabled = settings.securityLockEnabled && settings.appLockEnabled,
            timeoutMinutes = settings.lockTimeoutMinutes,
        )
    }

    if (!state.configured) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {}
        return
    }

    if (state.locked) {
        LockScreen()
    } else {
        content()
    }
}

/** 锁屏只展示解锁入口，不显示任何密钥、联系人、日志或设置内容。 */
@Composable
private fun LockScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val hostUnavailableText = stringResource(R.string.security_host_unavailable)
    val verifyRequiredText = stringResource(R.string.security_verify_required)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = stringResource(R.string.security_lock_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.security_lock_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            message?.let { text ->
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Spacing.xl))
            Button(
                onClick = {
                    if (busy) return@Button
                    val activity = context.findFragmentActivity()
                    if (activity == null) {
                        message = hostUnavailableText
                        return@Button
                    }
                    scope.launch {
                        busy = true
                        message = null
                        when (val result = BiometricAuth.authenticate(activity, SecurityAction.UNLOCK_APP)) {
                            BiometricAuthResult.Success -> SecuritySession.unlock()

                            BiometricAuthResult.Cancelled ->
                                message = verifyRequiredText

                            is BiometricAuthResult.Unavailable -> message = result.message

                            is BiometricAuthResult.Failed -> message = result.message
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (busy) R.string.security_verifying else R.string.security_unlock,
                    ),
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            TextButton(
                onClick = { context.findFragmentActivity()?.finish() },
            ) {
                Text(stringResource(R.string.security_exit))
            }
        }
    }
}
