package com.cryptoassistant.app.feature.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.cryptoassistant.app.MainActivity
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.feature.open.FileEntryActivity
import com.cryptoassistant.app.feature.security.SecuritySession
import com.cryptoassistant.app.feature.security.findFragmentActivity
import com.cryptoassistant.app.data.log.LogFields
import java.io.File

/**
 * 文件任务的两种对外动作，语义必须分清楚：
 *
 * - [shareFile]：`ACTION_SEND`，把文件**发给某个应用/人**（微信、邮件、网盘）。
 *   MIME 固定 `application/octet-stream`，密文与明文都保持"这是一个文件"的语义。
 * - [openWithOtherApp]：`ACTION_VIEW`，用**能打开这种内容的应用**打开它（WPS、图片查看器、
 *   文本编辑器）。MIME 必须来自明文自身的类型，并且只对解密结果提供。
 *
 * 两者都只授予目标应用这一份文件的临时读权限，不暴露私有目录。
 */

/**
 * 对外可见的分享标签。加密结果只允许使用脱敏后的 [FileTaskResult.displayName]；
 * 原始文件名在 [FileTaskResult.sourceName] 里，仅用于结果页/历史等本机展示，不能进 Intent。
 */
internal data class ShareLabel(val title: String, val clipLabel: String)

internal fun shareLabel(result: FileTaskResult): ShareLabel =
    ShareLabel(title = result.displayName, clipLabel = result.displayName)

private fun uriFor(context: Context, result: FileTaskResult): Uri? =
    runCatching {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            result.file,
        )
    }.getOrNull()

/**
 * 所有外发 chooser 都排除本应用：新增 SEND 接收过滤器后，否则会把结果再分享回自己。
 */
private fun fileChooser(context: Context, target: Intent, title: String): Intent =
    Intent.createChooser(target, title).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf(
                ComponentName(context, MainActivity::class.java),
                // 文件入口现在也是 SEND 接收者，必须一起排除，否则结果会被再分享回自己。
                ComponentName(context, FileEntryActivity::class.java),
            ),
        )
    }

/**
 * `Intent.createChooser` 自身通常总能启动；目标列表为空时系统会显示“无法执行此操作”，
 * 所以必须在启动前直接检查目标 intent。查询失败时宁可继续尝试，不要误伤正常分享。
 * 本应用的入口也排除，否则只有自己时仍会被当成“有目标”。
 */
private fun hasExternalTarget(context: Context, target: Intent): Boolean =
    runCatching {
        context.packageManager.queryIntentActivities(
            target,
            PackageManager.MATCH_DEFAULT_ONLY,
        ).any { it.activityInfo.packageName != context.packageName }
    }.getOrDefault(true)

/**
 * 启动 chooser；只有真正返回成功才让调用方写导出租约。
 * 用户取消 chooser 仍会返回成功，这是本方法的已知边界（见批次计划 §12/D12）。
 */
private fun startChooser(context: Context, chooser: Intent, failureMessage: String): Boolean {
    // 只有从 MainActivity 发起的分享才计入应用锁的外部流程；文件入口不锁，也不应影响主界面。
    val trackAppLock = context.findFragmentActivity() != null
    if (trackAppLock) SecuritySession.beginExternalFlow()
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        if (trackAppLock) SecuritySession.endExternalFlow()
        Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
        false
    } catch (_: SecurityException) {
        if (trackAppLock) SecuritySession.endExternalFlow()
        Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
        false
    }
}

/** 至少有一个外部应用能接收 `ACTION_SEND` 的文件（已排除本应用）。 */
fun canShareFiles(context: Context): Boolean = hasExternalTarget(
    context,
    Intent(Intent.ACTION_SEND).apply { type = "application/octet-stream" },
)

/**
 * @return 只有 chooser 真正启动才为 true；调用方可据此决定是否保留临时目录/关闭 HUD。
 */
fun shareFile(context: Context, result: FileTaskResult): Boolean {
    val uri = uriFor(context, result)
    if (uri == null) {
        Toast.makeText(
            context,
            context.getString(R.string.files_share_prepare_failed),
            Toast.LENGTH_SHORT,
        ).show()
        return false
    }
    val label = shareLabel(result)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = if (result.mode == FileTaskMode.DECRYPT && result.sourceIsFolder) {
            "application/zip"
        } else {
            "application/octet-stream"
        }
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, label.title)
        clipData = ClipData.newRawUri(label.clipLabel, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (!hasExternalTarget(context, send)) {
        Toast.makeText(
            context,
            context.getString(R.string.files_no_share_target),
            Toast.LENGTH_SHORT,
        ).show()
        return false
    }
    AppLog.debug(
        "file.share",
        "op=send mode=${result.mode.name} count=1 mimeFamily=${LogFields.mimeFamily(send.type)}",
    )
    val chooser = fileChooser(
        context,
        send,
        context.getString(R.string.files_chooser_share),
    )
    if (!startChooser(
            context,
            chooser,
            context.getString(R.string.files_no_share_target),
        )
    ) {
        return false
    }
    // chooser 已成功启动后再写租约；取消 chooser 的边界见 startChooser 注释。
    result.file.parentFile?.let { FileTaskStorage.markExported(it) }
    return true
}

/**
 * 批量分享：一次把多个结果交给目标应用。
 *
 * <p>只对成功的结果调用；每个文件仍只授予临时读权限，并分别写入导出租约。
 * 一些聊天应用只接受单个文件，失败时会走系统的「无法处理」提示，不会静默丢文件。</p>
 */
fun shareFiles(context: Context, results: List<FileTaskResult>): Boolean {
    if (results.isEmpty()) return false
    if (results.size == 1) return shareFile(context, results.first())
    val prepared = results.mapNotNull { result ->
        uriFor(context, result)?.let { uri -> result to uri }
    }
    if (prepared.isEmpty()) {
        Toast.makeText(
            context,
            context.getString(R.string.files_share_prepare_failed),
            Toast.LENGTH_SHORT,
        ).show()
        return false
    }
    val label = shareLabel(prepared.first().first)
    val clip = ClipData.newRawUri(label.clipLabel, prepared.first().second)
    prepared.drop(1).forEach { (_, uri) ->
        clip.addItem(ClipData.Item(uri))
        // 这里只准备 ClipData；租约等 chooser 启动成功后再写。
    }
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/octet-stream"
        putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            ArrayList(prepared.map { it.second }),
        )
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (!hasExternalTarget(context, send)) {
        Toast.makeText(
            context,
            context.getString(R.string.files_no_share_target),
            Toast.LENGTH_SHORT,
        ).show()
        return false
    }
    AppLog.debug(
        "file.share",
        "op=send_multiple mode=${results.first().mode.name} count=${prepared.size} " +
            "mimeFamily=${LogFields.mimeFamily(send.type)}",
    )
    val chooser = fileChooser(
        context,
        send,
        context.getString(R.string.files_chooser_share),
    )
    if (!startChooser(
            context,
            chooser,
            context.getString(R.string.files_no_share_target),
        )
    ) {
        return false
    }
    prepared.forEach { (result, _) ->
        result.file.parentFile?.let { FileTaskStorage.markExported(it) }
    }
    return true
}

/**
 * 分享归档结果：单卷沿用 [shareFile]，多卷一次性构造 `ACTION_SEND_MULTIPLE`。
 *
 * <p>分卷顺序由 [ArchiveSharePlan] 按 `_volNNN` 排序；接收方必须收齐全部
 * 分卷才能拼回 ZIP。分享仍只授予临时读权限，chooser 真正启动后才写租约。</p>
 */
fun shareArchive(context: Context, result: FileTaskResult): Boolean {
    val plan = ArchiveSharePlan.from(result)
    if (plan.isEmpty) return false
    val parts = plan.parts.map { part -> archivePartResult(result, part.file) }
    // 多卷必须整组分享：任何一个 URI 准备失败都不能静默少发一卷。
    if (parts.size > 1 && parts.any { uriFor(context, it) == null }) {
        Toast.makeText(
            context,
            context.getString(R.string.files_share_prepare_failed),
            Toast.LENGTH_SHORT,
        ).show()
        return false
    }
    return shareFiles(context, parts)
}

/** 分享其中一卷；不重新排序，卷号由 [ArchiveSharePlan] 决定。 */
fun shareArchivePart(context: Context, result: FileTaskResult, part: File): Boolean {
    if (result.parts.none { it.absoluteFile == part.absoluteFile }) return false
    return shareFile(context, archivePartResult(result, part))
}

private fun archivePartResult(result: FileTaskResult, part: File): FileTaskResult =
    result.copy(
        file = part,
        displayName = part.name,
        bytes = part.length(),
        parts = emptyList(),
        volumeCount = null,
    )

fun openWithOtherApp(context: Context, result: FileTaskResult): Boolean {
    val uri = uriFor(context, result)
    if (uri == null) {
        Toast.makeText(
            context,
            context.getString(R.string.files_open_prepare_failed),
            Toast.LENGTH_SHORT,
        ).show()
        return false
    }
    val mime = if (result.mode == FileTaskMode.DECRYPT && result.sourceIsFolder) {
        "application/zip"
    } else {
        FileMime.forResult(context, uri, result.displayName)
    }
    val view = Intent(Intent.ACTION_VIEW).apply {
        // ACTION_VIEW 的目标应用读 getData()/getType()，必须用 setDataAndType，不能塞 EXTRA_STREAM。
        setDataAndType(uri, mime)
        clipData = ClipData.newRawUri(shareLabel(result).clipLabel, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (!hasExternalTarget(context, view)) {
        Toast.makeText(
            context,
            context.getString(R.string.files_no_open_target),
            Toast.LENGTH_SHORT,
        ).show()
        return false
    }
    AppLog.debug(
        "file.open",
        "op=view mode=${result.mode.name} count=1 mimeFamily=${LogFields.mimeFamily(mime)}",
    )
    val chooser = fileChooser(
        context,
        view,
        context.getString(R.string.files_open_other_app),
    )
    if (!startChooser(
            context,
            chooser,
            context.getString(R.string.files_no_open_target),
        )
    ) {
        return false
    }
    result.file.parentFile?.let { FileTaskStorage.markExported(it) }
    return true
}
