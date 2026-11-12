package com.cryptoassistant.app.data.keys

import android.content.Context
import com.cryptoassistant.app.data.log.AppLog
import crypto.key.KeyPairManager
import crypto.key.KeyUtils
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.PrivateKey
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 本机密钥与联系人的唯一读写入口。
 *
 * - 私钥只以 Keystore 包装后的密文保存，普通操作不需要口令；
 * - 导出备份时内层仍用 GCM1 口令格式加密；外层完整备份由 [KeyBundle] 封装，
 *   导入只接受 `aegix-key-bundle`，不再识别旧版纯 GCM1 文本；
 * - 所有文件都在应用私有目录，不写外部存储；日志只记录操作结果，不记录任何密钥内容。
 */
class KeyRepository(
    context: Context,
    private val vault: KeyVault = AndroidKeyVault(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    rootDirectory: File = File(context.filesDir, "keys"),
) {

    private val directory = rootDirectory
    private val identityFile = File(directory, "identity.properties")
    private val contactsFile = File(directory, "contacts.properties")
    private val mutex = Mutex()

    private val _snapshot = MutableStateFlow(loadSnapshot())
    val snapshot: StateFlow<KeySnapshot> = _snapshot.asStateFlow()

    private val _error = MutableStateFlow<KeyError?>(null)
    val error: StateFlow<KeyError?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    /** 当前身份私钥（仅供后续悬浮球加解密使用，不离开进程）。 */
    fun currentPrivateKey(): PrivateKey? {
        val stored = readIdentity() ?: return null
        val wrapped = runCatching {
            Base64.getDecoder().decode(stored.wrappedPrivateKeyBase64)
        }.getOrNull() ?: return null
        val plain = runCatching { vault.unwrap(wrapped) }.getOrElse {
            AppLog.error("key.unwrap", it, "kind=key")
            _error.value = KeyError.UNWRAP
            return null
        }
        return runCatching { KeyUtils.getPrivateKeyFromBytes(plain) }
            .onFailure {
                AppLog.error("key.decode", it, "kind=key")
                _error.value = KeyError.INVALID_FORMAT
            }
            .getOrNull()
    }

    suspend fun createIdentity(
        displayName: String,
        keySize: Int = DEFAULT_KEY_SIZE,
    ): Result<IdentityKey> = mutate {
        val started = System.currentTimeMillis()
        val name = KeyFileCodec.cleanName(displayName)
        // 桌面端密钥强度校验把 2048–4095 位当作硬失败，生成侧与之保持一致。
        if (keySize < DEFAULT_KEY_SIZE) {
            throw KeyOperationException(KeyError.KEY_SIZE, "keySize=$keySize")
        }
        val pair = KeyPairManager.generateKeyPair(keySize)
        val wrapped = vault.wrap(pair.private.encoded)
        val identity = IdentityKey(
            displayName = name,
            publicKeyBase64 = PrivateKeyBackup.encodePublicKey(pair.public),
            fingerprint = PrivateKeyBackup.fingerprint(pair.public),
            keySize = keySize,
            createdAt = System.currentTimeMillis(),
        )
        writeIdentity(StoredIdentity(identity, Base64.getEncoder().encodeToString(wrapped)))
        AppLog.info(
            "key.create",
            "size=$keySize ms=${System.currentTimeMillis() - started} result=ok",
        )
        identity
    }

    suspend fun importIdentity(
        displayName: String,
        backupText: String,
        password: CharArray,
    ): Result<IdentityKey> = mutate {
        val started = System.currentTimeMillis()
        try {
            val name = KeyFileCodec.cleanName(displayName)
            // 只接受当前完整备份：公钥 + 身份元数据 + 口令加密私钥。
            val bundle = runCatching { KeyBundle.decode(backupText) }
                .getOrElse {
                    throw KeyOperationException(KeyError.BACKUP_FORMAT, it.message, it)
                } ?: throw KeyOperationException(
                KeyError.BACKUP_UNKNOWN_FORMAT,
                "missing aegix-key-bundle header",
            )
            val privateKey = runCatching {
                PrivateKeyBackup.open(bundle.privateKeyBackup, password)
            }.getOrElse {
                throw KeyOperationException(KeyError.BACKUP_PASSWORD, it.message, it)
            }
            val publicKey = PrivateKeyBackup.derivePublicKey(privateKey)
            val size = PrivateKeyBackup.keySize(publicKey)
            if (size < MIN_KEY_SIZE) {
                throw KeyOperationException(KeyError.BACKUP_KEY_SHORT, "size=$size")
            }
            // 公钥可由私钥推导；不一致说明备份被拼改或损坏，不能静默接受。
            if (PrivateKeyBackup.encodePublicKey(publicKey) != bundle.identity.publicKeyBase64) {
                throw KeyOperationException(KeyError.BACKUP_MISMATCH)
            }
            val wrapped = vault.wrap(privateKey.encoded)
            val identity = KeyImportMetadata.importedIdentity(
                displayName = name,
                backupCreatedAt = bundle.identity.createdAt,
                publicKeyBase64 = PrivateKeyBackup.encodePublicKey(publicKey),
                fingerprint = PrivateKeyBackup.fingerprint(publicKey),
                keySize = size,
                importedAt = System.currentTimeMillis(),
            )
            writeIdentity(StoredIdentity(identity, Base64.getEncoder().encodeToString(wrapped)))
            AppLog.info(
                "key.import",
                "size=$size ms=${System.currentTimeMillis() - started} result=ok",
            )
            identity
        } finally {
            PrivateKeyBackup.clear(password)
        }
    }

    /** 导出一次完整备份：公钥 + 身份元数据 + 口令加密的私钥。 */
    suspend fun exportIdentityBackup(password: CharArray): Result<String> = withContext(dispatcher) {
        val started = System.currentTimeMillis()
        try {
            val stored = readIdentity()
                ?: throw KeyOperationException(KeyError.NO_IDENTITY)
            val wrapped = Base64.getDecoder().decode(stored.wrappedPrivateKeyBase64)
            val plain = try {
                vault.unwrap(wrapped)
            } catch (error: Exception) {
                throw KeyOperationException(KeyError.UNWRAP, error.message, error)
            }
            try {
                val privateKey = KeyUtils.getPrivateKeyFromBytes(plain)
                val sealed = PrivateKeyBackup.seal(privateKey, password)
                val bundle = KeyBundle.encode(stored.identity, sealed)
                AppLog.info(
                    "key.export",
                    "size=${bundle.length} ms=${System.currentTimeMillis() - started} result=ok",
                )
                Result.success(bundle)
            } finally {
                plain.fill(0)
            }
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            PrivateKeyBackup.clear(password)
        }
    }

    suspend fun deleteIdentity(): Result<Unit> = mutate {
        val started = System.currentTimeMillis()
        if (!identityFile.exists()) throw KeyOperationException(KeyError.NO_IDENTITY)
        if (!identityFile.delete()) throw KeyOperationException(KeyError.DELETE_FAILED)
        AppLog.info("key.delete", "ms=${System.currentTimeMillis() - started} result=ok")
    }

    suspend fun addContact(
        name: String,
        publicKeyText: String,
    ): Result<ContactKey> = mutate {
        val clean = KeyFileCodec.cleanName(name)
        val publicKey = runCatching { PrivateKeyBackup.decodePublicKey(publicKeyText) }
            .getOrElse { throw KeyOperationException(KeyError.PUBLIC_KEY_INVALID, it.message, it) }
        val size = runCatching { PrivateKeyBackup.keySize(publicKey) }
            .getOrElse { throw KeyOperationException(KeyError.RSA_ONLY, it.message, it) }
        if (size < MIN_KEY_SIZE) {
            throw KeyOperationException(KeyError.PUBLIC_KEY_SHORT, "size=$size")
        }
        val fingerprint = PrivateKeyBackup.fingerprint(publicKey)
        val current = _snapshot.value
        if (current.identity?.fingerprint == fingerprint) {
            throw KeyOperationException(KeyError.OWN_KEY)
        }
        if (current.contacts.any { it.fingerprint == fingerprint }) {
            throw KeyOperationException(KeyError.DUPLICATE_CONTACT)
        }

        val contact = ContactKey(
            id = fingerprint,
            name = clean,
            publicKeyBase64 = PrivateKeyBackup.encodePublicKey(publicKey),
            fingerprint = fingerprint,
            addedAt = System.currentTimeMillis(),
        )
        writeContacts(current.contacts + contact)
        AppLog.debug("contact.add", "op=add")
        contact
    }

    suspend fun removeContact(id: String): Result<Unit> = mutate {
        val current = _snapshot.value
        val remaining = current.contacts.filterNot { it.id == id }
        if (remaining.size == current.contacts.size) {
            throw KeyOperationException(KeyError.CONTACT_MISSING)
        }
        writeContacts(remaining)
        AppLog.debug("contact.remove", "op=remove")
    }

    private suspend fun <T> mutate(block: () -> T): Result<T> = withContext(dispatcher) {
        mutex.withLock {
            try {
                val value = block()
                _snapshot.value = loadSnapshot()
                _error.value = null
                Result.success(value)
            } catch (error: Exception) {
                AppLog.error("keys.mutate", error, "kind=key")
                Result.failure(error)
            }
        }
    }

    private fun writeIdentity(stored: StoredIdentity) {
        writeFile(identityFile, KeyFileCodec.encodeIdentity(stored))
    }

    private fun writeContacts(contacts: List<ContactKey>) {
        writeFile(contactsFile, KeyFileCodec.encodeContacts(contacts))
    }

    private fun writeFile(file: File, text: String) {
        directory.mkdirs()
        val temp = File(directory, file.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readIdentity(): StoredIdentity? {
        if (!identityFile.isFile) return null
        return try {
            KeyFileCodec.decodeIdentity(identityFile.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            AppLog.warn("key.read", "kind=key_decode class=${error.javaClass.simpleName}")
            _error.value = KeyError.IDENTITY_FILE_READ
            null
        }
    }

    private fun loadSnapshot(): KeySnapshot {
        val identity = readIdentity()?.identity
        val contacts = if (!contactsFile.isFile) {
            emptyList()
        } else {
            try {
                KeyFileCodec.decodeContacts(contactsFile.readText(Charsets.UTF_8))
            } catch (error: Exception) {
                AppLog.warn("contact.read", "kind=contact_decode class=${error.javaClass.simpleName}")
                _error.value = KeyError.CONTACTS_FILE_READ
                emptyList()
            }
        }
        return KeySnapshot(identity, contacts)
    }

    companion object {
        const val MIN_KEY_SIZE = 2048
        const val DEFAULT_KEY_SIZE = 4096
    }
}
