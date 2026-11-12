package com.cryptoassistant.app.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

/**
 * 让控制中心里的磁贴重新读一次状态。
 *
 * 调用 [TileService.requestListeningState] 后系统会重新触发 `onStartListening()`，
 * 不需要用户把面板拉下来，也不需要额外广播；组件不存在（用户没添加）时是空操作。
 */
object TileSync {

    fun refresh(context: Context) {
        val app = context.applicationContext
        listOf(BallTileService::class.java, ClipboardTileService::class.java, QuickModeTileService::class.java).forEach { service ->
            runCatching {
                TileService.requestListeningState(app, ComponentName(app, service))
            }
        }
    }
}
