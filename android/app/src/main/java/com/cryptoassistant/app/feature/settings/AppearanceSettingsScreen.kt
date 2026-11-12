package com.cryptoassistant.app.feature.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.AccentPalette
import com.cryptoassistant.app.data.AppSettings
import com.cryptoassistant.app.data.ThemeMode
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.components.SectionTitle
import com.cryptoassistant.app.ui.theme.Spacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(
    settings: AppSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onAccentPaletteChange: (AccentPalette) -> Unit,
    onAmoledDarkChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // 动态配色只在 Android 12+ 可用；旧系统上的残留值不能让它压住品牌色。
    val dynamicActive =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && settings.dynamicColor

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenPadding),
    ) {
        SectionTitle(stringResource(R.string.appearance_section_theme))

        AppCard {
            Text(
                text = stringResource(R.string.appearance_theme_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            // 英文标签更长，允许换行，避免横向挤压成 “Viol et”。
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ThemeChip(
                    label = stringResource(R.string.theme_system),
                    selected = settings.themeMode == ThemeMode.SYSTEM,
                    onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
                )
                ThemeChip(
                    label = stringResource(R.string.theme_light),
                    selected = settings.themeMode == ThemeMode.LIGHT,
                    onClick = { onThemeModeChange(ThemeMode.LIGHT) },
                )
                ThemeChip(
                    label = stringResource(R.string.theme_dark),
                    selected = settings.themeMode == ThemeMode.DARK,
                    onClick = { onThemeModeChange(ThemeMode.DARK) },
                )
            }
            // 系统不支持动态取色时整行不出现，不留一个永远不可用的开关。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Spacer(Modifier.height(Spacing.md))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.appearance_dynamic_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.appearance_dynamic_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.dynamicColor,
                        onCheckedChange = onDynamicColorChange,
                    )
                }
            }
        }

        SectionTitle(stringResource(R.string.appearance_section_accent))
        AppCard {
            Text(
                text = stringResource(R.string.appearance_accent_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                AccentPalette.entries.forEach { palette ->
                    PaletteChip(
                        palette = palette,
                        selected = settings.accentPalette == palette,
                        enabled = !dynamicActive,
                        onClick = { onAccentPaletteChange(palette) },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(
                    if (dynamicActive) {
                        R.string.appearance_accent_dynamic_hint
                    } else {
                        R.string.appearance_accent_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 纯黑只影响深色主题，且与动态配色互斥；浅色主题下整组不出现。
        if (darkTheme && !dynamicActive) {
            SectionTitle(stringResource(R.string.appearance_section_dark))
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.appearance_amoled_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.appearance_amoled_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.amoledDark,
                        onCheckedChange = onAmoledDarkChange,
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.section))
    }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else {
            null
        },
    )
}

@Composable
private fun PaletteChip(
    palette: AccentPalette,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(palettePreviewColor(palette), CircleShape),
            )
        },
        label = { Text(stringResource(palette.labelRes)) },
    )
}

private fun palettePreviewColor(palette: AccentPalette): Color = when (palette) {
    AccentPalette.INDIGO -> Color(0xFF6D7EF0)
    AccentPalette.TEAL -> Color(0xFF2FC7A5)
    AccentPalette.AMBER -> Color(0xFFC98A1B)
    AccentPalette.VIOLET -> Color(0xFF8B6FE0)
}
