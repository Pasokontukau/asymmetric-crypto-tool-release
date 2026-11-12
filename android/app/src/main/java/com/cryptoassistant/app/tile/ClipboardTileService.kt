package com.cryptoassistant.app.tile

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.feature.ball.BallActionActivity

/**
 * 控制中心里的「剪贴板加解密」磁贴（动作型，不是开关）。
 *
 * 磁贴自己**不读剪贴板**：Android 10+ 只允许持有输入焦点的应用读剪贴板，
 * 服务/磁贴没有焦点，读到的只会是空。所以这里只负责把面板拉起来，
 * 读取与加解密交回 `BallActionActivity`（它一拿到窗口焦点就能读，现有机制已验证）。
 *
 * 面板会自动判断内容：密文直接解密展示，明文直接落在加密页——点一下磁贴就是一步到位，
 * 不依赖悬浮球，也不依赖无障碍权限。
 */
class ClipboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        // 动作型磁贴不伪装成开关，始终保持中性状态。
        tile.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.tile_clipboard_subtitle)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            AppLog.debug("tile.clipboard", "state=open")
            val intent = Intent(this, BallActionActivity::class.java).apply {
                putExtra(BallActionActivity.EXTRA_ACTION, BallActionActivity.ACTION_AUTO_CLIPBOARD)
                putExtra(BallActionActivity.EXTRA_SOURCE, BallActionActivity.SOURCE_CLIPBOARD)
            }
            launchFromTile(intent)
        }
    }
}
