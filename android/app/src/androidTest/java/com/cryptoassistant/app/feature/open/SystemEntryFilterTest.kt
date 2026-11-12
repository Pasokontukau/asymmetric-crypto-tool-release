package com.cryptoassistant.app.feature.open

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptoassistant.app.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 只验证本应用 Manifest 的 filter 是否匹配代表性 Intent。
 *
 * <p>2026-10-31 起文件 filter 挂在透明入口 [FileEntryActivity] 上；这里同时锁住
 * “命中新入口”和“MainActivity 不再重复声明文件入口”，避免迁移时两条 filter 并存。
 * 这不能代表微信/QQ/文件管理器会看到本应用：候选列表还受调用方包可见性和 ROM
 * 过滤影响。真正的真机矩阵仍按迁移计划 §7 由用户取证。</p>
 */
@RunWith(AndroidJUnit4::class)
class SystemEntryFilterTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun viewFilterAcceptsAnyContentType() {
        listOf(
            "application/octet-stream",
            "application/x-encrypted",
            "application/vnd.aegix",
            "application/x-aegix",
            "application/pdf",
            "application/vnd.android.package-archive",
            "application/x-7z-compressed",
            "text/plain",
            "image/jpeg",
            "video/mp4",
            "audio/mpeg",
            "*/*",
        ).forEach { type ->
            assertTrue("VIEW 应匹配 content + $type", matches(view(type)))
        }
        // filter 可以多写 OPENABLE；不能因此排掉只带 DEFAULT 的发送方。
        assertTrue(matches(view("application/octet-stream", openable = false)))
    }

    @Test
    fun viewFilterAcceptsContentWithoutType() {
        // 只声明 scheme 的 filter 负责覆盖不带 type 的 content intent。
        assertTrue(matches(viewWithoutType()))
    }

    @Test
    fun viewFilterStillRejectsFileScheme() {
        assertFalse(matches(view("application/octet-stream", scheme = "file")))
    }

    @Test
    fun sendFilterAcceptsAnyType() {
        listOf(
            "image/jpeg",
            "video/mp4",
            "audio/mpeg",
            "application/pdf",
            "application/octet-stream",
            "application/vnd.android.package-archive",
            "text/plain",
            "*/*",
        ).forEach { type ->
            assertTrue("SEND 应匹配 $type", matches(send(type)))
        }
        // 只带 DEFAULT 的分享也必须匹配；OPENABLE 只是扩面，不是必要条件。
        assertTrue(matches(send("image/jpeg", openable = false)))
    }

    @Test
    fun sendMultipleFilterAcceptsMixedTypes() {
        assertTrue(matches(sendMultiple("*/*")))
        assertTrue(matches(sendMultiple("image/jpeg")))
        assertTrue(matches(sendMultiple("application/octet-stream")))
        assertTrue(matches(sendMultiple("*/*", openable = false)))
    }

    @Test
    fun sendWithoutTypeIsKnownBoundary() {
        // 没有 data、没有 type 的 SEND 需要额外的“无 data”filter；当前按边界不声明。
        assertFalse(matches(sendWithoutType()))
    }

    @Test
    fun mainActivityDoesNotDeclareFileFilters() {
        // filter 已迁到 FileEntryActivity；MainActivity 只保留 LAUNCHER 与磁贴偏好。
        assertFalse(mainActivityMatches(view("application/octet-stream")))
        assertFalse(mainActivityMatches(send("image/jpeg")))
        assertFalse(mainActivityMatches(sendMultiple("*/*")))
    }

    private fun view(
        type: String,
        scheme: String = "content",
        openable: Boolean = true,
    ): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("$scheme://com.cryptoassistant.app.fileprovider/test.aegix"), type)
            addCategory(Intent.CATEGORY_DEFAULT)
            if (openable) addCategory(Intent.CATEGORY_OPENABLE)
        }

    private fun viewWithoutType(): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("content://com.cryptoassistant.app.fileprovider/test.aegix")
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_OPENABLE)
        }

    private fun send(type: String, openable: Boolean = true): Intent =
        Intent(Intent.ACTION_SEND).apply {
            setType(type)
            addCategory(Intent.CATEGORY_DEFAULT)
            if (openable) addCategory(Intent.CATEGORY_OPENABLE)
        }

    private fun sendWithoutType(): Intent =
        Intent(Intent.ACTION_SEND).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_OPENABLE)
        }

    private fun sendMultiple(type: String, openable: Boolean = true): Intent =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            setType(type)
            addCategory(Intent.CATEGORY_DEFAULT)
            if (openable) addCategory(Intent.CATEGORY_OPENABLE)
        }

    private fun matches(intent: Intent): Boolean =
        matchesComponent(intent, FileEntryActivity::class.java.name)

    private fun mainActivityMatches(intent: Intent): Boolean =
        matchesComponent(intent, MainActivity::class.java.name)

    private fun matchesComponent(intent: Intent, componentName: String): Boolean =
        context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { info ->
                info.activityInfo.packageName == context.packageName &&
                    info.activityInfo.name == componentName
            }
}
