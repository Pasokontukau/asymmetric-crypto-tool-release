package com.cryptoassistant.app.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.cryptoassistant.app.CryptoAssistantApplication
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.BallWorkMode
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.service.TextAccessibilityService
import kotlinx.coroutines.launch

/**
 * 控制中心里的「便捷模式」磁贴（开关型）。
 *
 * 语义是切换工作模式：便捷（QUICK，点球就地加解密输入框）↔ 标准（STANDARD，点球开文字面板）。
 * 状态按 `ballWorkMode` 判定；开启便捷模式但无障碍未开启时，副标题标「无障碍未开启」提醒，
 * 但磁贴保持可点——绝不能用 STATE_UNAVAILABLE，否则用户点不进设置去开无障碍。
 */
class QuickModeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val app = application as CryptoAssistantApplication
        val settings = app.settingsRepository
        app.appScope.launch {
            val quick = settings.current().ballWorkMode == BallWorkMode.QUICK
            val target = if (quick) BallWorkMode.STANDARD else BallWorkMode.QUICK
            AppLog.debug("tile.quick", "mode=${target.name}")
            // 先乐观更新一次，设置落盘后由 TileSync 重新校准。
            render(quick = target == BallWorkMode.QUICK)
            settings.setBallWorkMode(target)
            TileSync.refresh(this@QuickModeTileService)
        }
    }

    private fun render() {
        val app = application as CryptoAssistantApplication
        app.appScope.launch {
            val quick = app.settingsRepository.current().ballWorkMode == BallWorkMode.QUICK
            render(quick)
        }
    }

    private fun render(quick: Boolean) {
        // 磁贴已被移除时 qsTile 为 null，直接跳过。
        val tile = qsTile ?: return
        tile.state = if (quick) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when {
                    !quick -> R.string.tile_quick_subtitle_standard
                    TextAccessibilityService.isEnabled(this) -> R.string.tile_quick_subtitle_on
                    else -> R.string.tile_quick_subtitle_no_a11y
                },
            )
        }
        tile.updateTile()
    }
}
