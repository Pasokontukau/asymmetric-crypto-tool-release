package com.cryptoassistant.app.feature.settings

import androidx.annotation.StringRes
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.log.LogLevel

/** 数据层只存 wireName；显示名称与说明在设置页按当前语言取资源。 */
@get:StringRes
internal val LogLevel.labelRes: Int
    get() = when (this) {
        LogLevel.OFF -> R.string.log_level_off
        LogLevel.ERROR -> R.string.log_level_error
        LogLevel.WARN -> R.string.log_level_warn
        LogLevel.INFO -> R.string.log_level_info
        LogLevel.DEBUG -> R.string.log_level_debug
        LogLevel.VERBOSE -> R.string.log_level_verbose
    }

@get:StringRes
internal val LogLevel.descriptionRes: Int
    get() = when (this) {
        LogLevel.OFF -> R.string.log_level_off_desc
        LogLevel.ERROR -> R.string.log_level_error_desc
        LogLevel.WARN -> R.string.log_level_warn_desc
        LogLevel.INFO -> R.string.log_level_info_desc
        LogLevel.DEBUG -> R.string.log_level_debug_desc
        LogLevel.VERBOSE -> R.string.log_level_verbose_desc
    }
