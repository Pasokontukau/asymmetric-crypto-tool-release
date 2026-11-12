package com.cryptoassistant.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptoassistant.app.R
import com.cryptoassistant.app.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date

/** 对话框与说明页共用的「标题 + 正文」条目。 */
@Composable
internal fun PrivacyPoint(title: String, body: String) {
    Column(modifier = Modifier.padding(bottom = Spacing.md)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 无障碍服务的显著披露 + 独立同意弹窗（任务书 §2.4）。
 *
 * 硬性要求：同意必须是**未默认勾选**的独立操作，勾选后才能点「我理解并同意，前往开启」；
 * 本地只记录同意时间与披露文案版本，不记录任何输入内容。调用方在 [onConfirm] 里
 * 落盘同意记录并跳转系统无障碍设置。
 *
 * 每次打开都从未勾选开始（即便此前同意过），披露内容更新后由调用方通过
 * 文案版本判断是否需要再次展示。
 */
@Composable
internal fun AccessibilityDisclosureDialog(
    consentAt: Long?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var agreed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.a11y_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                PrivacyPoint(
                    stringResource(R.string.a11y_purpose_title),
                    stringResource(R.string.a11y_purpose_body),
                )
                PrivacyPoint(
                    stringResource(R.string.a11y_scope_title),
                    stringResource(R.string.a11y_scope_body),
                )
                PrivacyPoint(
                    stringResource(R.string.a11y_consent_title),
                    stringResource(R.string.a11y_consent_body),
                )
                PrivacyPoint(
                    stringResource(R.string.a11y_network_title),
                    stringResource(R.string.a11y_network_body),
                )
                consentAt?.let { at ->
                    // 用可观察的 Configuration locale，避免 Compose 在切换语言后继续用旧 Locale。
                    val locale = LocalConfiguration.current.locales[0]
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", locale).format(Date(at))
                    Text(
                        text = stringResource(R.string.a11y_consented_at, time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.md))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { agreed = !agreed }
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = agreed,
                        onCheckedChange = { agreed = it },
                    )
                    Text(
                        text = stringResource(R.string.a11y_agree_checkbox),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = agreed,
                onClick = onConfirm,
            ) { Text(stringResource(R.string.a11y_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

