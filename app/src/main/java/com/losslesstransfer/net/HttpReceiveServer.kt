package com.losslesstransfer.net

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.losslesstransfer.LogBus
import com.losslesstransfer.storage.PathUtil
import com.losslesstransfer.storage.StorageManager
import com.losslesstransfer.util.FileUtil
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder

private const val BUFFER_SIZE = 64 * 1024
private const val MIME_PLAINTEXT = "text/plain; charset=utf-8"
private const val MIME_HTML = "text/html; charset=utf-8"
private const val MIME_JSON = "application/json; charset=utf-8"
private const val MIME_OCTET_STREAM = "application/octet-stream"

data class ReceiveEntry(val relPath: String, val size: Long)

class DownloadHandle(val stream: InputStream, val size: Long, val name: String)

class UploadHandle(
    val stream: OutputStream,
    val savedRelPath: String,
    val savedIdentifier: String,
)

/**
 * 接收目录抽象：统一 app-specific File 与 SAF 目录树两种后端。
 * 所有 SAF 读写均通过 StorageManager/ContentResolver，不把 Uri 当 File。
 */
class ReceiveDir private constructor(
    private val storage: StorageManager,
    private val baseFile: File?,
    private val treeUri: Uri?,
    val displayName: String,
) {

    val isSaf: Boolean get() = treeUri != null

    companion object {
        fun resolve(storage: StorageManager, configSaveDir: String): ReceiveDir {
            if (configSaveDir.isBlank()) {
                val dir = storage.ensureDefaultDir()
                return ReceiveDir(storage, dir, null, dir.absolutePath)
            }
            return if (configSaveDir.startsWith("content://")) {
                ReceiveDir(storage, null, Uri.parse(configSaveDir), configSaveDir)
            } else {
                val f = File(configSaveDir)
                ReceiveDir(storage, f, null, f.absolutePath)
            }
        }
    }

    fun list(): List<ReceiveEntry> =
        (if (treeUri != null) listSaf() else listFile()).sortedBy { it.relPath }

    private fun listFile(): List<ReceiveEntry> {
        val base = baseFile ?: return emptyList()
        if (!base.isDirectory) return emptyList()
        val result = mutableListOf<ReceiveEntry>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            children.sortBy { it.name }
            for (child in children) {
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                when {
                    child.isDirectory -> walk(child, rel)
                    child.isFile -> result += ReceiveEntry(rel, child.length())
                }
            }
        }
        walk(base, "")
        return result
    }

    private fun listSaf(): List<ReceiveEntry> {
        val root = try {
            storage.treeDocumentFile(treeUri!!)
        } catch (e: IOException) {
            return emptyList()
        }
        val result = mutableListOf<ReceiveEntry>()
        fun walk(node: DocumentFile, prefix: String) {
            for (child in node.listFiles()) {
                val name = child.name ?: continue
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                when {
                    child.isDirectory -> walk(child, rel)
                    child.isFile -> result += ReceiveEntry(rel, child.length())
                }
            }
        }
        walk(root, "")
        return result
    }

    fun download(relPath: String): DownloadHandle? =
        if (treeUri != null) downloadSaf(relPath) else downloadFile(relPath)

    private fun downloadFile(relPath: String): DownloadHandle? {
        val base = baseFile ?: return null
        val f = try {
            PathUtil.resolveSafe(base, relPath)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (!f.isFile) return null
        return try {
            DownloadHandle(FileInputStream(f), f.length(), f.name)
        } catch (e: IOException) {
            null
        }
    }

    private fun downloadSaf(relPath: String): DownloadHandle? {
        val root = try {
            storage.treeDocumentFile(treeUri!!)
        } catch (e: IOException) {
            return null
        }
        val segments = PathUtil.normalizeRelativePath(relPath).split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var node = root
        for (seg in segments) {
            node = node.findFile(seg) ?: return null
        }
        if (!node.isFile) return null
        val stream = storage.openInputStream(node.uri) ?: return null
        return DownloadHandle(stream, node.length(), node.name ?: "download")
    }

    fun upload(relPath: String): UploadHandle =
        if (treeUri != null) uploadSaf(relPath) else uploadFile(relPath)

    private fun uploadFile(relPath: String): UploadHandle {
        val base = baseFile ?: throw IOException("接收目录无效")
        if (!base.exists() && !base.mkdirs()) {
            throw IOException("无法创建接收目录")
        }
        val target = PathUtil.resolveSafe(base, relPath)
        val parent = target.parentFile ?: base
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建目录: ${parent.path}")
        }
        val unique = PathUtil.uniqueFile(target)
        val stream = FileOutputStream(unique)
        val savedRel = unique.relativeTo(base).path.replace(File.separatorChar, '/')
        return UploadHandle(stream, savedRel, unique.absolutePath)
    }

    private fun uploadSaf(relPath: String): UploadHandle {
        val root = storage.treeDocumentFile(treeUri!!)
        val segments = PathUtil.normalizeRelativePath(relPath).split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw IllegalArgumentException("非法路径: $relPath")
        val fileName = segments.last()
        val dirSegments = segments.dropLast(1)
        var dir = root
        for (seg in dirSegments) {
            dir = dir.findFile(seg) ?: dir.createDirectory(seg)
                ?: throw IOException("无法创建目录: $seg")
        }
        val finalName = PathUtil.uniqueName(fileName) { dir.findFile(it) != null }
        val doc = dir.createFile(MIME_OCTET_STREAM, finalName)
            ?: throw IOException("无法创建文件: $finalName")
        val stream = storage.openOutputStream(doc.uri)
            ?: throw IOException("无法打开输出流: ${doc.uri}")
        val savedRel = (dirSegments + finalName).joinToString("/")
        return UploadHandle(stream, savedRel, doc.uri.toString())
    }
}

class HttpReceiveServer(
    private val storageManager: StorageManager,
    private val receiveDir: ReceiveDir,
    private val bindPort: Int,
    private val onFileReceived: (String) -> Unit = {},
) : NanoHTTPD(DEFAULT_HOST, bindPort) {

    val port: Int
        get() = listeningPort

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && (session.uri == "/" || session.uri == "/index.html") ->
                    serveIndex()
                session.method == Method.GET && session.uri == "/ping" ->
                    servePing()
                session.method == Method.GET && session.uri.startsWith("/download/") ->
                    serveDownload(session.uri.removePrefix("/download/"))
                session.method == Method.PUT && session.uri.trimEnd('/') == "/upload" ->
                    serveUpload(session)
                else ->
                    newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Internal Error")
        }
    }

    private fun serveIndex(): Response {
        val rows = receiveDir.list().joinToString("\n") { entry ->
            val url = "/download/" + encodePath(entry.relPath)
            val esc = htmlEscape(entry.relPath)
            "<li><a href=\"$url\">$esc</a><span>${FileUtil.humanSize(entry.size)}</span></li>"
        }.ifEmpty { "<li>暂无文件</li>" }

        val html = RECEIVE_PAGE_HTML.replace(FILES_PLACEHOLDER, rows)
        return newFixedLengthResponse(Status.OK, MIME_HTML, html)
    }

    private fun servePing(): Response =
        newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "pong")

    private fun serveDownload(rel: String): Response {
        val handle = receiveDir.download(rel)
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")

        val encodedName = URLEncoder.encode(handle.name, "UTF-8").replace("+", "%20")
        val resp = newFixedLengthResponse(Status.OK, MIME_OCTET_STREAM, handle.stream, handle.size)
        resp.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encodedName")
        return resp
    }

    private fun serveUpload(session: IHTTPSession): Response {
        val params = session.parameters
        val rel = params["path"]?.firstOrNull()
            ?: params["name"]?.firstOrNull()
            ?: "unnamed"

        val contentLength = header(session, "content-length")?.toLongOrNull() ?: 0L

        val handle = try {
            receiveDir.upload(rel)
        } catch (e: IllegalArgumentException) {
            return newFixedLengthResponse(Status.FORBIDDEN, MIME_PLAINTEXT, e.message ?: "Forbidden")
        } catch (e: IOException) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Internal Error")
        }

        var written = 0L
        try {
            handle.stream.use { out ->
                val input = session.inputStream
                val buf = ByteArray(BUFFER_SIZE)
                var remaining = contentLength
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    remaining -= n
                }
                out.flush()
            }
        } catch (e: IOException) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Upload Failed")
        }

        onFileReceived(handle.savedIdentifier)
        LogBus.append("[接收] ${handle.savedRelPath}  (${FileUtil.humanSize(written)})")

        val body = """{"ok":true,"file":"${jsonEscape(handle.savedRelPath)}"}"""
        return newFixedLengthResponse(Status.OK, MIME_JSON, body)
    }

    private fun header(session: IHTTPSession, name: String): String? =
        session.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun encodePath(rel: String): String =
        rel.split('/').joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }

    private fun htmlEscape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        const val DEFAULT_PORT = 8765
        const val DEFAULT_HOST = "0.0.0.0"

        /** 启动服务器：先试默认端口，被占用则回退随机端口。 */
        @Throws(IOException::class)
        fun start(
            storageManager: StorageManager,
            receiveDir: ReceiveDir,
            onFileReceived: (String) -> Unit = {},
        ): HttpReceiveServer {
            return try {
                val srv = HttpReceiveServer(storageManager, receiveDir, DEFAULT_PORT, onFileReceived)
                srv.start()
                srv
            } catch (e: IOException) {
                val srv = HttpReceiveServer(storageManager, receiveDir, 0, onFileReceived)
                srv.start()
                srv
            }
        }
    }
}
