package com.cryptoassistant.app.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.feature.files.ArchiveVolumeSize
import com.cryptoassistant.app.service.TextAccessibilityService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode(@get:StringRes val labelRes: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
}

/** 品牌色方案；动态配色开启时由系统取色，本设置不生效。 */
enum class AccentPalette(@get:StringRes val labelRes: Int) {
    INDIGO(R.string.accent_indigo),
    TEAL(R.string.accent_teal),
    AMBER(R.string.accent_amber),
    VIOLET(R.string.accent_violet),
}

/**
 * 悬浮球的工作模式。
 *
 * - [QUICK]：申请无障碍，单击球就地读写当前输入框（能力最全、最顺手）；
 * - [STANDARD]：完全不碰无障碍，单击球打开文字输入面板（不自动读剪贴板）。
 *
 * 注意：这是**用户意愿**，优先级高于系统里的无障碍实际状态——标准模式即使
 * 无障碍恰好开着也不会去读输入框。
 */
enum class BallWorkMode(@get:StringRes val labelRes: Int) {
    QUICK(R.string.mode_quick),
    STANDARD(R.string.mode_standard),
}

/**
 * 无障碍披露文案版本（任务书 §2.4）。披露内容有实质变化时递增，
 * 让用户对新文案重新同意一次；本地只记录状态/时间/版本，不记录任何输入内容。
 *
 * v3（2026-11-02）：补充读不到输入框时的剪贴板兜底说明（W1/W3）。
 */
const val A11Y_DISCLOSURE_VERSION = "2026-11-02-v3"

/** 一次输入框节点读写的动作类型（设置页「最近一次读写」状态行用）。 */
enum class FieldIoAction { READ, WRITE }

/**
 * 读写结果的**原因**，全部是不含文字内容的枚举：
 * 密码框跳过、快照不符这类原因必须能让用户在设置页看到，但绝不能带上输入框里的字。
 */
enum class FieldIoOutcome {
    /** 成功读到 / 写入。 */
    SUCCESS,
    /** 读到了焦点控件，但内容为空白。 */
    EMPTY,
    /** 没有活动窗口或没有输入焦点。 */
    NO_FOCUS,
    /** 目标是密码框/PIN 等敏感输入框，按合规要求主动跳过。 */
    PASSWORD_SKIPPED,
    /** 写回前复核失败：应用已切换，或用户改过文字（拒绝覆盖）。 */
    SNAPSHOT_MISMATCH,
    /** 胶囊关闭后找不到可写回的目标。 */
    NO_TARGET,
    /** SET_TEXT 与粘贴都失败。 */
    WRITE_FAILED,
}

/** 最近一次输入框节点读写的结果摘要。 */
data class FieldIoRecord(
    val action: FieldIoAction,
    val outcome: FieldIoOutcome,
    val at: Long,
    val packageName: String,
)

/** 包名只含 `[a-zA-Z0-9_.]`，用 `|` 分隔不会歧义。 */
internal fun encodeFieldIo(record: FieldIoRecord): String =
    listOf(
        record.action.name,
        record.outcome.name,
        record.at.toString(),
        record.packageName,
    ).joinToString("|")

/** 解析失败（未来版本/损坏）时返回 null，不影响其它设置读取。 */
internal fun parseFieldIo(raw: String?): FieldIoRecord? {
    val parts = raw?.split("|") ?: return null
    if (parts.size != 4) return null
    val action = FieldIoAction.entries.firstOrNull { it.name == parts[0] } ?: return null
    val outcome = FieldIoOutcome.entries.firstOrNull { it.name == parts[1] } ?: return null
    val at = parts[2].toLongOrNull() ?: return null
    return FieldIoRecord(action, outcome, at, parts[3])
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    /** 品牌色方案；默认沿用现有靛蓝，老用户升级后视觉不变。 */
    val accentPalette: AccentPalette = AccentPalette.INDIGO,
    /** 深色主题下使用纯黑表面；仅动态配色关闭时生效。 */
    val amoledDark: Boolean = false,
    val ballEnabled: Boolean = false,
    val ballXFraction: Float = 1f,
    val ballYFraction: Float = 0.62f,
    /** 悬浮球尺寸档位；默认标准 56dp，与旧版本一致。 */
    val ballSize: BallSize = BallSize.STANDARD,
    /** 球本体透明度；默认不透明。 */
    val ballOpacity: BallOpacity = BallOpacity.SOLID,
    /** 上次加密用的联系人 id（指纹），再次点球时默认选中。 */
    val lastContactId: String? = null,
    /** 便捷模式下，外部单文件入口先加/解密并用可见 HUD 确认；默认关闭。 */
    val fileQuickPipeline: Boolean = false,
    /** 多个文件打包后的单个分卷上限；默认 500 MB。 */
    val archiveVolumeSize: ArchiveVolumeSize = ArchiveVolumeSize.DEFAULT,
    /** 多文件/文件夹进入加密页时默认勾选“打包为一个加密包”；单文件不显示入口。 */
    val packageMultipleFiles: Boolean = true,
    /** 生物识别安全锁：开启后导出/导入/删除密钥前验证；默认关闭。 */
    val securityLockEnabled: Boolean = false,
    /** 应用锁：打开主界面时验证；默认关闭，且依赖生物识别安全锁。 */
    val appLockEnabled: Boolean = false,
    /** 应用锁重新验证间隔：0 = 立即，10 = 10 分钟后；默认 10。 */
    val lockTimeoutMinutes: Int = 10,
    /** 悬浮球工作模式：便捷模式读写当前输入框（未连接时先引导），标准模式打开文字面板。 */
    val ballWorkMode: BallWorkMode = BallWorkMode.QUICK,
    /** 无障碍披露同意时间（未同意为 null）；配合 [a11yConsentVersion] 使用。 */
    val a11yConsentAt: Long? = null,
    /** 用户同意时的披露文案版本；版本变化后需要重新同意。 */
    val a11yConsentVersion: String? = null,
    /** 最近一次输入框节点读写的结果（不含文字内容）。 */
    val lastFieldIo: FieldIoRecord? = null,
)

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * 本地设置存储。只保存非敏感偏好：主题、悬浮球开关与归一化位置。
 * 不保存任何明文、密文、密钥或剪贴板内容。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ACCENT_PALETTE = stringPreferencesKey("accent_palette")
        val AMOLED_DARK = booleanPreferencesKey("amoled_dark")
        val BALL_ENABLED = booleanPreferencesKey("ball_enabled")
        val BALL_X = floatPreferencesKey("ball_x_fraction")
        val BALL_Y = floatPreferencesKey("ball_y_fraction")
        val BALL_SIZE = stringPreferencesKey("ball_size")
        val BALL_OPACITY = stringPreferencesKey("ball_opacity")
        val LAST_CONTACT = stringPreferencesKey("last_contact_id")
        val FILE_QUICK_PIPELINE = booleanPreferencesKey("file_quick_pipeline")
        val ARCHIVE_VOLUME_SIZE = stringPreferencesKey("archive_volume_size")
        val PACKAGE_MULTIPLE_FILES = booleanPreferencesKey("package_multiple_files")
        val SECURITY_LOCK = booleanPreferencesKey("security_lock")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val LOCK_TIMEOUT_MINUTES = intPreferencesKey("lock_timeout_minutes")
        val BALL_WORK_MODE = stringPreferencesKey("ball_work_mode")
        val A11Y_CONSENT_AT = longPreferencesKey("a11y_consent_at")
        val A11Y_CONSENT_VERSION = stringPreferencesKey("a11y_consent_version")
        val LAST_FIELD_IO = stringPreferencesKey("last_field_io")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            AppSettings(
                themeMode = prefs[Keys.THEME_MODE]?.let { stored ->
                    ThemeMode.entries.firstOrNull { it.name == stored }
                } ?: ThemeMode.SYSTEM,
                dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
                accentPalette = prefs[Keys.ACCENT_PALETTE]?.let { stored ->
                    AccentPalette.entries.firstOrNull { it.name == stored }
                } ?: AccentPalette.INDIGO,
                amoledDark = prefs[Keys.AMOLED_DARK] ?: false,
                ballEnabled = prefs[Keys.BALL_ENABLED] ?: false,
                ballXFraction = prefs[Keys.BALL_X] ?: 1f,
                ballYFraction = prefs[Keys.BALL_Y] ?: 0.62f,
                ballSize = BallSize.fromStored(prefs[Keys.BALL_SIZE]),
                ballOpacity = BallOpacity.fromStored(prefs[Keys.BALL_OPACITY]),
                lastContactId = prefs[Keys.LAST_CONTACT],
                fileQuickPipeline = prefs[Keys.FILE_QUICK_PIPELINE] ?: false,
                archiveVolumeSize = ArchiveVolumeSize.fromStored(prefs[Keys.ARCHIVE_VOLUME_SIZE]),
                packageMultipleFiles = prefs[Keys.PACKAGE_MULTIPLE_FILES] ?: true,
                securityLockEnabled = prefs[Keys.SECURITY_LOCK] ?: false,
                appLockEnabled = prefs[Keys.APP_LOCK] ?: false,
                // 旧版本可存 1/5/30 分钟；读出来统一归一到「立即」或「10 分钟后」。
                lockTimeoutMinutes = normalizeLockTimeout(prefs[Keys.LOCK_TIMEOUT_MINUTES]),
                ballWorkMode = prefs[Keys.BALL_WORK_MODE]
                    ?.let { stored -> BallWorkMode.entries.firstOrNull { it.name == stored } }
                    ?: migratedWorkMode(),
                a11yConsentAt = prefs[Keys.A11Y_CONSENT_AT],
                a11yConsentVersion = prefs[Keys.A11Y_CONSENT_VERSION],
                lastFieldIo = parseFieldIo(prefs[Keys.LAST_FIELD_IO]),
            )
        }

    /**
     * 老装机没有模式记录时的迁移判据：
     * 系统里无障碍已经开着，说明用户此前就在用「点球就地读写」，留在便捷模式；
     * 否则（包括从未开过无障碍的那批用户）进入标准模式，不再被引导弹窗打扰。
     *
     * 只在没有 `ball_work_mode` 键时生效；用户一旦手选，之后只认设置。
     */
    private fun migratedWorkMode(): BallWorkMode =
        if (TextAccessibilityService.isEnabled(context)) BallWorkMode.QUICK else BallWorkMode.STANDARD

    /** 把迁移结果落盘，让模式从此由设置决定，不再随系统无障碍状态漂移。 */
    suspend fun ensureBallWorkMode() {
        val prefs = context.settingsDataStore.data.first()
        if (prefs[Keys.BALL_WORK_MODE] != null) return
        val mode = migratedWorkMode()
        context.settingsDataStore.edit { it[Keys.BALL_WORK_MODE] = mode.name }
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        AppLog.debug("settings.change", "key=theme value=${mode.name}")
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        AppLog.debug("settings.change", "key=dynamicColor value=$enabled")
        context.settingsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAccentPalette(palette: AccentPalette) {
        AppLog.debug("settings.change", "key=accentPalette value=${palette.name}")
        context.settingsDataStore.edit { it[Keys.ACCENT_PALETTE] = palette.name }
    }

    suspend fun setAmoledDark(enabled: Boolean) {
        AppLog.debug("settings.change", "key=amoledDark value=$enabled")
        context.settingsDataStore.edit { it[Keys.AMOLED_DARK] = enabled }
    }

    suspend fun setBallEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.BALL_ENABLED] = enabled }
    }

    suspend fun setBallWorkMode(mode: BallWorkMode) {
        AppLog.debug("settings.change", "key=ballMode value=${mode.name}")
        context.settingsDataStore.edit { it[Keys.BALL_WORK_MODE] = mode.name }
    }

    /** 「文件快捷处理」开关；仅便捷模式下的外部单文件入口生效。 */
    suspend fun setFileQuickPipeline(enabled: Boolean) {
        AppLog.debug("settings.change", "key=filePipeline value=$enabled")
        context.settingsDataStore.edit { it[Keys.FILE_QUICK_PIPELINE] = enabled }
    }

    /** 多个文件打包后的单个分卷上限；只保存五档枚举名。 */
    suspend fun setArchiveVolumeSize(size: ArchiveVolumeSize) {
        AppLog.debug("settings.change", "key=archiveVolumeSize value=${size.name}")
        context.settingsDataStore.edit { it[Keys.ARCHIVE_VOLUME_SIZE] = size.name }
    }

    /** 新选择多文件/文件夹时是否默认勾选打包；只影响下一次选择。 */
    suspend fun setPackageMultipleFiles(enabled: Boolean) {
        AppLog.debug("settings.change", "key=packageMultipleFiles value=$enabled")
        context.settingsDataStore.edit { it[Keys.PACKAGE_MULTIPLE_FILES] = enabled }
    }

    /**
     * 生物识别安全锁开关。
     *
     * <p>调用方必须先完成生物识别/设备凭据验证；本方法只负责落盘，不在这里弹窗，
     * 避免业务层绕过 UI 直接改设置时出现“静默关闭”。</p>
     */
    suspend fun setSecurityLockEnabled(enabled: Boolean) {
        AppLog.debug("settings.change", "key=securityLock value=$enabled")
        context.settingsDataStore.edit { it[Keys.SECURITY_LOCK] = enabled }
    }

    /** 应用锁开关；关闭总开关时由调用方一并关闭它，避免孤儿配置。 */
    suspend fun setAppLockEnabled(enabled: Boolean) {
        AppLog.debug("settings.change", "key=appLock value=$enabled")
        context.settingsDataStore.edit { it[Keys.APP_LOCK] = enabled }
    }

    /** 应用锁重新验证间隔；只接受 0（立即）或 10（10 分钟后）。 */
    suspend fun setLockTimeoutMinutes(minutes: Int) {
        val normalized = normalizeLockTimeout(minutes)
        AppLog.debug("settings.change", "key=lockTimeout value=$normalized")
        context.settingsDataStore.edit { it[Keys.LOCK_TIMEOUT_MINUTES] = normalized }
    }

    /** 记录上次加密的接收方；传 null 表示清除。 */
    suspend fun setLastContactId(id: String?) {
        context.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.LAST_CONTACT) else prefs[Keys.LAST_CONTACT] = id
        }
    }

    /** 记录用户对当前版本无障碍披露的同意（时间 + 文案版本，不含任何输入内容）。 */
    suspend fun recordA11yConsent(version: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.A11Y_CONSENT_VERSION] = version
            prefs[Keys.A11Y_CONSENT_AT] = System.currentTimeMillis()
        }
    }

    /** 记录最近一次输入框节点读写的结果摘要；只存原因枚举，不存文字内容。 */
    suspend fun recordFieldIo(
        action: FieldIoAction,
        outcome: FieldIoOutcome,
        packageName: String,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.LAST_FIELD_IO] = encodeFieldIo(
                FieldIoRecord(action, outcome, System.currentTimeMillis(), packageName),
            )
        }
    }

    suspend fun saveBallPosition(xFraction: Float, yFraction: Float) {
        context.settingsDataStore.edit {
            it[Keys.BALL_X] = xFraction.coerceIn(0f, 1f)
            it[Keys.BALL_Y] = yFraction.coerceIn(0f, 1f)
        }
    }

    suspend fun setBallSize(size: BallSize) {
        AppLog.debug("settings.change", "key=ballSize value=${size.name}")
        context.settingsDataStore.edit { it[Keys.BALL_SIZE] = size.name }
    }

    suspend fun setBallOpacity(opacity: BallOpacity) {
        AppLog.debug("settings.change", "key=ballOpacity value=${opacity.name}")
        context.settingsDataStore.edit { it[Keys.BALL_OPACITY] = opacity.name }
    }

    /** 0 保持「立即」，其余（含旧版本 1/5/30 与异常值）统一归一到 10 分钟。 */
    private fun normalizeLockTimeout(stored: Int?): Int = if (stored == 0) 0 else 10
}
