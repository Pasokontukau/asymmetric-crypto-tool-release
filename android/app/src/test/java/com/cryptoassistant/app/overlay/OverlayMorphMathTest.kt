package com.cryptoassistant.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayMorphMathTest {

    private val ball = MorphRect(left = 100, top = 800, right = 156, bottom = 856)

    @Test
    fun `lerp returns the start and end rects at the endpoints`() {
        val end = MorphRect(0, 0, 400, 600)
        assertEquals(ball, lerpMorphRect(ball, end, 0f))
        assertEquals(end, lerpMorphRect(ball, end, 1f))
    }

    @Test
    fun `lerp interpolates every edge at the midpoint`() {
        val end = MorphRect(0, 0, 400, 600)
        assertEquals(MorphRect(50, 400, 278, 728), lerpMorphRect(ball, end, 0.5f))
    }

    @Test
    fun `lerp clamps fractions outside zero and one`() {
        val end = MorphRect(0, 0, 400, 600)
        assertEquals(ball, lerpMorphRect(ball, end, -1f))
        assertEquals(end, lerpMorphRect(ball, end, 2f))
    }

    @Test
    fun `menu prefers the space below the ball`() {
        val target = menuTargetRect(
            ball = ball,
            menuWidth = 700,
            menuHeight = 600,
            screenWidth = 1080,
            screenHeight = 2400,
            safeTop = 80,
            safeBottom = 100,
            gap = 12,
            margin = 12,
        )
        assertEquals(MorphRect(12, 868, 712, 1468), target)
    }

    @Test
    fun `menu flips above the ball when below does not fit`() {
        val lowBall = MorphRect(500, 2000, 556, 2056)
        val target = menuTargetRect(
            ball = lowBall,
            menuWidth = 700,
            menuHeight = 600,
            screenWidth = 1080,
            screenHeight = 2400,
            safeTop = 80,
            safeBottom = 100,
            gap = 12,
            margin = 12,
        )
        assertEquals(178, target.left)
        assertEquals(1388, target.top)
        assertEquals(1988, target.bottom)
    }

    @Test
    fun `menu clamps to the screen edges`() {
        val rightBall = MorphRect(1000, 800, 1056, 856)
        val right = menuTargetRect(
            ball = rightBall,
            menuWidth = 700,
            menuHeight = 600,
            screenWidth = 1080,
            screenHeight = 2400,
            safeTop = 80,
            safeBottom = 100,
            gap = 12,
            margin = 12,
        )
        assertEquals(368, right.left)

        val left = menuTargetRect(
            ball = ball,
            menuWidth = 700,
            menuHeight = 600,
            screenWidth = 1080,
            screenHeight = 2400,
            safeTop = 80,
            safeBottom = 100,
            gap = 12,
            margin = 12,
        )
        assertEquals(12, left.left)
    }

    @Test
    fun `menu never overlaps the status bar when it does not fit at all`() {
        val target = menuTargetRect(
            ball = MorphRect(500, 50, 556, 106),
            menuWidth = 700,
            menuHeight = 2300,
            screenWidth = 1080,
            screenHeight = 2400,
            safeTop = 80,
            safeBottom = 100,
            gap = 12,
            margin = 12,
        )
        assertEquals(92, target.top)
    }
}
