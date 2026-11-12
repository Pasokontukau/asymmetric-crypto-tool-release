package com.cryptoassistant.app.tile

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.net.toUri
import com.cryptoassistant.app.CryptoAssistantApplication
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.log.AppLog
import com.cryptoassistant.app.overlay.BallToggle
import com.cryptoassistant.app.overlay.OverlayService
import kotlinx.coroutines.launch

/**
 * 控制中心里的「悬浮球」磁贴。
 *
 * 语义就是一个真开关：开着点一下关掉，关着点一下打开。状态按**球是否真的在**判定
 * （见 [resolveBallTile]），所以重启后、被系统强停后磁贴会如实显示为关闭，点一下即可恢复。
 *
 * 没有悬浮窗权限时磁贴显示为不可用，点击直接跳到系统授权页——绝不假装打开一个不存在的球。
 * 点击路径统一走 `unlockAndRun`，避免在锁屏上叠出浮层。
 */
class BallTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())
    private var destroyed = false

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        AppLog.debug("tile.ball", "state=added")
        render()
    }

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun { onTileClicked() }
    }

    private fun onTileClicked() {
        val app = application as CryptoAssistantApplication
        val settings = app.settingsRepository
        when {
            // 正在运行：关掉。
            OverlayService.isRunning -> {
                AppLog.debug("tile.ball", "state=off")
                // 面板正在展开时系统不会再回调 onStartListening，所以先乐观更新一次，
                // 再在服务真的停下后校一次，避免“点了却还显示开着”。
                render(BallTileState.INACTIVE)
                app.appScope.launch {
                    BallToggle.disable(this@BallTileService, settings)
                    TileSync.refresh(this@BallTileService)
                }
                scheduleReconcile()
            }

            // 没权限：先去授权，不启动服务（OverlayService 自己也会拒绝无权限启动）。
            !Settings.canDrawOverlays(this) -> {
                AppLog.warn("tile.ball", "state=needs_permission")
                openOverlaySettings()
            }

            else -> {
                AppLog.debug("tile.ball", "state=on")
                render(BallTileState.ACTIVE)
                app.appScope.launch {
                    BallToggle.enable(this@BallTileService, settings)
                    TileSync.refresh(this@BallTileService)
                }
                scheduleReconcile()
            }
        }
    }

    /** 启动/停止服务要几百毫秒，稍后按真实状态再渲染一次（失败时会自动恢复为关闭）。 */
    private fun scheduleReconcile() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (!destroyed) render() }, RECONCILE_DELAY_MS)
    }

    private companion object {
        const val RECONCILE_DELAY_MS = 900L
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri(),
        )
        launchFromTile(intent)
    }

    /** 把当前状态推给控制中心；磁贴已被移除时 `qsTile` 为 null，直接跳过。 */
    private fun render() {
        render(
            resolveBallTile(
                overlayGranted = Settings.canDrawOverlays(this),
                running = OverlayService.isRunning,
            ),
        )
    }

    private fun render(state: BallTileState) {
        // 磁贴已被移除时 qsTile 为 null；点击路径也可能发生在非监听态，直接跳过。
        val tile = qsTile ?: return
        tile.state = when (state) {
            BallTileState.ACTIVE -> Tile.STATE_ACTIVE
            // 注意：缺权限时**不能**用 STATE_UNAVAILABLE——实测系统会把磁贴置为不可点，
            // 用户就永远进不了授权页。保持可点的"关"状态，靠副标题说明缺什么。
            BallTileState.INACTIVE, BallTileState.NEEDS_PERMISSION -> Tile.STATE_INACTIVE
        }
        // 副标题从 Android 10 起才有；26–28 只能靠开关状态表达。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when (state) {
                    BallTileState.ACTIVE -> R.string.tile_ball_subtitle_on
                    BallTileState.INACTIVE -> R.string.tile_ball_subtitle_off
                    BallTileState.NEEDS_PERMISSION -> R.string.tile_ball_subtitle_permission
                },
            )
        }
        tile.updateTile()
    }
}
