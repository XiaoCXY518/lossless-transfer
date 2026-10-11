package com.losslesstransfer.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 存储管理：默认 app-specific external 目录 + SAF/ContentResolver 流式读写封装。
 * 所有涉及 SAF Uri 的读写均通过 ContentResolver，绝不把 Uri 当 File 使用。
 */
class StorageManager(context: Context) {

    private val appContext: Context = context.applicationContext

    private val bufferSize: Int = 64 * 1024

    /** 默认接收目录：app-specific external（分区存储安全），回退内部 filesDir。 */
    val defaultDir: File = appContext.getExternalFilesDir(null) ?: appContext.filesDir

    fun ensureDefaultDir(): File {
        if (!defaultDir.exists()) {
            defaultDir.mkdirs()
        }
        return defaultDir
    }

    // ---------- SAF / ContentResolver 流式读写封装 ----------

    fun openInputStream(uri: Uri): InputStream? =
        appContext.contentResolver.openInputStream(uri)

    fun openOutputStream(uri: Uri, mode: String = "w"): OutputStream? =
        appContext.contentResolver.openOutputStream(uri, mode)

    /** 从 Uri 流式读取并写入目标流，返回总字节数。 */
    fun copyTo(uri: Uri, output: OutputStream): Long {
        val input = openInputStream(uri) ?: throw IOException("无法打开输入流: $uri")
        input.use { src ->
            var total = 0L
            val buf = ByteArray(bufferSize)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                total += n
            }
            output.flush()
            return total
        }
    }

    /** 从输入流流式写入 Uri，返回总字节数。 */
    fun copyFrom(input: InputStream, uri: Uri): Long {
        val output = openOutputStream(uri) ?: throw IOException("无法打开输出流: $uri")
        output.use { dst ->
            var total = 0L
            val buf = ByteArray(bufferSize)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                total += n
            }
            dst.flush()
            return total
        }
    }

    /** 将目录树 URI 解析为 DocumentFile，失败抛 IOException。 */
    fun treeDocumentFile(treeUri: Uri): DocumentFile =
        DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IOException("无法解析目录树 URI: $treeUri")
}
