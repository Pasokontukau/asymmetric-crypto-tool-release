package com.cryptoassistant.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {

    private val ball = 168 // 56dp @ 3x
    private val portrait = ScreenBounds(width = 1080, height = 2340, safeTop = 80, safeBottom = 90)

    @Test
    fun `clamp keeps ball inside screen`() {
        val far = OverlayGeometry.clamp(-500, -500, ball, portrait)
        assertEquals(0, far.x)
        assertEquals(80, far.y)

        val beyond = OverlayGeometry.clamp(5000, 5000, ball, portrait)
        assertEquals(1080 - ball, beyond.x)
        assertEquals(2340 - 90 - ball, beyond.y)
    }

    @Test
    fun `snap chooses nearest horizontal edge`() {
        val left = OverlayGeometry.snap(40, 500, ball, portrait, margin = 24)
        assertEquals(24, left.x)

        val right = OverlayGeometry.snap(900, 500, ball, portrait, margin = 24)
        assertEquals(1080 - ball - 24, right.x)
    }

    @Test
    fun `snap keeps vertical position within safe area`() {
        val snapped = OverlayGeometry.snap(100, 40, ball, portrait, margin = 24)
        assertEquals(24, snapped.x)
        assertEquals(80, snapped.y)
    }

    @Test
    fun `fraction roundtrip survives rotation`() {
        val position = OverlayGeometry.snap(900, 1200, ball, portrait, margin = 24)
        val (fx, fy) = OverlayGeometry.toFraction(position, ball, portrait)

        val landscape = ScreenBounds(width = 2340, height = 1080, safeTop = 80, safeBottom = 60)
        val restored = OverlayGeometry.fromFraction(fx, fy, ball, landscape)

        assertTrue(restored.x in 0..(landscape.width - ball))
        assertTrue(restored.y in landscape.safeTop..(landscape.height - landscape.safeBottom - ball))
        assertTrue(restored.x >= landscape.width / 2 - ball)
    }

    @Test
    fun `restored position is re-clamped after rotation`() {
        val (fx, fy) = OverlayGeometry.toFraction(
            BallPosition(x = 0, y = 2200),
            ball,
            portrait,
        )
        val shortScreen = ScreenBounds(width = 720, height = 1280, safeTop = 60, safeBottom = 48)
        val restored = OverlayGeometry.fromFraction(fx, fy, ball, shortScreen)
        assertEquals(0, restored.x)
        assertTrue(restored.y <= shortScreen.height - shortScreen.safeBottom - ball)
    }

    @Test
    fun `all ball size presets stay inside bounds and roundtrip`() {
        val landscape = ScreenBounds(width = 2340, height = 1080, safeTop = 80, safeBottom = 60)
        // 48 / 56 / 64 dp @ 3x
        listOf(144, 168, 192).forEach { size ->
            val snapped = OverlayGeometry.snap(900, 900, size, portrait, margin = 24)
            assertTrue(snapped.x in 0..(portrait.width - size))
            assertTrue(snapped.y in portrait.safeTop..(portrait.height - portrait.safeBottom - size))

            val (fx, fy) = OverlayGeometry.toFraction(snapped, size, portrait)
            val restored = OverlayGeometry.fromFraction(fx, fy, size, landscape)
            assertTrue(restored.x in 0..(landscape.width - size))
            assertTrue(restored.y in landscape.safeTop..(landscape.height - landscape.safeBottom - size))
        }
    }

    @Test
    fun `ball larger than screen does not produce negative bounds`() {
        val tiny = ScreenBounds(width = 100, height = 100, safeTop = 0, safeBottom = 0)
        val clamped = OverlayGeometry.clamp(50, 50, ball, tiny)
        assertEquals(0, clamped.x)
        assertEquals(0, clamped.y)
    }
}
