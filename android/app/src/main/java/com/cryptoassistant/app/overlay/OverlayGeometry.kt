package com.cryptoassistant.app.overlay

import kotlin.math.abs

/** 屏幕可用区域（像素，含系统栏安全区）。 */
data class ScreenBounds(
    val width: Int,
    val height: Int,
    val safeTop: Int = 0,
    val safeBottom: Int = 0,
)

data class BallPosition(val x: Int, val y: Int)

/**
 * 悬浮球位置计算：纯函数，便于单元测试，不依赖 Android framework。
 */
object OverlayGeometry {

    fun clamp(x: Int, y: Int, ballSize: Int, bounds: ScreenBounds): BallPosition {
        val maxX = (bounds.width - ballSize).coerceAtLeast(0)
        val minY = bounds.safeTop.coerceAtLeast(0)
        val maxY = (bounds.height - bounds.safeBottom - ballSize).coerceAtLeast(minY)
        return BallPosition(
            x = x.coerceIn(0, maxX),
            y = y.coerceIn(minY, maxY),
        )
    }

    /** 选择距离更近的左右边缘，返回吸附后的 x。 */
    fun snapToNearestEdge(x: Int, ballSize: Int, bounds: ScreenBounds, margin: Int): Int {
        val left = margin.coerceAtLeast(0)
        val right = (bounds.width - ballSize - margin).coerceAtLeast(left)
        val distanceToLeft = abs(x - left)
        val distanceToRight = abs(right - x)
        return if (distanceToLeft <= distanceToRight) left else right
    }

    fun snap(x: Int, y: Int, ballSize: Int, bounds: ScreenBounds, margin: Int): BallPosition {
        val clamped = clamp(x, y, ballSize, bounds)
        return BallPosition(
            x = snapToNearestEdge(clamped.x, ballSize, bounds, margin),
            y = clamped.y,
        )
    }

    /** 归一化位置，用于跨旋转/分辨率保存。 */
    fun toFraction(position: BallPosition, ballSize: Int, bounds: ScreenBounds): Pair<Float, Float> {
        val maxX = (bounds.width - ballSize).coerceAtLeast(1)
        val minY = bounds.safeTop.coerceAtLeast(0)
        val maxY = (bounds.height - bounds.safeBottom - ballSize).coerceAtLeast(minY + 1)
        val fx = position.x.toFloat() / maxX
        val fy = (position.y - minY).toFloat() / (maxY - minY)
        return fx.coerceIn(0f, 1f) to fy.coerceIn(0f, 1f)
    }

    fun fromFraction(fx: Float, fy: Float, ballSize: Int, bounds: ScreenBounds): BallPosition {
        val maxX = (bounds.width - ballSize).coerceAtLeast(0)
        val minY = bounds.safeTop.coerceAtLeast(0)
        val maxY = (bounds.height - bounds.safeBottom - ballSize).coerceAtLeast(minY)
        val x = (fx.coerceIn(0f, 1f) * maxX).toInt()
        val y = minY + (fy.coerceIn(0f, 1f) * (maxY - minY)).toInt()
        return clamp(x, y, ballSize, bounds)
    }
}
