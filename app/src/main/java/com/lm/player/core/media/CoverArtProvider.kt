package com.lm.player.core.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * 第二十四节：只读封面提供组件，地址 `content://<包名>.coverart/art/<md5>.jpg`。
 *
 * - 只提供 [SessionArtworkStore] 已生成 (或正在生成) 的缩略封面，文件名必须是 32 位 md5 + .jpg；
 * - 只读，不支持写入/插入/删除；
 * - 不接受任意网址，不会替其他 App 访问服务器，不会暴露登录凭证。
 */
class CoverArtProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun nameOf(uri: Uri): String? {
        val segs = uri.pathSegments
        if (segs.size != 2 || segs[0] != SessionArtworkStore.PATH_PREFIX) return null
        return segs[1].takeIf { SessionArtworkStore.isValidName(it) }
    }

    override fun getType(uri: Uri): String? = if (nameOf(uri) != null) "image/jpeg" else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        val ctx = context ?: throw FileNotFoundException(uri.toString())
        val name = nameOf(uri) ?: throw FileNotFoundException(uri.toString())
        val file = SessionArtworkStore.awaitFile(ctx, name) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val name = nameOf(uri) ?: return null
        val file = SessionArtworkStore.awaitFile(ctx, name) ?: return null
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?.toTypedArray()?.takeIf { it.isNotEmpty() }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) file.length() else name })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
