package com.cryptoassistant.app.smoke

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptoassistant.app.CryptoAssistantApplication
import com.cryptoassistant.app.MainActivity
import com.cryptoassistant.app.R
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CI 的第一层设备 smoke：不碰密钥、文件、网络，只确认应用能启动，
 * 能走到「设置 → 关于」，并且「允许检查更新」开关可切换、退出再进仍保留。
 *
 * <p>只断言稳定的导航与设置持久化，不点「检查更新」和「查看最新版本」：
 * 前者会访问 GitHub，后者会把系统浏览器带到前台；它们属于手工验收，不能
 * 让模拟器 job 依赖外部网络或系统 chooser。</p>
 */
@RunWith(AndroidJUnit4::class)
class StartupAboutSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun startsSettingsAboutAndTogglesUpdateCheck() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 启动后底部导航出现，说明设置已加载、SecurityGate 已放行。
        waitForTag(NAV_SETTINGS)
        compose.onNodeWithTag(NAV_SETTINGS).assertIsDisplayed().performClick()

        // 设置首页是 LazyColumn；先滚到「关于」再点，避免依赖屏幕高度。
        waitForTag(SETTINGS_LIST)
        compose.onNodeWithTag(SETTINGS_LIST)
            .performScrollToNode(hasTestTag(SETTINGS_ABOUT))
        compose.onNodeWithTag(SETTINGS_ABOUT).assertIsDisplayed().performClick()

        waitForTag(ABOUT_SCREEN)
        compose.onNodeWithText(context.getString(R.string.about_section_version))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.about_check_updates))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(UPDATE_SWITCH).performScrollTo().assertIsOff()

        // 打开开关：调用 SettingsRepository，DataStore 异步写回后 Compose 才变 On。
        compose.onNodeWithTag(UPDATE_SWITCH).performClick()
        waitForSwitch(enabled = true)
        compose.onNodeWithTag(UPDATE_SWITCH).assertIsOn()

        // 返回设置再进关于：确认状态来自持久化设置，而不是当前页面的临时 state。
        compose.onNodeWithContentDescription(context.getString(R.string.nav_back)).performClick()
        waitForTag(SETTINGS_LIST)
        compose.onNodeWithTag(SETTINGS_LIST)
            .performScrollToNode(hasTestTag(SETTINGS_ABOUT))
        compose.onNodeWithTag(SETTINGS_ABOUT).performClick()
        waitForTag(ABOUT_SCREEN)
        waitForSwitch(enabled = true)

        // 复位，避免同一 AVD 上后续测试从“已允许联网检查”开始。
        compose.onNodeWithTag(UPDATE_SWITCH).performScrollTo().performClick()
        waitForSwitch(enabled = false)
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForSwitch(enabled: Boolean) {
        compose.waitUntil(TIMEOUT_MS) {
            runCatching {
                if (enabled) {
                    compose.onNodeWithTag(UPDATE_SWITCH).assertIsOn()
                } else {
                    compose.onNodeWithTag(UPDATE_SWITCH).assertIsOff()
                }
            }.isSuccess
        }
    }

    @After
    fun resetUpdateCheck() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as CryptoAssistantApplication
        runBlocking { app.settingsRepository.setUpdateCheckEnabled(false) }
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L
        private const val NAV_SETTINGS = "nav_settings"
        private const val SETTINGS_LIST = "settings_list"
        private const val SETTINGS_ABOUT = "settings_about"
        private const val ABOUT_SCREEN = "about_screen"
        private const val UPDATE_SWITCH = "about_update_check_switch"

        /**
         * 仪器测试可能复用上一次运行的数据；先关掉应用锁/安全锁与悬浮球，
         * 否则 MainActivity 可能停在锁屏或启动前台服务，让 smoke 与功能无关地失败。
         */
        @BeforeClass
        @JvmStatic
        fun resetPersistentSettings() {
            val app = InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationContext as CryptoAssistantApplication
            runBlocking {
                app.settingsRepository.setSecurityLockEnabled(false)
                app.settingsRepository.setAppLockEnabled(false)
                app.settingsRepository.setBallEnabled(false)
                app.settingsRepository.setUpdateCheckEnabled(false)
            }
        }
    }
}
