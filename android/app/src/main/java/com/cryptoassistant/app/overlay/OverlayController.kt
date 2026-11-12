package com.cryptoassistant.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnLayout
import com.cryptoassistant.app.data.AccentPalette
import com.cryptoassistant.app.data.BallOpacity
import com.cryptoassistant.app.data.BallSize
import com.cryptoassistant.app.data.BallWorkMode
import com.cryptoassistant.app.data.ThemeMode
import com.cryptoassistant.app.data.keys.ContactKey
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.service.TextAccessibilityService
import com.cryptoassistant.app.ui.theme.CryptoAssistantTheme
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 悬浮球窗口宿主。
 *
 * 设计约束（见架构文档 6.3）：
 * - 一个窗口承担两种形态：56dp 的圆球，以及被拉长的胶囊（显示解密结果）；
 * - 形态切换用窗口几何动画完成（圆角恒为高度一半，所以视觉上就是球在拉伸）；
 * - 菜单复用球窗口做同窗口变形；遮罩是球窗口下方的独立全屏窗口；
 * - 所有位置按窗口尺寸与系统栏安全区约束，旋转后重新约束。
 */
internal class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val themeMode: ThemeMode,
    private val dynamicColor: Boolean,
    private val accentPalette: AccentPalette,
    private val amoledDark: Boolean,
    private val ballSize: BallSize,
    private val ballOpacity: BallOpacity,
    private val onPositionPersisted: (Float, Float) -> Unit,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onCopyPlaintext: () -> Unit,
    private val onDismissCapsule: () -> Unit,
    private val onSelectContact: (String) -> Unit,
    private val onExpandCapsule: () -> Unit,
    private val onCollapseCapsule: () -> Unit,
) {
    private companion object {
        const val TAG = "OverlayController"
        const val EDGE_MARGIN_DP = 8
        const val MENU_WIDTH_DP = OVERLAY_MENU_WIDTH_DP
        const val MENU_SHADOW_PADDING_DP = OVERLAY_MENU_SHADOW_PADDING_DP
        const val MENU_WINDOW_WIDTH_DP = MENU_WIDTH_DP + MENU_SHADOW_PADDING_DP * 2
        const val MENU_GAP_DP = 12
        const val MENU_SCREEN_MARGIN_DP = 12
        const val CAPSULE_WIDTH_DP = 300
        const val CAPSULE_HEIGHT_DP = 72
        const val CAPSULE_MARGIN_DP = 12
        const val CAPSULE_EXPANDED_WIDTH_DP = 360
        const val CAPSULE_EXPANDED_MAX_HEIGHT_FRACTION = 0.6f
        const val EXPAND_MILLIS = 280L
        const val COLLAPSE_MILLIS = 220L
        const val MENU_EXPAND_MILLIS = 280L
        const val MENU_COLLAPSE_MILLIS = 200L
        const val MENU_SCRIM_ALPHA = 0.30f

        /** 菜单先在这个 y 位置按最终宽度量高度，量完再回到球的位置开始展开。 */
        const val MENU_OFFSCREEN_Y = -20_000
    }

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).roundToInt()

    private val ballSizePx = dp(ballSize.diameterDp)
    private val edgeMarginPx = dp(EDGE_MARGIN_DP)

    private var ballView: ComposeView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    /** 球窗口当前是否真的挂在 WindowManager 上（面板/菜单展开期间会暂时移除）。 */
    private var ballAttached = false
    private var scrimView: ComposeView? = null
    private var scrimParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var scrimOwner: OverlayLifecycleOwner? = null
    private var menuMeasureView: ComposeView? = null
    private var menuMeasureOwner: OverlayLifecycleOwner? = null

    private var position = BallPosition(0, 0)
    private var dragStartPosition: BallPosition? = null
    private var menuOpen = false
    private var menuMeasuring = false
    /** 收回动画期间为 true：忽略菜单行的点击，避免收起途中又触发一次动作。 */
    private var menuClosing = false
    private var menuAnimator: ValueAnimator? = null
    private var menuAnimGeneration = 0
    private var destroyed = false

    /** 0 = 球形态，1 = 完整菜单，菜单展开/收回期间连续变化。 */
    private val menuProgress = mutableStateOf(0f)

    /** 同窗口菜单数据；null = 不显示菜单内容。 */
    private val menuState = mutableStateOf<OverlayMenuPayload?>(null)

    /** 菜单遮罩透明度进度；球窗口本身不动画 scrim。 */
    private val scrimProgress = mutableStateOf(0f)

    /** 胶囊内容（null = 球形态）。 */
    private val capsuleState = mutableStateOf<OverlayCapsulePayload?>(null)

    /** 0 = 球内容，1 = 胶囊内容，动画期间连续变化。 */
    private val capsuleProgress = mutableStateOf(0f)

    /** 胶囊是否展开成可滚动面板（仅在 [capsuleState] 非空时有意义）。 */
    private val capsuleExpanded = mutableStateOf(false)

    /** 当前工作模式：只影响球的读屏文案；切换时关闭已打开的菜单。点球行为由服务端决定。 */
    private val workMode = mutableStateOf(BallWorkMode.QUICK)

    /** 模式变化时同步窗口内容（菜开着就关掉，避免显示旧模式的动作行）。 */
    fun setWorkMode(mode: BallWorkMode) {
        if (workMode.value == mode) return
        workMode.value = mode
        closeMenu(animate = false)
    }

    private var morphAnimator: ValueAnimator? = null

    /** @return 窗口是否真的加上了（失败时服务应停止，不要留下"在跑但没球"的状态）。 */
    fun show(initialXFraction: Float, initialYFraction: Float): Boolean {
        if (ballView != null) return true
        val bounds = currentBounds()
        position = OverlayGeometry.snap(
            x = OverlayGeometry.fromFraction(initialXFraction, initialYFraction, ballSizePx, bounds).x,
            y = OverlayGeometry.fromFraction(initialXFraction, initialYFraction, ballSizePx, bounds).y,
            ballSize = ballSizePx,
            bounds = bounds,
            margin = edgeMarginPx,
        )

        // scrim 必须先于球窗口添加；同类型窗口按添加顺序叠放。
        ensureScrim()

        val owner = OverlayLifecycleOwner().also {
            it.create()
            it.resume()
        }
        val view = ComposeView(context).apply {
            attachOverlayLifecycle(owner)
            setContent {
                CryptoAssistantTheme(
                    themeMode = themeMode,
                    dynamicColor = dynamicColor,
                    accentPalette = accentPalette,
                    amoledDark = amoledDark,
                ) {
                    OverlayWindowContent(
                        ballProgress = capsuleProgress.value,
                        capsule = capsuleState.value,
                        capsuleExpanded = capsuleExpanded.value,
                        workMode = workMode.value,
                        ballOpacity = ballOpacity.alpha,
                        iconSizeDp = ballSize.iconDp,
                        menu = menuState.value,
                        menuProgress = { menuProgress.value },
                        onTap = onTap,
                        onLongPress = onLongPress,
                        onDragStart = { beginDrag() },
                        onDragTo = { dx, dy -> dragBy(dx, dy) },
                        onDragEnd = { settle() },
                        onCopyPlaintext = onCopyPlaintext,
                        onDismissCapsule = onDismissCapsule,
                        onExpandCapsule = { expandCapsule() },
                        onCollapseCapsule = { collapseCapsule() },
                        onSelectContact = { id ->
                            if (!menuClosing) {
                                closeMenu()
                                onSelectContact(id)
                            }
                        },
                    )
                }
            }
        }
        val params = baseParams(ballSizePx, ballSizePx).apply {
            x = position.x
            y = position.y
        }

        val added = runCatching { windowManager.addView(view, params) }
            .onFailure {
                Log.e(TAG, "无法添加悬浮球窗口", it)
                AppLog.error("overlay.window", it, "kind=window")
            }
            .isSuccess
        if (!added) {
            owner.destroy()
            removeScrim()
            return false
        }

        ballView = view
        ballParams = params
        lifecycleOwner = owner
        ballAttached = true
        BallWindowBridge.attach(this)
        return true
    }

    fun hide() {
        destroyed = true
        morphAnimator?.cancel()
        morphAnimator = null
        cancelMenuMeasurement()
        menuAnimator?.cancel()
        menuAnimator = null
        menuOpen = false
        menuMeasuring = false
        menuClosing = false
        menuState.value = null
        menuProgress.value = 0f
        scrimProgress.value = 0f
        capsuleState.value = null
        capsuleProgress.value = 0f
        if (ballAttached) {
            ballView?.let { view -> runCatching { windowManager.removeView(view) } }
        }
        ballAttached = false
        BallWindowBridge.detach(this)
        ballView = null
        ballParams = null
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        removeScrim()
    }

    /**
     * 面板与菜单展开期间把球暂时从窗口上摘下来，关闭后按原位置挂回去。
     *
     * 不能用 View.INVISIBLE：overlay 窗口即使内容不可见，窗口本身仍会拦截那一小块触摸，
     * 面板正好在球的位置时就会出现“点不动”的死区。
     */
    fun setBallVisible(visible: Boolean) {
        if (destroyed) return
        val view = ballView ?: return
        val params = ballParams ?: return
        if (visible == ballAttached) return
        if (visible) {
            // 恢复时一定回到球形态：面板/菜单期间的中间状态不该带回来。
            morphAnimator?.cancel()
            morphAnimator = null
            capsuleState.value = null
            capsuleProgress.value = 0f
            capsuleExpanded.value = false
            menuState.value = null
            menuProgress.value = 0f
            scrimProgress.value = 0f
            menuClosing = false
            setScrimTouchable(false)
            params.width = ballSizePx
            params.height = ballSizePx
            params.x = position.x
            params.y = position.y
            ballAttached = runCatching { windowManager.addView(view, params) }.isSuccess
        } else {
            if (menuOpen || menuMeasuring) finishMenuClose()
            runCatching { windowManager.removeView(view) }
            ballAttached = false
        }
    }

    /** 球的屏幕矩形，作为面板展开动画的起点；球暂时被摘掉时位置依然有效。 */
    fun ballRectOnScreen(): Rect? = if (ballView == null) null else ballRect()

    fun onConfigurationChanged() {
        if (destroyed) return
        closeMenu(animate = false)
        // 旋转后胶囊的尺寸假设失效，直接收回球形态。
        capsuleState.value = null
        capsuleProgress.value = 0f
        capsuleExpanded.value = false
        morphAnimator?.cancel()
        morphAnimator = null
        val bounds = currentBounds()
        position = OverlayGeometry.clamp(position.x, position.y, ballSizePx, bounds)
        ballParams?.let { params ->
            params.width = ballSizePx
            params.height = ballSizePx
            params.x = position.x
            params.y = position.y
            ballView?.let { view -> runCatching { windowManager.updateViewLayout(view, params) } }
        }
        persistPosition(bounds)
    }

    /**
     * 临时让悬浮球窗口拿到输入焦点，用来读系统剪贴板。
     *
     * Android 10+ 只允许「当前持有输入焦点」的应用读剪贴板，后台服务会被直接拒绝。
     * 这里把球窗口的可聚焦标志去掉一瞬间（约 120ms）再恢复，读完立即回到非焦点状态，
     * 不弹任何界面；读不到（部分 ROM 仍拒绝）就返回 null。
     */
    suspend fun readClipboardWhileFocused(read: () -> String?): FocusedClipboard {
        if (!ballAttached) return FocusedClipboard.NoFocus
        val params = ballParams ?: return FocusedClipboard.NoFocus
        val view = ballView ?: return FocusedClipboard.NoFocus
        val originalFlags = params.flags
        params.flags = originalFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        val raised = runCatching { windowManager.updateViewLayout(view, params) }.isSuccess
        if (!raised) {
            params.flags = originalFlags
            return FocusedClipboard.NoFocus
        }
        // 恢复必须走 finally：任何一条异常/取消路径上漏恢复，球窗口会一直保持可聚焦，
        // 之后每次点球读到的“活动窗口”都会是球自己，输入框路径就会永久失效。
        return try {
            // 给窗口一点时间真正拿到焦点，再读剪贴板。
            kotlinx.coroutines.delay(140)
            val text = runCatching { read() }.getOrNull()
            when {
                text.isNullOrBlank() -> FocusedClipboard.Empty
                else -> FocusedClipboard.Content(text)
            }
        } finally {
            params.flags = originalFlags
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    fun isCapsuleVisible(): Boolean = capsuleState.value != null && ballAttached

    fun capsulePlaintext(): String? = capsuleState.value?.plaintext

    /** 把球拉长为胶囊并展示一段明文。 */
    fun showCapsule(payload: OverlayCapsulePayload) {
        if (destroyed || ballView == null || !ballAttached) return
        if (menuOpen) closeMenu(animate = false)
        if (capsuleExpanded.value) {
            // 展开中收到新内容：取消展开动画，窗口直接回胶囊矩形。
            morphAnimator?.cancel()
            morphAnimator = null
            capsuleExpanded.value = false
            applyRect(pillRect())
        }
        if (capsuleState.value != null) {
            // 已经是胶囊：只换内容，不再重新拉伸。
            capsuleState.value = payload
            return
        }
        capsuleState.value = payload
        animateWindow(from = currentRect(), to = pillRect(), expand = true)
    }

    /** 收回胶囊，恢复成球。 */
    fun hideCapsule() {
        if (destroyed || capsuleState.value == null) return
        capsuleExpanded.value = false
        animateWindow(from = currentRect(), to = ballRect(), expand = false)
    }

    /** 胶囊展开成可滚动面板（仅在胶囊可见且未展开时生效）。 */
    fun expandCapsule() {
        if (destroyed || capsuleState.value == null || capsuleExpanded.value) return
        capsuleExpanded.value = true
        animateExpansion(from = currentRect(), to = expandedRect(), expand = true)
        onExpandCapsule()
    }

    /** 展开面板收回成胶囊（仅在已展开时生效）。 */
    fun collapseCapsule() {
        if (destroyed || !capsuleExpanded.value) return
        capsuleExpanded.value = false
        animateExpansion(from = currentRect(), to = pillRect(), expand = false)
        onCollapseCapsule()
    }

    /** 胶囊是否已展开。 */
    fun isCapsuleExpanded(): Boolean = capsuleExpanded.value

    private fun animateExpansion(from: Rect, to: Rect, expand: Boolean) {
        morphAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expand) EXPAND_MILLIS else COLLAPSE_MILLIS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animation ->
                applyRect(lerpRect(from, to, animation.animatedFraction))
            }
        }
        morphAnimator = animator
        animator.start()
    }

    /** 展开面板目标矩形：约 360dp 宽、屏幕可用高度 60%，约束在安全区内。 */
    private fun expandedRect(): Rect {
        val bounds = currentBounds()
        val margin = dp(CAPSULE_MARGIN_DP)
        val width = min(
            dp(CAPSULE_EXPANDED_WIDTH_DP),
            (bounds.width - margin * 2).coerceAtLeast(ballSizePx),
        )
        val usableHeight = bounds.height - bounds.safeTop - bounds.safeBottom
        val height = (usableHeight * CAPSULE_EXPANDED_MAX_HEIGHT_FRACTION).roundToInt()
            .coerceAtLeast(dp(CAPSULE_HEIGHT_DP))
        val centerY = position.y + ballSizePx / 2
        val minY = bounds.safeTop + margin
        val maxY = (bounds.height - bounds.safeBottom - height - margin).coerceAtLeast(minY)
        val top = (centerY - height / 2).coerceIn(minY, maxY)
        val onRightHalf = position.x + ballSizePx / 2 > bounds.width / 2
        val rawLeft = if (onRightHalf) position.x + ballSizePx - width else position.x
        val left = rawLeft.coerceIn(margin, (bounds.width - width - margin).coerceAtLeast(margin))
        return Rect(left, top, left + width, top + height)
    }

    private fun currentRect(): Rect {
        val params = ballParams
        if (params == null) return ballRect()
        return Rect(params.x, params.y, params.x + params.width, params.y + params.height)
    }

    private fun ballRect(): Rect =
        Rect(position.x, position.y, position.x + ballSizePx, position.y + ballSizePx)

    private fun pillRect(): Rect {
        val bounds = currentBounds()
        val margin = dp(CAPSULE_MARGIN_DP)
        val width = min(dp(CAPSULE_WIDTH_DP), (bounds.width - margin * 2).coerceAtLeast(ballSizePx))
        val height = dp(CAPSULE_HEIGHT_DP)
        val centerY = position.y + ballSizePx / 2
        val minY = bounds.safeTop + margin
        val maxY = (bounds.height - bounds.safeBottom - height - margin).coerceAtLeast(minY)
        val top = (centerY - height / 2).coerceIn(minY, maxY)
        val onRightHalf = position.x + ballSizePx / 2 > bounds.width / 2
        val rawLeft = if (onRightHalf) position.x + ballSizePx - width else position.x
        val left = rawLeft.coerceIn(margin, (bounds.width - width - margin).coerceAtLeast(margin))
        return Rect(left, top, left + width, top + height)
    }

    private fun animateWindow(from: Rect, to: Rect, expand: Boolean) {
        morphAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expand) EXPAND_MILLIS else COLLAPSE_MILLIS
            // Material 3 标准「emphasized」曲线，先快后慢，拉伸感更自然。
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animation ->
                val f = animation.animatedFraction
                applyRect(lerpRect(from, to, f))
                capsuleProgress.value = if (expand) f else 1f - f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!expand) {
                        capsuleState.value = null
                        capsuleProgress.value = 0f
                    } else {
                        capsuleProgress.value = 1f
                    }
                }
            })
        }
        morphAnimator = animator
        animator.start()
    }

    private fun lerpRect(from: Rect, to: Rect, fraction: Float): Rect = Rect(
        lerp(from.left, to.left, fraction),
        lerp(from.top, to.top, fraction),
        lerp(from.right, to.right, fraction),
        lerp(from.bottom, to.bottom, fraction),
    )

    private fun lerp(start: Int, end: Int, fraction: Float): Int =
        (start + (end - start) * fraction).roundToInt()

    private fun applyRect(rect: Rect) {
        val params = ballParams ?: return
        val width = (rect.right - rect.left).coerceAtLeast(1)
        val height = (rect.bottom - rect.top).coerceAtLeast(1)
        if (params.x == rect.left && params.y == rect.top &&
            params.width == width && params.height == height
        ) {
            return
        }
        params.x = rect.left
        params.y = rect.top
        params.width = width
        params.height = height
        ballView?.let { view -> runCatching { windowManager.updateViewLayout(view, params) } }
    }

    /** 全屏 scrim 必须在球窗口之前添加，保证它在球/菜单下方。 */
    private fun ensureScrim() {
        if (scrimView != null) return
        val owner = OverlayLifecycleOwner().also {
            it.create()
            it.resume()
        }
        val view = ComposeView(context).apply {
            attachOverlayLifecycle(owner)
            setContent {
                CryptoAssistantTheme(
                    themeMode = themeMode,
                    dynamicColor = dynamicColor,
                    accentPalette = accentPalette,
                    amoledDark = amoledDark,
                ) {
                    OverlayScrim(
                        alpha = scrimProgress.value * MENU_SCRIM_ALPHA,
                        enabled = menuOpen && scrimProgress.value > 0.01f,
                        onDismiss = { closeMenu() },
                    )
                }
            }
        }
        val params = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        ).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val added = runCatching { windowManager.addView(view, params) }
            .onFailure { AppLog.warn("overlay.window", "kind=scrim") }
            .isSuccess
        if (!added) {
            owner.destroy()
            return
        }
        scrimView = view
        scrimParams = params
        scrimOwner = owner
    }

    private fun removeScrim() {
        scrimView?.let { view -> runCatching { windowManager.removeView(view) } }
        scrimView = null
        scrimParams = null
        scrimOwner?.destroy()
        scrimOwner = null
    }

    private fun setScrimTouchable(touchable: Boolean) {
        val view = scrimView ?: return
        val params = scrimParams ?: return
        val flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (flags != params.flags) {
            params.flags = flags
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun cancelMenuMeasurement() {
        menuMeasuring = false
        val view = menuMeasureView
        val owner = menuMeasureOwner
        menuMeasureView = null
        menuMeasureOwner = null
        view?.let { runCatching { windowManager.removeView(it) } }
        owner?.destroy()
    }

    private fun openMenu(
        contacts: List<ContactKey>,
        selectedContactId: String?,
        mode: BallWorkMode,
    ) {
        if (menuOpen || menuMeasuring || ballView == null || !ballAttached) return
        // 胶囊展开时不叠菜单；直接回到球形态，避免两种几何同时存在。
        if (capsuleState.value != null) {
            morphAnimator?.cancel()
            morphAnimator = null
            capsuleState.value = null
            capsuleProgress.value = 0f
            capsuleExpanded.value = false
            applyRect(ballRect())
        }

        menuMeasuring = true
        val owner = OverlayLifecycleOwner().also {
            it.create()
            it.resume()
        }
        val measure = ComposeView(context).apply {
            attachOverlayLifecycle(owner)
            setContent {
                CryptoAssistantTheme(
                    themeMode = themeMode,
                    dynamicColor = dynamicColor,
                    accentPalette = accentPalette,
                    amoledDark = amoledDark,
                ) {
                    OverlayMenuMeasure(
                        mode = mode,
                        contacts = contacts,
                        selectedContactId = selectedContactId,
                    )
                }
            }
        }
        val measureParams = baseParams(
            dp(MENU_WINDOW_WIDTH_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            x = 0
            y = MENU_OFFSCREEN_Y
            alpha = 0f
            // 即使个别 ROM 把窗口夹回屏幕内，也不能抢走触摸。
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val added = runCatching { windowManager.addView(measure, measureParams) }.isSuccess
        if (!added) {
            owner.destroy()
            menuMeasuring = false
            AppLog.warn("ball.menu", "kind=measure_window")
            return
        }
        menuMeasureView = measure
        menuMeasureOwner = owner

        measure.doOnLayout { view ->
            val valid = menuMeasuring && menuMeasureView === measure &&
                ballView != null && ballAttached
            val menuHeight = view.height
            runCatching { windowManager.removeView(view) }
            owner.destroy()
            if (menuMeasureView === measure) menuMeasureView = null
            if (menuMeasureOwner === owner) menuMeasureOwner = null
            menuMeasuring = false
            if (!valid || menuHeight <= 0) {
                AppLog.warn("ball.menu", "kind=measure_invalid")
                return@doOnLayout
            }

            menuOpen = true
            menuClosing = false
            menuState.value = OverlayMenuPayload(
                contacts = contacts,
                selectedContactId = selectedContactId,
                mode = mode,
            )
            menuProgress.value = 0f
            scrimProgress.value = 0f
            setScrimTouchable(true)
            AppLog.debug("ball.menu", "action=open heightPx=$menuHeight contacts=${contacts.size}")

            val bounds = currentBounds()
            val target = menuTargetRect(
                ball = ballRect().toMorphRect(),
                menuWidth = dp(MENU_WINDOW_WIDTH_DP),
                menuHeight = menuHeight,
                screenWidth = bounds.width,
                screenHeight = bounds.height,
                safeTop = bounds.safeTop,
                safeBottom = bounds.safeBottom,
                gap = dp(MENU_GAP_DP),
                margin = dp(MENU_SCREEN_MARGIN_DP),
            )
            animateMenuWindow(
                from = ballRect(),
                to = Rect(target.left, target.top, target.right, target.bottom),
                expand = true,
            )
        }
    }

    /** 长按入口：由服务提供联系人列表与当前选择。 */
    fun showMenu(
        contacts: List<ContactKey>,
        selectedContactId: String?,
        mode: BallWorkMode,
    ) {
        if (destroyed) return
        if (menuOpen) {
            closeMenu()
            return
        }
        if (!menuMeasuring) openMenu(contacts, selectedContactId, mode)
    }

    private fun animateMenuWindow(from: Rect, to: Rect, expand: Boolean) {
        // 先让旧动画的回调失效再取消：cancel 会同步触发 onAnimationEnd。
        menuAnimGeneration++
        menuAnimator?.cancel()
        val generation = menuAnimGeneration
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expand) MENU_EXPAND_MILLIS else MENU_COLLAPSE_MILLIS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animation ->
                val f = animation.animatedFraction
                applyRect(lerpRect(from, to, f))
                val p = if (expand) f else 1f - f
                menuProgress.value = p
                scrimProgress.value = p
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != menuAnimGeneration) return
                    menuAnimator = null
                    if (expand) {
                        menuProgress.value = 1f
                        scrimProgress.value = 1f
                    } else {
                        finishMenuClose()
                    }
                }
            })
        }
        menuAnimator = animator
        animator.start()
    }

    /** 关闭菜单：默认播收回动画；导航型动作、配置变化与销毁走 animate = false。 */
    private fun closeMenu(animate: Boolean = true) {
        if (menuMeasuring) {
            cancelMenuMeasurement()
            menuOpen = false
            menuState.value = null
            menuProgress.value = 0f
            scrimProgress.value = 0f
            menuClosing = false
            setScrimTouchable(false)
            return
        }
        if (!menuOpen) return
        menuOpen = false
        if (!animate || menuState.value == null) {
            finishMenuClose()
            return
        }
        menuClosing = true
        animateMenuWindow(from = currentRect(), to = ballRect(), expand = false)
    }

    private fun finishMenuClose() {
        menuAnimGeneration++
        menuAnimator?.cancel()
        menuAnimator = null
        menuOpen = false
        menuMeasuring = false
        cancelMenuMeasurement()
        menuState.value = null
        menuProgress.value = 0f
        scrimProgress.value = 0f
        menuClosing = false
        setScrimTouchable(false)
        // 同窗口：窗口直接回到球矩形，不再需要挂回另一个球窗口。
        applyRect(ballRect())
    }

    fun isMenuOpen(): Boolean = menuOpen

    fun dismissMenu() = closeMenu(animate = false)

    private fun beginDrag() {
        if (destroyed) return
        if (menuOpen) closeMenu(animate = false)
        if (capsuleState.value != null) return
        dragStartPosition = position
    }

    /** dx/dy 是相对按下点的位移，由窗口所在屏幕的原始坐标计算。 */
    private fun dragBy(dx: Float, dy: Float) {
        val start = dragStartPosition ?: return
        if (capsuleState.value != null) return
        val bounds = currentBounds()
        val next = OverlayGeometry.clamp(
            start.x + dx.roundToInt(),
            start.y + dy.roundToInt(),
            ballSizePx,
            bounds,
        )
        if (next == position) return
        position = next
        ballParams?.let { params ->
            params.x = next.x
            params.y = next.y
            ballView?.let { view -> runCatching { windowManager.updateViewLayout(view, params) } }
        }
    }

    private fun settle() {
        dragStartPosition = null
        if (capsuleState.value != null) return
        val bounds = currentBounds()
        position = OverlayGeometry.snap(position.x, position.y, ballSizePx, bounds, edgeMarginPx)
        ballParams?.let { params ->
            params.x = position.x
            params.y = position.y
            ballView?.let { view -> runCatching { windowManager.updateViewLayout(view, params) } }
        }
        persistPosition(bounds)
    }

    private fun persistPosition(bounds: ScreenBounds = currentBounds()) {
        val (fx, fy) = OverlayGeometry.toFraction(position, ballSizePx, bounds)
        onPositionPersisted(fx, fy)
    }

    private fun baseParams(width: Int, height: Int): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
        }
    }

    private fun currentBounds(): ScreenBounds {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val rect = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return ScreenBounds(rect.width(), rect.height(), insets.top, insets.bottom)
        }
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        return ScreenBounds(
            width = displayMetrics.widthPixels,
            height = displayMetrics.heightPixels,
            safeTop = systemBarHeight("status_bar_height"),
            safeBottom = systemBarHeight("navigation_bar_height"),
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun systemBarHeight(name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
