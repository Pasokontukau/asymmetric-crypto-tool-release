package com.cryptoassistant.app.data

/**
 * 悬浮球尺寸档位。
 *
 * <p>存枚举名而不是裸 dp：以后调整档位或新增尺寸不会让旧 DataStore 数据失效；
 * 图标尺寸也从同一档位派生，避免窗口变大而图标不变。</p>
 */
enum class BallSize(val diameterDp: Int, val iconDp: Int) {
    SMALL(48, 20),
    STANDARD(56, 24),
    LARGE(64, 28),
    ;

    companion object {
        fun fromStored(value: String?): BallSize =
            entries.firstOrNull { it.name == value } ?: STANDARD
    }
}

/** 球本体透明度；菜单、胶囊与引导面板始终不透明。 */
enum class BallOpacity(val alpha: Float) {
    SOLID(1.0f),
    SOFT(0.9f),
    FAINT(0.8f),
    ;

    companion object {
        fun fromStored(value: String?): BallOpacity =
            entries.firstOrNull { it.name == value } ?: SOLID
    }
}
