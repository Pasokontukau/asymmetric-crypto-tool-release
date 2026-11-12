package com.cryptoassistant.app.tile

/**
 * 悬浮球磁贴的状态（纯逻辑，无 Android 依赖，可被 JVM 单测覆盖）。
 *
 * 判定口径是「球是否真的能用」，而不是单纯读偏好：
 * 权限没有 → 一律不可用；否则看服务是否在跑。
 * 这样手机重启、被系统强停之后磁贴会如实显示为关闭，用户点一下就能把球拉回来，
 * 不会出现「开关显示开着、屏幕上看不到球」的假状态。
 */
enum class BallTileState { ACTIVE, INACTIVE, NEEDS_PERMISSION }

fun resolveBallTile(overlayGranted: Boolean, running: Boolean): BallTileState = when {
    !overlayGranted -> BallTileState.NEEDS_PERMISSION
    running -> BallTileState.ACTIVE
    else -> BallTileState.INACTIVE
}
