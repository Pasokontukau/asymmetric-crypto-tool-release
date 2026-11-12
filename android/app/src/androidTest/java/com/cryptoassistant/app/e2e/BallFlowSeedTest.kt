package com.cryptoassistant.app.e2e

import android.content.ClipData
import android.content.ClipboardManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cryptoassistant.app.CryptoAssistantApplication
import com.cryptoassistant.app.data.BallWorkMode
import com.cryptoassistant.app.data.keys.IdentityKey
import com.cryptoassistant.app.data.keys.KeyRepository
import com.cryptoassistant.app.data.keys.PrivateKeyBackup
import com.cryptoassistant.app.feature.ball.TextActionUseCase
import crypto.key.KeyPairManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 手工验收用的种子测试（只在 androidTest 里，不进入产品代码）。
 *
 * 悬浮球的加解密发生在「本应用拿到窗口焦点」的胶囊里，命令行无法写设备剪贴板，
 * 所以先用这两个用例把剪贴板内容准备好，再由人手点悬浮球走完整个闭环：
 *
 *  - seedPlaintextForEncrypt：剪贴板放一段明文（加密动作的输入）；
 *  - seedCiphertextForDecrypt：新增「测试发送者」联系人，并用它的私钥签名、
 *    用设备身份公钥加密一段明文写进剪贴板（解密动作的输入）。
 *
 * 单人设备无法自签自解，因此解密用例需要这个「模拟发送者」。
 */
@RunWith(AndroidJUnit4::class)
class BallFlowSeedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val repository: KeyRepository
        get() = (context.applicationContext as CryptoAssistantApplication).keyRepository

    /**
     * 把「悬浮球已启用」写进设置。之后重新拉起 MainActivity，
     * AppNavigation 的 LaunchedEffect 会自动启动前台服务并显示球（需先授予悬浮窗权限）。
     * 这样别的 agent 不必靠点开关坐标也能复现测试环境。
     */
    @Test
    fun prepareBallEnabled() = runBlocking {
        val app = context.applicationContext as CryptoAssistantApplication
        app.settingsRepository.setBallEnabled(true)
    }

    /** 手工验收标准模式：单击应打开空白输入框，而不是读剪贴板。 */
    @Test
    fun prepareStandardMode() = runBlocking {
        val app = context.applicationContext as CryptoAssistantApplication
        app.settingsRepository.setBallWorkMode(BallWorkMode.STANDARD)
    }

    /** 手工验收便捷模式：剪贴板出现密文时自动解密进胶囊（T9 胶囊展开验证用）。 */
    @Test
    fun prepareQuickMode() = runBlocking {
        val app = context.applicationContext as CryptoAssistantApplication
        app.settingsRepository.setBallWorkMode(BallWorkMode.QUICK)
    }

    @Test
    fun seedPlaintextForEncrypt() = runBlocking {
        ensureIdentity()
        setClipboard(PLAINTEXT)
    }

    @Test
    fun seedCiphertextForDecrypt() = runBlocking {
        val identity = ensureIdentity()
        val sender = KeyPairManager.generateKeyPair(4096)
        val senderPublic = PrivateKeyBackup.encodePublicKey(sender.public)

        // 同名旧联系人的私钥已经随上次测试丢失，先移除再添加。
        repository.snapshot.value.contacts
            .filter { it.name == SENDER_NAME }
            .forEach { repository.removeContact(it.id) }
        repository.addContact(SENDER_NAME, senderPublic).getOrThrow()

        val myPublic = PrivateKeyBackup.decodePublicKey(identity.publicKeyBase64)
        val cipher = TextActionUseCase.encrypt(DECRYPTED_PLAINTEXT, sender.private, myPublic)
        setClipboard(cipher)
    }

    @Test
    fun seedCiphertextFromUnknownSender() = runBlocking {
        val identity = ensureIdentity()
        val stranger = KeyPairManager.generateKeyPair(4096)
        val myPublic = PrivateKeyBackup.decodePublicKey(identity.publicKeyBase64)
        val cipher = TextActionUseCase.encrypt("这条消息的发送者不在联系人里。", stranger.private, myPublic)
        setClipboard(cipher)
    }

    private suspend fun ensureIdentity(): IdentityKey {
        repository.snapshot.value.identity?.let { return it }
        val created = repository.createIdentity("本机身份").getOrThrow()
        assertNotNull(created)
        return created
    }

    private fun setClipboard(text: String) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        manager.setPrimaryClip(ClipData.newPlainText("seed", text))
    }

    private companion object {
        const val SENDER_NAME = "测试发送者"
        const val PLAINTEXT = "这是待加密的测试消息，用来验证悬浮球的加密动作。"
        const val DECRYPTED_PLAINTEXT = "这段明文由测试发送者签名，并加密给本机密钥。"
    }
}
