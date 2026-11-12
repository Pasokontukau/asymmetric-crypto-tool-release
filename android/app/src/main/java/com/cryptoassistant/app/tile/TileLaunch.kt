package com.cryptoassistant.app.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.Toast
import com.cryptoassistant.app.R

/**
 * 从磁贴拉起一个 Activity 并收起控制中心。
 *
 * Android 14 起 `startActivityAndCollapse(Intent)` 被废弃，且后台启动 Activity 的规则更严，
 * 必须改用 `PendingIntent` 版本；低版本继续用 Intent 版本。
 * 个别 ROM 会直接拒绝，这时给一条提示，而不是让用户觉得"点了没反应"。
 */
// 低版本没有 PendingIntent 重载，只能在 SDK < 34 走废弃的 Intent 版本；已按版本分支。
@SuppressLint("StartActivityAndCollapseDeprecated")
internal fun TileService.launchFromTile(intent: Intent) {
    val launchIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val launched = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }.isSuccess
    if (!launched) {
        Toast.makeText(this, R.string.tile_launch_failed, Toast.LENGTH_SHORT).show()
    }
}
