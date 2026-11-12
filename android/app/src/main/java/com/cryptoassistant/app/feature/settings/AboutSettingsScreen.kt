package com.cryptoassistant.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cryptoassistant.app.BuildConfig
import com.cryptoassistant.app.R
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.components.KeyValueRow
import com.cryptoassistant.app.ui.components.SectionTitle
import com.cryptoassistant.app.ui.theme.Spacing

/**
 * 关于页只展示“构建是什么、许可证是什么、隐私边界是什么”。
 *
 * 构建 UUID 与编译时间来自 BuildConfig：每次 Gradle 构建生成一次，只用于问题反馈时
 * 区分安装包；不参与任何密钥、协议或日志内容。
 */
@Composable
fun AboutSettingsScreen(modifier: Modifier = Modifier) {
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showGplDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val gplLoadFailed = stringResource(R.string.about_gpl_load_failed)
    val gplText = remember {
        runCatching {
            context.resources.openRawResource(R.raw.gpl_3_0)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrElse { gplLoadFailed }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenPadding),
    ) {
        SectionTitle(stringResource(R.string.about_section_version))

        AppCard {
            KeyValueRow(
                label = stringResource(R.string.about_label_app_version),
                value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
            KeyValueRow(
                stringResource(R.string.about_label_build_type),
                BuildConfig.BUILD_TYPE,
            )
            KeyValueRow(
                stringResource(R.string.about_label_build_stage),
                stringResource(R.string.about_build_stage_value),
            )
            KeyValueRow(
                stringResource(R.string.about_label_application_id),
                BuildConfig.APPLICATION_ID,
            )
            KeyValueRow(
                stringResource(R.string.about_label_build_uuid),
                BuildConfig.BUILD_UUID,
            )
            KeyValueRow(
                stringResource(R.string.about_label_build_time),
                BuildConfig.BUILD_TIME,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.about_privacy_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionTitle(stringResource(R.string.about_section_license))

        AppCard {
            Text(
                text = stringResource(R.string.about_license_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
            TextButton(onClick = { showGplDialog = true }) {
                Text(stringResource(R.string.about_view_gpl))
            }
            TextButton(onClick = { showLicensesDialog = true }) {
                Text(stringResource(R.string.about_view_licenses))
            }
        }

        Spacer(Modifier.height(Spacing.section))
    }

    if (showGplDialog) {
        AlertDialog(
            onDismissRequest = { showGplDialog = false },
            title = { Text(stringResource(R.string.about_gpl_dialog_title)) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = gplText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showGplDialog = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text(stringResource(R.string.about_licenses_dialog_title)) },
            text = {
                Column {
                    LicenseLine(
                        stringResource(R.string.about_this_app),
                        "GPL-3.0-only",
                    )
                    LicenseLine("AndroidX / Jetpack Compose", "Apache-2.0")
                    LicenseLine("Material 3 / Material Icons", "Apache-2.0")
                    LicenseLine("Kotlin & Kotlin Coroutines", "Apache-2.0")
                    LicenseLine("BouncyCastle bcprov", "MIT-style")
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(R.string.about_license_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
}

@Composable
private fun LicenseLine(name: String, license: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = license,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
