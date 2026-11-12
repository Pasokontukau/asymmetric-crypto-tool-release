package com.cryptoassistant.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.cryptoassistant.app.data.AccentPalette
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentPaletteTest {

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance()
        val b = second.luminance()
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    @Test
    fun `default palette keeps existing schemes unchanged`() {
        assertEquals(LightColorScheme, colorSchemeFor(AccentPalette.INDIGO, false, false))
        assertEquals(DarkColorScheme, colorSchemeFor(AccentPalette.INDIGO, true, false))
    }

    @Test
    fun `amoled only changes dark surfaces`() {
        val light = colorSchemeFor(AccentPalette.INDIGO, false, true)
        assertEquals(LightColorScheme, light)

        val dark = colorSchemeFor(AccentPalette.INDIGO, true, true)
        assertEquals(Color.Black, dark.background)
        assertEquals(Color.Black, dark.surface)
        assertEquals(Color.Black, dark.surfaceContainerLowest)
        assertFalse(dark.surfaceContainerLow == Color.Black)
    }

    @Test
    fun `all palettes keep button text contrast readable`() {
        AccentPalette.entries.forEach { palette ->
            val light = colorSchemeFor(palette, false, false)
            val dark = colorSchemeFor(palette, true, false)
            assertTrue(
                "$palette light primary contrast",
                contrast(light.primary, light.onPrimary) >= 4.5,
            )
            assertTrue(
                "$palette dark primary contrast",
                contrast(dark.primary, dark.onPrimary) >= 4.5,
            )
            assertTrue(
                "$palette light container contrast",
                contrast(light.primaryContainer, light.onPrimaryContainer) >= 4.5,
            )
            assertTrue(
                "$palette dark container contrast",
                contrast(dark.primaryContainer, dark.onPrimaryContainer) >= 4.5,
            )
        }
    }

    @Test
    fun `palette options are the four reviewed brands`() {
        assertEquals(
            listOf(
                AccentPalette.INDIGO,
                AccentPalette.TEAL,
                AccentPalette.AMBER,
                AccentPalette.VIOLET,
            ),
            AccentPalette.entries.toList(),
        )
    }
}
