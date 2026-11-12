package com.cryptoassistant.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.keys.IdentityKey
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import com.cryptoassistant.app.ui.components.ActionTile
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.components.SettingsNavRow
import com.cryptoassistant.app.ui.components.StatusRow
import com.cryptoassistant.app.ui.components.StatusTone
import com.cryptoassistant.app.ui.theme.Spacing

/**
 * 首页只保留「本机密钥状态」与「文件加解密入口」，其余入口收进底部导航或设置二级菜单，
 * 避免同一功能在首页、悬浮球页、设置里各出现一次。
 */
@Composable
fun HomeScreen(
    identity: IdentityKey?,
    onOpenKeys: () -> Unit,
    onOpenEncryptFiles: () -> Unit,
    onOpenDecryptFiles: () -> Unit,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.screenPadding,
            end = Spacing.screenPadding,
            bottom = Spacing.xxl,
        ),
    ) {
        item {
            Spacer(Modifier.height(Spacing.sm))
            AppCard(
                onClick = onOpenKeys,
                onClickLabel = stringResource(R.string.home_open_keys_action),
            ) {
                StatusRow(
                    icon = Icons.Outlined.VpnKey,
                    title = stringResource(R.string.home_key_title),
                    subtitle = identity?.let {
                        stringResource(
                            R.string.home_key_fingerprint,
                            PrivateKeyBackup.formatFingerprint(it.fingerprint),
                        )
                    } ?: stringResource(R.string.home_key_missing),
                    tone = if (identity != null) StatusTone.SUCCESS else StatusTone.WARNING,
                )
            }
        }

        item {
            Spacer(Modifier.height(Spacing.lg))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                ActionTile(
                    icon = Icons.Outlined.Folder,
                    title = stringResource(R.string.nav_title_encrypt_files),
                    description = stringResource(R.string.home_encrypt_desc),
                    onClick = onOpenEncryptFiles,
                    modifier = Modifier.weight(1f),
                )
                ActionTile(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.nav_title_decrypt_files),
                    description = stringResource(R.string.home_decrypt_desc),
                    onClick = onOpenDecryptFiles,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Spacer(Modifier.height(Spacing.lg))
            AppCard {
                SettingsNavRow(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = stringResource(R.string.nav_title_guide),
                    subtitle = stringResource(R.string.home_guide_desc),
                    onClick = onOpenGuide,
                )
            }
        }

        item {
            Spacer(Modifier.height(Spacing.section))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.home_offline_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
