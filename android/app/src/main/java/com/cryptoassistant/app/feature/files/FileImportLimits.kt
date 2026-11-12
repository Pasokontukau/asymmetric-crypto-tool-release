package com.cryptoassistant.app.feature.files

/**
 * 外部分享/「用其他应用打开」一次最多接收多少文件。
 *
 * <p>与目录递归使用同一组上限（200 个文件、已知总大小 4 GiB），但这里是分享入口，
 * 解析层和 ViewModel 层都要检查，避免绕过 UI 直接把超大列表塞进队列。</p>
 */
object FileImportLimits {

    const val MAX_FILES = 200
    const val MAX_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L

    fun isTooManyFiles(count: Int): Boolean = count > MAX_FILES

    /** 大小未知（null/负数）时不参与判断，交给单文件大小预检/分卷分流。 */
    fun exceedsTotalBytes(knownBytes: Long, nextSize: Long?): Boolean {
        if (nextSize == null || nextSize < 0) return false
        if (knownBytes > MAX_TOTAL_BYTES) return true
        return nextSize > MAX_TOTAL_BYTES - knownBytes
    }
}
