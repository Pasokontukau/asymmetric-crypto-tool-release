package com.cryptoassistant.app.overlay

import android.content.Context
import android.provider.Settings
import com.cryptoassistant.app.data.SettingsRepository

/**
 * 悬浮球启停的唯一入口（设置页开关、快捷设置磁贴都走这里）。
 *
 * 顺序固定为「先落偏好，再启停服务」：即使服务启动失败，用户的意愿也被记下来了，
 * 下次进入应用或再点一次磁贴会按同一份设置重试。
 */
object BallToggle {

    /** 是否具备显示悬浮球的条件；没有权限时调用方应先引导授权。 */
    fun canEnable(context: Context): Boolean = Settings.canDrawOverlays(context)

    suspend fun enable(context: Context, settings: SettingsRepository) {
        settings.setBallEnabled(true)
        OverlayService.start(context)
    }

    suspend fun disable(context: Context, settings: SettingsRepository) {
        settings.setBallEnabled(false)
        OverlayService.stop(context)
    }
}
