package com.cryptoassistant.app.feature.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用锁的内存会话。
 *
 * <p>只记录“这次进程里有没有解锁、离开多久、是否在外部流程中”，不落盘、不接触
 * 密钥/联系人/日志内容。进程被杀后重新开始，冷启动会重新要求验证。</p>
 */
object SecuritySession {

    data class State(
        /** 设置已加载并写入会话；未就绪时 UI 先渲染空白，避免闪出业务内容。 */
        val configured: Boolean = false,
        /** 是否停在锁屏；只有 true 时主界面内容才不可见。 */
        val locked: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var enabled = false
    private var timeoutMinutes = AppLockPolicy.DEFAULT_TIMEOUT_MINUTES
    private var configured = false
    private var locked = false
    private var foreground = false
    private var backgroundAt = 0L
    private var externalFlowDepth = 0

    /**
     * 设置加载完成或变化时调用。
     *
     * <p>第一次配置视为冷启动：应用锁开启就立即锁屏。之后的变化（用户刚验证过再开启、
     * 旋转重建、改超时）不重复锁。关闭时立即解锁并清掉离开时间。</p>
     */
    fun configure(enabled: Boolean, timeoutMinutes: Int) {
        val firstConfiguration = !configured
        this.enabled = enabled
        this.timeoutMinutes = AppLockPolicy.normalizeTimeoutMinutes(timeoutMinutes)
        if (!enabled) {
            locked = false
            backgroundAt = 0
        } else if (firstConfiguration) {
            locked = AppLockPolicy.shouldLock(
                enabled = true,
                coldStart = true,
                awayMs = 0,
                timeoutMinutes = this.timeoutMinutes,
                externalFlow = false,
            )
        }
        configured = true
        publish()
    }

    /** MainActivity 进入前台；只有真正从后台回来才计算离开时长。 */
    fun onForeground() {
        if (foreground) return
        foreground = true
        val awayMs = if (backgroundAt == 0L) {
            0L
        } else {
            (SystemClock.elapsedRealtime() - backgroundAt).coerceAtLeast(0L)
        }
        val externalFlow = externalFlowDepth > 0
        if (configured && enabled &&
            AppLockPolicy.shouldLock(
                enabled = true,
                coldStart = false,
                awayMs = awayMs,
                timeoutMinutes = timeoutMinutes,
                externalFlow = externalFlow,
            )
        ) {
            locked = true
        }
        backgroundAt = 0
        // 回到前台后外部流程即结束；即便回调没有归零，也不能让标记永久卡住。
        if (externalFlow) externalFlowDepth = 0
        publish()
    }

    /** MainActivity 离开前台；外部流程（SAF/分享/验证面板）不计入离开时长。 */
    fun onBackground() {
        if (!foreground) return
        foreground = false
        if (externalFlowDepth == 0) {
            backgroundAt = SystemClock.elapsedRealtime()
        }
    }

    /** 启动系统选择器/分享/验证面板前调用；可嵌套。 */
    fun beginExternalFlow() {
        externalFlowDepth += 1
    }

    /** 外部流程取消或启动失败时调用；回到前台会兜底清零。 */
    fun endExternalFlow() {
        externalFlowDepth = (externalFlowDepth - 1).coerceAtLeast(0)
    }

    /** 系统验证成功后放行本次会话。 */
    fun unlock() {
        if (!locked) return
        locked = false
        publish()
    }

    private fun publish() {
        _state.value = State(configured = configured, locked = locked)
    }
}
