package com.cryptoassistant.app.feature.ball

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.cryptoassistant.app.ui.theme.Sizes
import com.cryptoassistant.app.ui.theme.Spacing
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 面板的真实容器宿主。
 *
 * 这里不再用"幽灵矩形盖在最终卡片上"的结构。SubcomposeLayout 先按最终宽度测量一次内容，
 * 然后在同一个容器上插值"球矩形 → 卡片矩形"；内容按最终尺寸放置，由当前容器裁剪。
 * 收回时同一容器反向缩回球，不会出现另一颗小球再滑回原位。
 */
@Composable
internal fun BallPanelHost(
    ballRectScreen: android.graphics.Rect?,
    skipIntro: Boolean,
    closing: Boolean,
    /** 与悬浮球档位一致，保证 48/56/64dp 下代理球与真球重合。 */
    ballIconSize: androidx.compose.ui.unit.Dp = Sizes.floatingBallIcon,
    /** 配置变化重建时球已被上一个实例隐藏，交给新实例继续持有，避免引用计数错乱。 */
    ballAlreadyHidden: Boolean = false,
    onDismiss: () -> Unit,
    onHideBall: () -> Unit,
    onExitFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val progress = remember {
        Animatable(if (ballRectScreen == null || skipIntro) 1f else 0f)
    }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    val layoutInfo = remember { PanelLayoutInfo() }

    /**
     * 卡片是否已经完成第一次测量/布局。
     *
     * 必须等到这一刻才隐藏真球、启动进度：SubcomposeLayout 首次测量要排版整张卡片，
     * 在它完成前 draw 阶段拿不到 finalRect，只能画遮罩；如果这时已经隐藏真球，
     * 用户会看到“球先消失、遮罩变暗、卡片随后突然跳出来”。
     */
    var laidOut by remember { mutableStateOf(false) }

    val startRect = remember(ballRectScreen, hostOrigin) {
        ballRectScreen?.let {
            Rect(
                left = it.left - hostOrigin.x,
                top = it.top - hostOrigin.y,
                right = it.right - hostOrigin.x,
                bottom = it.bottom - hostOrigin.y,
            )
        }
    }
    layoutInfo.startRect = startRect

    LaunchedEffect(laidOut, skipIntro) {
        if (!laidOut) return@LaunchedEffect
        // 布局完成的同一帧，draw 阶段就能画出与真球重合的代理；此时再隐藏真球。
        if (!ballAlreadyHidden) onHideBall()
        // 旋转重建 / 磁贴等没有球形变路径：直接落到最终状态，不伪造从底部飞入。
        if (ballRectScreen == null || skipIntro) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 320,
                easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
            ),
        )
    }

    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        if (progress.value > 0f) {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 240,
                    easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
                ),
            )
        }
        // 最后再停一帧代理球：它此刻与真球完全重合，随后由 Activity 恢复真球并把整窗透明化。
        withFrameNanos { }
        onExitFinished()
    }

    val primary = MaterialTheme.colorScheme.primary
    val primaryDeep = MaterialTheme.colorScheme.primaryContainer
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val cardCorner = MaterialTheme.shapes.large.topStart
    val iconPainter = rememberVectorPainter(Icons.Outlined.Shield)
    val iconSize = with(density) { ballIconSize.toPx() }
    val iconTint = MaterialTheme.colorScheme.onPrimary
    // 与悬浮球本体同一套描边颜色（onPrimary），保证球变卡片的过渡不出现色差。
    val ringColor = MaterialTheme.colorScheme.onPrimary
    val scrimColor = MaterialTheme.colorScheme.scrim
    val imeBottom = WindowInsets.ime.getBottom(density)

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩全屏；点击关闭只在接近落位后生效，避免手抖。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawRect(scrimColor.copy(alpha = 0.45f * progress.value))
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (progress.value > 0.9f) onDismiss()
                },
        )

        SubcomposeLayout(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    hostOrigin = it.localToScreen(Offset.Zero)
                    // 第一次布局完成：finalRect 已由 measure 写入，可以安全交接并开始动画。
                    if (!laidOut) laidOut = true
                }
                .drawWithContent {
                    val finalRect = layoutInfo.finalRect
                    if (finalRect.width <= 0f) {
                        drawContent()
                        return@drawWithContent
                    }
                    val p = progress.value.coerceIn(0f, 1f)
                    val start = layoutInfo.startRect
                    val fromBall = start != null
                    val rect = if (start == null) finalRect else lerpRect(start, finalRect, p)
                    val endRadius = cardCorner.toPx(rect.size, this)
                    val radius = if (start == null) {
                        endRadius
                    } else {
                        val ballRadius = start.height / 2f
                        (ballRadius + (endRadius - ballRadius) * p)
                            .coerceIn(0f, rect.height / 2f)
                    }
                    val corner = CornerRadius(radius)

                    // 真实卡片内容会被 clipPath 裁掉，Surface 自带的阴影也跟着消失；
                    // 这里按当前容器补一层很淡的投影，避免面板看起来贴在屏幕上。
                    val shadowAlpha = 0.10f * p
                    if (shadowAlpha > 0f) {
                        val spread = 10.dp.toPx()
                        for (layer in 3 downTo 1) {
                            val grow = spread * layer / 3f
                            drawRoundRect(
                                color = Color.Black.copy(alpha = shadowAlpha / layer),
                                topLeft = Offset(rect.left - grow, rect.top - grow + 1.dp.toPx()),
                                size = Size(rect.width + grow * 2f, rect.height + grow * 2f),
                                cornerRadius = CornerRadius(radius + grow),
                            )
                        }
                    }

                    // 当前容器本身就是可见的卡片；不是幽灵。
                    drawRoundRect(
                        color = surface,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        cornerRadius = corner,
                        alpha = if (fromBall) 1f else p,
                    )
                    if (fromBall) {
                        drawRoundRect(
                            brush = Brush.linearGradient(listOf(primary, primaryDeep)),
                            topLeft = rect.topLeft,
                            size = rect.size,
                            cornerRadius = corner,
                            alpha = 1f - p,
                        )
                        drawRoundRect(
                            color = ringColor.copy(alpha = 0.35f * (1f - p)),
                            topLeft = rect.topLeft,
                            size = rect.size,
                            cornerRadius = corner,
                            style = Stroke(width = 1.dp.toPx()),
                        )

                        // 与真实球一致的首帧代理：描边 + 盾牌图标。展开初段淡出。
                        translate(
                            left = rect.center.x - iconSize / 2f,
                            top = rect.center.y - iconSize / 2f,
                        ) {
                            with(iconPainter) {
                                draw(
                                    size = Size(iconSize, iconSize),
                                    alpha = (1f - p).coerceIn(0f, 1f),
                                    colorFilter = ColorFilter.tint(iconTint),
                                )
                            }
                        }
                    }

                    // 内容按最终尺寸测量、放置一次；动画期间只做绘制平移与裁剪，
                    // 不重新测量卡片，避免每帧触发文字重排。
                    val path = Path().apply {
                        addRoundRect(RoundRect(rect, corner))
                    }
                    clipPath(path) {
                        translate(
                            left = rect.left - finalRect.left,
                            top = rect.top - finalRect.top,
                        ) {
                            this@drawWithContent.drawContent()
                        }
                    }
                },
        ) { constraints ->
            val horizontalMargin = with(density) { Spacing.lg.toPx() }
            val verticalMargin = with(density) { Spacing.xxl.toPx() }
            val maxCardWidth = with(density) { 400.dp.toPx() }
            val cardWidth = min(
                maxCardWidth,
                (constraints.maxWidth - horizontalMargin * 2f).coerceAtLeast(1f),
            ).roundToInt()
            val availableHeight = (constraints.maxHeight - imeBottom).coerceAtLeast(1)
            val maxCardHeight = (availableHeight - verticalMargin * 2f)
                .coerceAtLeast(1f)
                .roundToInt()

            val card = subcompose("card") {
                Box(
                    modifier = Modifier.graphicsLayer {
                        alpha = panelContentAlpha(progress.value)
                    },
                ) {
                    content()
                }
            }.first().measure(
                Constraints(
                    minWidth = cardWidth,
                    maxWidth = cardWidth,
                    minHeight = 0,
                    maxHeight = maxCardHeight,
                ),
            )

            val contentHeight = availableHeight - verticalMargin * 2f
            val top = verticalMargin +
                ((contentHeight - card.height) / 2f).coerceAtLeast(0f)
            val finalRect = Rect(
                left = (constraints.maxWidth - cardWidth) / 2f,
                top = top,
                right = (constraints.maxWidth + cardWidth) / 2f,
                bottom = top + card.height,
            )
            layoutInfo.finalRect = finalRect

            layout(constraints.maxWidth, constraints.maxHeight) {
                // 固定放在最终位置；动画在 draw 阶段平移可见区域，避免逐帧测量。
                card.place(finalRect.left.roundToInt(), finalRect.top.roundToInt())
            }
        }
    }
}

/** 内容只在容器基本落位后淡入，避免文字在放大过程中被看清重排。 */
internal fun panelContentAlpha(progress: Float): Float =
    ((progress.coerceIn(0f, 1f) - 0.55f) / 0.45f).coerceIn(0f, 1f)

internal fun lerpRect(from: Rect, to: Rect, fraction: Float): Rect {
    val f = fraction.coerceIn(0f, 1f)
    fun lerp(a: Float, b: Float): Float = a + (b - a) * f
    return Rect(
        left = lerp(from.left, to.left),
        top = lerp(from.top, to.top),
        right = lerp(from.right, to.right),
        bottom = lerp(from.bottom, to.bottom),
    )
}

/** measure 阶段写入，draw 阶段读取，避免为了几何额外触发重组。 */
private class PanelLayoutInfo {
    var startRect: Rect? = null
    var finalRect: Rect = Rect.Zero
}
