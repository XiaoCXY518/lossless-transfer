// path: app/src/main/java/com/losslesstransfer/net/Sender.kt
package com.losslesstransfer.net

import com.losslesstransfer.LogBus
import com.losslesstransfer.model.SendSummary
import com.losslesstransfer.util.FileUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Sender(private val client: OkHttpClient = defaultClient()) {

    suspend fun sendFiles(host: String, port: Int, pairs: List<Pair<File, String>>): SendSummary =
        sendItems(host, port, pairs)

    suspend fun sendFolder(host: String, port: Int, folder: File): SendSummary {
        val base = folder.parentFile ?: folder
        val pairs = FileUtil.listRelativeFiles(folder, base).map { rel -> File(base, rel) to rel }
        return sendItems(host, port, pairs)
    }

    private suspend fun sendItems(host: String, port: Int, pairs: List<Pair<File, String>>): SendSummary {
        val total = pairs.size
        var ok = 0
        LogBus.append("[发送] 开始 → $host:$port，共 $total 个文件")
        val start = System.currentTimeMillis()
        pairs.forEachIndexed { index, (file, rel) ->
            try {
                LogBus.append("          (${index + 1}/$total) $rel  ${FileUtil.humanSize(file.length())}")
                putFile(host, port, file, rel)
                ok++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.append("          ✗ 失败：$rel -> ${e.message}")
            }
        }
        val elapsed = (System.currentTimeMillis() - start) / 1000.0
        LogBus.append("[发送] 结束：成功 $ok/$total，失败 ${total - ok}，耗时 ${"%.1f".format(elapsed)}s")
        return SendSummary(total = total, successCount = ok, failCount = total - ok)
    }

    private suspend fun putFile(host: String, port: Int, file: File, rel: String) {
        val url = HttpUrl.Builder()
            .scheme("http")
            .host(host)
            .port(port)
            .addPathSegment("upload")
            .addQueryParameter("path", rel)
            .build()

        val body = object : RequestBody() {
            override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength(): Long = file.length()
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                    }
                }
            }
        }

        val call = client.newCall(Request.Builder().url(url).put(body).build())
        execute(call).use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
        }
    }

    private suspend fun execute(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_PORT = 8765

        fun parseTarget(text: String, defaultPort: Int = DEFAULT_PORT): Pair<String, Int> {
            var t = text.trim()
            if (t.startsWith("https://", ignoreCase = true)) {
                t = t.substring(8)
            } else if (t.startsWith("http://", ignoreCase = true)) {
                t = t.substring(7)
            }
            t = t.trimEnd('/')
            val host: String
            val port: Int
            if (t.count { it == ':' } == 1) {
                val idx = t.lastIndexOf(':')
                val portPart = t.substring(idx + 1)
                if (portPart.isNotEmpty() && portPart.all { it.isDigit() }) {
                    host = t.substring(0, idx).trim()
                    port = portPart.toInt()
                } else {
                    host = t.trim()
                    port = defaultPort
                }
            } else {
                host = t.trim()
                port = defaultPort
            }
            return host to port
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .readTimeout(10, TimeUnit.MINUTES)
            .build()
    }
}