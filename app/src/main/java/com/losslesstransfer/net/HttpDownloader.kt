package com.losslesstransfer.net

import android.net.Uri
import com.losslesstransfer.LogBus
import com.losslesstransfer.model.HttpDownloadProgress
import com.losslesstransfer.storage.PathUtil
import com.losslesstransfer.util.FileUtil
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpDownloader(private val client: OkHttpClient = defaultClient()) {

    suspend fun download(
        url: String,
        saveDir: File,
        onProgress: (HttpDownloadProgress) -> Unit = {},
    ): File {
        LogBus.append("[下载] $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        val call = client.newCall(request)

        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isActive) {
                        response.close()
                        return
                    }
                    try {
                        val file = response.use { resp ->
                            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                            if (!saveDir.exists()) saveDir.mkdirs()

                            val finalUrl = resp.request.url.toString()
                            val name = PathUtil.sanitizeName(
                                guessFilename(
                                    finalUrl,
                                    resp.header("Content-Disposition"),
                                    resp.header("Content-Type"),
                                )
                            )
                            val target = PathUtil.uniqueFile(File(saveDir, name))

                            val body = resp.body ?: throw IOException("响应体为空")
                            val total = body.contentLength()
                            var downloaded = 0L
                            val start = System.currentTimeMillis()

                            FileOutputStream(target).use { out ->
                                body.byteStream().use { input ->
                                    val buf = ByteArray(BUFFER_SIZE)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        downloaded += n
                                        val elapsed = (System.currentTimeMillis() - start) / 1000.0
                                        val speed = if (elapsed > 0.0) (downloaded / elapsed).toLong() else 0L
                                        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                                        onProgress(HttpDownloadProgress(percent, speed, downloaded, total))
                                    }
                                    out.flush()
                                }
                            }

                            LogBus.append("[下载] ✔ 已保存：${target.absolutePath}")
                            LogBus.append("          大小 ${FileUtil.humanSize(downloaded)}")
                            target
                        }
                        cont.resume(file)
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
            })
        }
    }

    private fun guessFilename(url: String, contentDisposition: String?, contentType: String?): String {
        var name = parseContentDisposition(contentDisposition) ?: guessFromUrl(url) ?: "download"
        if (!name.contains('.')) {
            FileUtil.extensionFromContentType(contentType)?.let { name += it }
        }
        return name
    }

    private fun parseContentDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            var v = m.groupValues[1].trim().trim('"')
            val idx = v.indexOf("''")
            v = if (idx >= 0) v.substring(idx + 2) else v
            return Uri.decode(v) ?: v
        }
        Regex("""filename\s*=\s*"([^"]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            return m.groupValues[1]
        }
        Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            return m.groupValues[1].trim().trim('"')
        }
        return null
    }

    private fun guessFromUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#')
        val seg = path.substringAfterLast('/')
        if (seg.isBlank()) return null
        return Uri.decode(seg) ?: seg
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
