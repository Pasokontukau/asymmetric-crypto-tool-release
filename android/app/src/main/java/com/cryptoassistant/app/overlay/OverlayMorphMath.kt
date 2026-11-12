package com.cryptoassistant.app.overlay

import android.graphics.Rect

/**
 * 悬浮球形态变换用到的纯几何计算。
 *
 * 用自带的 [MorphRect] 而不是直接传 `android.graphics.Rect`：后者在 JVM 单测里是桩实现，
 * 这样这些函数才能被普通 unit test 覆盖（矩形插值、菜单落位）。
 */
internal data class MorphRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
}

internal fun Rect.toMorphRect(): MorphRect = MorphRect(left, top, right, bottom)

/** 两个矩形之间按 [fraction]（0..1）线性插值。 */
internal fun lerpMorphRect(from: MorphRect, to: MorphRect, fraction: Float): MorphRect {
    val f = fraction.coerceIn(0f, 1f)
    fun lerp(a: Int, b: Int): Int = (a + (b - a) * f).toInt()
    return MorphRect(
        left = lerp(from.left, to.left),
        top = lerp(from.top, to.top),
        right = lerp(from.right, to.right),
        bottom = lerp(from.bottom, to.bottom),
    )
}

/**
 * 长按菜单的目标矩形。
 *
 * 优先放在球下方；下方放不下就翻到上方；水平方向以球心对齐并夹在屏幕边距内。
 * [safeTop] / [safeBottom] 是状态栏与导航栏占用的高度，菜单不会压到它们。
 */
internal fun menuTargetRect(
    ball: MorphRect,
    menuWidth: Int,
    menuHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
    safeTop: Int,
    safeBottom: Int,
    gap: Int,
    margin: Int,
): MorphRect {
    val minX = margin
    val maxX = (screenWidth - menuWidth - margin).coerceAtLeast(minX)
    val x = (ball.centerX - menuWidth / 2).coerceIn(minX, maxX)

    val minY = safeTop + margin
    val maxY = (screenHeight - safeBottom - menuHeight - margin).coerceAtLeast(minY)
    val below = ball.bottom + gap
    val above = ball.top - gap - menuHeight
    val y = if (below <= maxY) below.coerceIn(minY, maxY) else above.coerceIn(minY, maxY)

    return MorphRect(x, y, x + menuWidth, y + menuHeight)
}
