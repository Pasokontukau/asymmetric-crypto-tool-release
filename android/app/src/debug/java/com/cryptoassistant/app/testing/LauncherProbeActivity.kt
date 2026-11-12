package com.cryptoassistant.app.testing

import android.os.Bundle
import android.widget.Button
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity

/**
 * 仅存在于 debug 变体的测试探针，不进入 release。
 *
 * <p>1.0.7 把 `MainActivity` 换成 `FragmentActivity` 后，`fragment:1.2.5` 的
 * 16 位请求码校验会让 Compose/`ActivityResultRegistry` 的 32 位请求码直接抛
 * `IllegalArgumentException`。这个 Activity 只做一件事：在真实的
 * `FragmentActivity` 上注册并触发 `CreateDocument`，让 instrumented 测试能够
 * 在不点 UI 的情况下复现/守住那条启动链。</p>
 */
class LauncherProbeActivity : FragmentActivity() {

    private lateinit var launcher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { /* 测试只关心启动是否成功，不写文件 */ }
        setContentView(
            Button(this).apply {
                text = "launch-create-document"
                setOnClickListener { launchProbe() }
            },
        )
    }

    /** 由 instrumented 测试在主线程调用。 */
    fun launchProbe() {
        launcher.launch("probe.txt")
    }
}
