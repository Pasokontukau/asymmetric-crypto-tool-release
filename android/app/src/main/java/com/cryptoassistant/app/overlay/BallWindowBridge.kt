package com.cryptoassistant.app.overlay

/**
 * 面板/菜单展开期间暂时隐藏悬浮球窗口的进程内桥。
 *
 * 为什么要引用计数：面板打开时菜单可能还在播收回动画，两边都以为自己该把球放回来，
 * 结果球会盖在面板上。用计数把"谁需要球暂时消失"叠加起来，最后一处释放时才真正显示。
 *
 * 所有调用都发生在主线程（OverlayService 与 BallActionActivity 都在主线程收尾）。
 */
internal object BallWindowBridge {

    private var controller: OverlayController? = null
    private var hiddenBy = 0

    fun attach(controller: OverlayController) {
        this.controller = controller
        hiddenBy = 0
    }

    fun detach(controller: OverlayController) {
        if (this.controller === controller) {
            this.controller = null
            hiddenBy = 0
        }
    }

    fun hide() {
        hiddenBy++
        controller?.setBallVisible(false)
    }

    fun show() {
        if (hiddenBy > 0) hiddenBy--
        if (hiddenBy == 0) controller?.setBallVisible(true)
    }

    /** 当前是否仍有持有者要求隐藏球（Activity 重建时用来区分配置变化与进程重启）。 */
    fun isHiding(): Boolean = hiddenBy > 0
}
