package com.cryptoassistant.app.feature.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

/**
 * 把 SAF 的目录树适配成 [DirectoryWalker] 需要的 `(parentId) -> List<DirectoryEntry>`。
 *
 * <p>只查询 DocumentsContract 暴露的 id/名称/MIME/大小，不打开文件内容。
 * FileProvider 不支持目录树，所以这里只处理系统文件选择器返回的 tree URI。</p>
 */
internal class SafDirectoryReader(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) {

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    fun rootDocumentId(): String = DocumentsContract.getTreeDocumentId(treeUri)

    /** 根目录显示名；provider 不返回时用 null，由调用方回退到时间戳根名。 */
    fun rootName(): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId())
        val cursor = resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (index < 0 || it.isNull(index)) return null
            return it.getString(index)?.trim()?.takeIf { name -> name.isNotEmpty() }
        }
    }

    fun listChildren(parentDocumentId: String): List<DirectoryEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId,
        )
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IOException("无法读取目录，访问权限可能已失效")

        cursor.use {
            val idIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
                throw IOException("这个文件提供方不支持目录浏览")
            }

            val entries = mutableListOf<DirectoryEntry>()
            while (it.moveToNext()) {
                val id = it.getString(idIndex) ?: continue
                val name = it.getString(nameIndex)?.trim().orEmpty()
                if (name.isEmpty()) continue
                val mime = it.getString(mimeIndex).orEmpty()
                entries += DirectoryEntry(
                    id = id,
                    name = name,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                        it.getLong(sizeIndex)
                    } else {
                        null
                    },
                )
            }
            return entries
        }
    }

    fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
}
