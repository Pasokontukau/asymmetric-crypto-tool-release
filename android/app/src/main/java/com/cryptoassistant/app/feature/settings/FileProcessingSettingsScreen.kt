package com.cryptoassistant.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.AppSettings
import com.cryptoassistant.app.feature.files.ArchiveVolumeSize
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.components.SectionTitle
import com.cryptoassistant.app.ui.theme.Spacing

/**
 * 文件处理设置：多文件默认打包 + “每卷上限”五档，不做自由输入。
 *
 * <p>打包开关只作为新选择的默认值，文件页仍可逐次关闭。上限按加密后的
 * 单个 `.aegix` 文件大小计算；平台能否发送取决于目标应用，
 * 因此页面只给建议，不承诺微信/QQ 一定接收 500 MB 以上文件。</p>
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileProcessingSettingsScreen(
    settings: AppSettings,
    onArchiveVolumeSizeChange: (ArchiveVolumeSize) -> Unit,
    onPackageMultipleFilesChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenPadding),
    ) {
        SectionTitle(stringResource(R.string.settings_file_processing_section))

        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_package_multiple_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_package_multiple_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.packageMultipleFiles,
                    onCheckedChange = onPackageMultipleFilesChange,
                )
            }
            Spacer(Modifier.height(Spacing.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.settings_file_processing_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ArchiveVolumeSize.entries.forEach { size ->
                    FilterChip(
                        selected = settings.archiveVolumeSize == size,
                        onClick = { onArchiveVolumeSizeChange(size) },
                        label = { Text(stringResource(size.labelRes)) },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.settings_file_processing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.section))
    }
}
