package com.cryptoassistant.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BallAppearanceTest {

    @Test
    fun `defaults match the existing 56dp solid ball`() {
        assertEquals(BallSize.STANDARD, BallSize.fromStored(null))
        assertEquals(BallSize.STANDARD, BallSize.fromStored("unknown"))
        assertEquals(56, BallSize.STANDARD.diameterDp)
        assertEquals(24, BallSize.STANDARD.iconDp)
        assertEquals(BallOpacity.SOLID, BallOpacity.fromStored(null))
        assertEquals(BallOpacity.SOLID, BallOpacity.fromStored("unknown"))
        assertEquals(1.0f, BallOpacity.SOLID.alpha)
    }

    @Test
    fun `size presets are ordered and keep touch target`() {
        assertTrue(BallSize.SMALL.diameterDp >= 48)
        assertTrue(BallSize.STANDARD.diameterDp > BallSize.SMALL.diameterDp)
        assertTrue(BallSize.LARGE.diameterDp > BallSize.STANDARD.diameterDp)
        BallSize.entries.forEach { size ->
            assertTrue(size.iconDp < size.diameterDp)
        }
    }

    @Test
    fun `opacity presets stay readable`() {
        BallOpacity.entries.forEach { opacity ->
            assertTrue(opacity.alpha in 0.8f..1.0f)
        }
    }

    @Test
    fun `stored names roundtrip`() {
        BallSize.entries.forEach { size ->
            assertEquals(size, BallSize.fromStored(size.name))
        }
        BallOpacity.entries.forEach { opacity ->
            assertEquals(opacity, BallOpacity.fromStored(opacity.name))
        }
    }
}
