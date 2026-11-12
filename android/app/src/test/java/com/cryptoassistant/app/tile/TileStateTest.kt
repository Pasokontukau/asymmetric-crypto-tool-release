package com.cryptoassistant.app.tile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 磁贴状态矩阵：状态判定只依赖「权限」与「服务是否真的在跑」，
 * 这样重启/被强停之后不会出现「开关显示开着、屏幕上看不到球」。
 */
class TileStateTest {

    @Test
    fun `没有悬浮窗权限时一律不可用`() {
        assertEquals(
            BallTileState.NEEDS_PERMISSION,
            resolveBallTile(overlayGranted = false, running = false),
        )
        // 即使进程里残留了运行标记，没有权限也不该显示为开启。
        assertEquals(
            BallTileState.NEEDS_PERMISSION,
            resolveBallTile(overlayGranted = false, running = true),
        )
    }

    @Test
    fun `有权限且服务在跑时显示开启`() {
        assertEquals(
            BallTileState.ACTIVE,
            resolveBallTile(overlayGranted = true, running = true),
        )
    }

    @Test
    fun `有权限但服务没跑时显示关闭`() {
        // 手机重启 / 被 am force-stop 之后就是这种状态：磁贴如实变灰，点一下可恢复。
        assertEquals(
            BallTileState.INACTIVE,
            resolveBallTile(overlayGranted = true, running = false),
        )
    }
}
