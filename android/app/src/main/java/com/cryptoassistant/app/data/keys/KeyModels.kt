package com.cryptoassistant.app.data.keys

/**
 * 本机身份密钥（公钥部分可安全展示；私钥只以 Keystore 包装后的形式落盘）。
 */
data class IdentityKey(
    val displayName: String,
    val publicKeyBase64: String,
    val fingerprint: String,
    val keySize: Int,
    /**
     * 原始创建时间；当前完整备份一定携带，[UNKNOWN_CREATED_AT] 仅作防御哨兵。
     * 只用于显示，不参与任何密码学校验。
     */
    val createdAt: Long,
    /** 最近一次从备份导入到本机的时间；本地事件，不写入 [KeyBundle]。 */
    val lastImportedAt: Long? = null,
) {
    val createdAtKnown: Boolean get() = createdAt > UNKNOWN_CREATED_AT

    companion object {
        /** 0 是“未知创建时间”哨兵，不是 Unix 时间。 */
        const val UNKNOWN_CREATED_AT = 0L
    }
}

/** 把备份元数据映射成本机身份，便于 JVM 测试且避免两处写法不一致。 */
object KeyImportMetadata {
    fun importedIdentity(
        displayName: String,
        backupCreatedAt: Long,
        publicKeyBase64: String,
        fingerprint: String,
        keySize: Int,
        importedAt: Long,
    ): IdentityKey = IdentityKey(
        displayName = displayName,
        publicKeyBase64 = publicKeyBase64,
        fingerprint = fingerprint,
        keySize = keySize,
        createdAt = backupCreatedAt.takeIf { it > IdentityKey.UNKNOWN_CREATED_AT }
            ?: IdentityKey.UNKNOWN_CREATED_AT,
        lastImportedAt = importedAt,
    )
}

/**
 * 联系人保存的是对方公钥与指纹。指纹用于人工核对，避免同名误选。
 */
data class ContactKey(
    val id: String,
    val name: String,
    val publicKeyBase64: String,
    val fingerprint: String,
    val addedAt: Long,
)

data class KeySnapshot(
    val identity: IdentityKey? = null,
    val contacts: List<ContactKey> = emptyList(),
) {
    val canEncrypt: Boolean get() = identity != null
}

/** 身份密钥文件解码结果：身份信息 + Keystore 包装后的私钥。 */
data class StoredIdentity(
    val identity: IdentityKey,
    val wrappedPrivateKeyBase64: String,
)
