package com.cryptoassistant.app.testing

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 守住 1.0.7 的「点导出直接回桌面」回归。
 *
 * <p>当时的链路是：`FragmentActivity`（由 biometric 依赖引入）+ `fragment:1.2.5`
 * 的 16 位请求码校验 + Compose/`ActivityResultRegistry` 的 32 位请求码 →
 * `IllegalArgumentException` 直接结束进程。这里用一个 debug-only 的
 * `FragmentActivity` 注册 `CreateDocument` 并触发，再从 ActivityManager 的
 * 任务栈里确认出现了 `ACTION_CREATE_DOCUMENT` 的 ActivityRecord；旧 fragment
 * 会在 `launch()` 处直接抛异常，测试失败。</p>
 */
@RunWith(AndroidJUnit4::class)
class SystemPickerLaunchTest {

    @Test
    fun createDocumentStartsSystemPicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var scenario: ActivityScenario<LauncherProbeActivity>? = null
        try {
            scenario = ActivityScenario.launch(LauncherProbeActivity::class.java)
            // 旧 fragment 会在这里同步抛 IllegalArgumentException，测试直接失败。
            scenario.onActivity { activity -> activity.launchProbe() }

            var dump = ""
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                dump = dumpTaskStack()
                if (dump.contains(ACTION_CREATE_DOCUMENT)) break
                Thread.sleep(POLL_INTERVAL_MS)
            }

            assertTrue(
                "没有找到 ACTION_CREATE_DOCUMENT 的 ActivityRecord：\n$dump",
                dump.contains(ACTION_CREATE_DOCUMENT),
            )
            val topLine = dump.lineSequence().firstOrNull { it.contains("topResumedActivity") }.orEmpty()
            assertFalse(
                "CreateDocument 之后探针 Activity 仍在前台，系统保存页没有出现：\n$topLine",
                topLine.contains(PACKAGE_NAME),
            )
        } finally {
            // 关掉系统选择器，避免它留在前台影响后续用例。
            runCatching {
                instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
            }
            scenario?.close()
        }
    }

    /** 只取任务栈摘要里的两行，避免整份 dumpsys 太大。 */
    private fun dumpTaskStack(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "dumpsys activity activities | grep -i -m 3 '$ACTION_CREATE_DOCUMENT\\|topResumedActivity'",
        )
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
            it.readText()
        }
    }

    private companion object {
        const val ACTION_CREATE_DOCUMENT = "android.intent.action.CREATE_DOCUMENT"
        const val PACKAGE_NAME = "com.cryptoassistant.app"
        const val TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
