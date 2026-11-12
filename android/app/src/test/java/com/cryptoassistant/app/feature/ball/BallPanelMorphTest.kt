package com.cryptoassistant.app.feature.ball

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class BallPanelMorphTest {

    private val ball = Rect(left = 100f, top = 800f, right = 156f, bottom = 856f)

    @Test
    fun `lerp returns the endpoints unchanged`() {
        val card = Rect(16f, 400f, 344f, 1200f)
        assertEquals(ball, lerpRect(ball, card, 0f))
        assertEquals(card, lerpRect(ball, card, 1f))
    }

    @Test
    fun `lerp moves every edge together`() {
        val card = Rect(16f, 400f, 344f, 1200f)
        assertEquals(Rect(58f, 600f, 250f, 1028f), lerpRect(ball, card, 0.5f))
    }

    @Test
    fun `lerp clamps out of range progress`() {
        val card = Rect(16f, 400f, 344f, 1200f)
        assertEquals(ball, lerpRect(ball, card, -0.5f))
        assertEquals(card, lerpRect(ball, card, 1.5f))
    }

    @Test
    fun `content stays hidden until the real container has mostly landed`() {
        assertEquals(0f, panelContentAlpha(0f), 0.0001f)
        assertEquals(0f, panelContentAlpha(0.55f), 0.0001f)
        assertEquals(0.5f, panelContentAlpha(0.775f), 0.0001f)
        assertEquals(1f, panelContentAlpha(1f), 0.0001f)
    }
}
