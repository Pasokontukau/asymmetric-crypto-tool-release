package com.cryptoassistant.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.cryptoassistant.app.data.AccentPalette

// 品牌色：深海军蓝底 + 靛蓝主色 + 青绿安全色
internal val Navy950 = Color(0xFF080C16)
internal val Navy900 = Color(0xFF0B1120)
internal val Navy800 = Color(0xFF131C31)
internal val Navy700 = Color(0xFF1B2742)
internal val Navy600 = Color(0xFF273757)

internal val Indigo300 = Color(0xFF9AA8FF)
internal val Indigo400 = Color(0xFF8B9CFF)
internal val Indigo500 = Color(0xFF6D7EF0)
internal val Indigo700 = Color(0xFF4457C9)

internal val Teal300 = Color(0xFF6FE3C4)
internal val Teal500 = Color(0xFF2FC7A5)
internal val Teal700 = Color(0xFF12866C)

internal val Cloud100 = Color(0xFFF6F7FC)
internal val Cloud200 = Color(0xFFEEF1FA)
internal val Cloud300 = Color(0xFFE3E8F5)
internal val Cloud500 = Color(0xFFC3CADD)

internal val ErrorDark = Color(0xFFFF9B9B)
internal val ErrorLight = Color(0xFFB3261E)
internal val WarningDark = Color(0xFFF4C67A)
internal val WarningLight = Color(0xFF8A5A00)

internal val DarkColorScheme = darkColorScheme(
    primary = Indigo400,
    onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF232F63),
    onPrimaryContainer = Color(0xFFDDE2FF),
    secondary = Color(0xFFAEB8D6),
    onSecondary = Color(0xFF121A2B),
    secondaryContainer = Navy700,
    onSecondaryContainer = Color(0xFFD7DEF2),
    tertiary = Teal300,
    onTertiary = Color(0xFF052018),
    tertiaryContainer = Color(0xFF114234),
    onTertiaryContainer = Color(0xFFB9F3E1),
    background = Navy950,
    onBackground = Color(0xFFE8ECF7),
    surface = Navy900,
    onSurface = Color(0xFFE8ECF7),
    surfaceVariant = Navy700,
    onSurfaceVariant = Color(0xFFB4BFDA),
    surfaceContainerLowest = Color(0xFF060A13),
    surfaceContainerLow = Color(0xFF0E1626),
    surfaceContainer = Navy800,
    surfaceContainerHigh = Color(0xFF18233A),
    surfaceContainerHighest = Color(0xFF1F2C46),
    outline = Color(0xFF3A4867),
    outlineVariant = Color(0xFF273350),
    error = ErrorDark,
    onError = Color(0xFF2B0808),
    errorContainer = Color(0xFF52201F),
    onErrorContainer = Color(0xFFFFDAD7),
    inverseSurface = Color(0xFFE8ECF7),
    inverseOnSurface = Color(0xFF141B2B),
    inversePrimary = Indigo700,
    scrim = Color(0xFF000000),
)

internal val LightColorScheme = lightColorScheme(
    primary = Indigo700,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE2FF),
    onPrimaryContainer = Color(0xFF0B1233),
    secondary = Color(0xFF5A6480),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E8F5),
    onSecondaryContainer = Color(0xFF1B2337),
    tertiary = Teal700,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8F3E6),
    onTertiaryContainer = Color(0xFF00251C),
    background = Cloud100,
    onBackground = Color(0xFF141B2B),
    surface = Color.White,
    onSurface = Color(0xFF141B2B),
    surfaceVariant = Cloud300,
    onSurfaceVariant = Color(0xFF4A5470),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FD),
    surfaceContainer = Cloud200,
    surfaceContainerHigh = Color(0xFFE8ECF7),
    surfaceContainerHighest = Color(0xFFDFE5F2),
    outline = Cloud500,
    outlineVariant = Color(0xFFDCE2EF),
    error = ErrorLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF1A2233),
    inverseOnSurface = Color(0xFFF0F2FA),
    inversePrimary = Indigo400,
    scrim = Color(0xFF000000),
)

/** 品牌扩展语义色（success / warning 不在 Material 3 标准色槽中）。 */
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
)

internal val DarkExtendedColors = ExtendedColors(
    success = Teal300,
    onSuccess = Color(0xFF04241B),
    successContainer = Color(0xFF103C30),
    onSuccessContainer = Color(0xFFB9F3E1),
    warning = WarningDark,
)

internal val LightExtendedColors = ExtendedColors(
    success = Teal700,
    onSuccess = Color.White,
    successContainer = Color(0xFFC8F3E6),
    onSuccessContainer = Color(0xFF00251C),
    warning = WarningLight,
)

val LocalExtendedColors = staticCompositionLocalOf { DarkExtendedColors }

/** 品牌色只替换 primary 角色；secondary 保持中性，success/warning 语义色不变。 */
private data class AccentRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val tertiary: Color = Color.Unspecified,
    val onTertiary: Color = Color.Unspecified,
    val tertiaryContainer: Color = Color.Unspecified,
    val onTertiaryContainer: Color = Color.Unspecified,
)

private val TealLightRoles = AccentRoles(
    primary = Teal700,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F3E6),
    onPrimaryContainer = Color(0xFF00251C),
    inversePrimary = Teal300,
    tertiary = Indigo700,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDE2FF),
    onTertiaryContainer = Color(0xFF0B1233),
)

private val TealDarkRoles = AccentRoles(
    primary = Teal300,
    onPrimary = Color(0xFF052018),
    primaryContainer = Color(0xFF114234),
    onPrimaryContainer = Color(0xFFB9F3E1),
    inversePrimary = Teal700,
    tertiary = Indigo400,
    onTertiary = Color(0xFF0B1020),
    tertiaryContainer = Color(0xFF232F63),
    onTertiaryContainer = Color(0xFFDDE2FF),
)

private val AmberLightRoles = AccentRoles(
    primary = Color(0xFF8A5A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE8B0),
    onPrimaryContainer = Color(0xFF2B1A00),
    inversePrimary = Color(0xFFF4C67A),
)

private val AmberDarkRoles = AccentRoles(
    primary = Color(0xFFF4C67A),
    onPrimary = Color(0xFF2B1A00),
    primaryContainer = Color(0xFF5B3D00),
    onPrimaryContainer = Color(0xFFFFE8B0),
    inversePrimary = Color(0xFF8A5A00),
)

private val VioletLightRoles = AccentRoles(
    primary = Color(0xFF6A4BC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DEFF),
    onPrimaryContainer = Color(0xFF21005D),
    inversePrimary = Color(0xFFC9B6FF),
)

private val VioletDarkRoles = AccentRoles(
    primary = Color(0xFFC9B6FF),
    onPrimary = Color(0xFF2B0A66),
    primaryContainer = Color(0xFF4A2C99),
    onPrimaryContainer = Color(0xFFE9DEFF),
    inversePrimary = Color(0xFF6A4BC4),
)

/**
 * 品牌色 + 深色纯黑合成的最终配色。
 *
 * <p>动态配色开启时调用方不会走到这里；INDIGO 直接返回现有方案，保证默认视觉不变。</p>
 */
internal fun colorSchemeFor(
    palette: AccentPalette,
    darkTheme: Boolean,
    amoledDark: Boolean,
): ColorScheme {
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    val roles = when (palette) {
        AccentPalette.INDIGO -> null
        AccentPalette.TEAL -> if (darkTheme) TealDarkRoles else TealLightRoles
        AccentPalette.AMBER -> if (darkTheme) AmberDarkRoles else AmberLightRoles
        AccentPalette.VIOLET -> if (darkTheme) VioletDarkRoles else VioletLightRoles
    }
    val paletteScheme = if (roles == null) {
        base
    } else {
        base.copy(
            primary = roles.primary,
            onPrimary = roles.onPrimary,
            primaryContainer = roles.primaryContainer,
            onPrimaryContainer = roles.onPrimaryContainer,
            inversePrimary = roles.inversePrimary,
            tertiary = roles.tertiary.takeIf { it != Color.Unspecified } ?: base.tertiary,
            onTertiary = roles.onTertiary.takeIf { it != Color.Unspecified } ?: base.onTertiary,
            tertiaryContainer = roles.tertiaryContainer.takeIf { it != Color.Unspecified }
                ?: base.tertiaryContainer,
            onTertiaryContainer = roles.onTertiaryContainer.takeIf { it != Color.Unspecified }
                ?: base.onTertiaryContainer,
        )
    }
    if (!darkTheme || !amoledDark) return paletteScheme
    return paletteScheme.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF050505),
        surfaceContainer = Color(0xFF0A0A0A),
        surfaceContainerHigh = Color(0xFF101010),
        surfaceContainerHighest = Color(0xFF161616),
    )
}
