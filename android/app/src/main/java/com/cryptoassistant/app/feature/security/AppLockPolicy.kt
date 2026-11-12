package com.cryptoassistant.app.feature.security

/**
 * 应用锁的纯时间策略。
 *
 * <p>只回答“这次回到主界面要不要验证”，不持有状态、不读系统 API，方便 JVM 单测。
 * 应用锁关闭或处于外部流程（SAF、分享、系统验证面板）时一律不锁。</p>
 */
object AppLockPolicy {

    /** 设置页可选的离开时长：0 = 立即，10 = 10 分钟后。 */
    val TIMEOUT_OPTIONS_MINUTES = listOf(0, 10)

    const val DEFAULT_TIMEOUT_MINUTES = 10

    /** 只接受「立即」与默认值；旧版本存下的 1/5/30 分钟统一按 10 分钟处理。 */
    fun normalizeTimeoutMinutes(minutes: Int): Int =
        if (minutes == 0) 0 else DEFAULT_TIMEOUT_MINUTES

    /**
     * @param coldStart 进程启动后第一次进入主界面
     * @param awayMs 上次离开主界面到现在的时间；没有离开记录时传 0
     * @param externalFlow 是否由 SAF、分享、系统验证等外部流程触发的离开
     */
    fun shouldLock(
        enabled: Boolean,
        coldStart: Boolean,
        awayMs: Long,
        timeoutMinutes: Int,
        externalFlow: Boolean,
    ): Boolean {
        if (!enabled || externalFlow) return false
        if (coldStart) return true
        val timeoutMs = normalizeTimeoutMinutes(timeoutMinutes) * 60_000L
        return awayMs >= timeoutMs
    }
}
