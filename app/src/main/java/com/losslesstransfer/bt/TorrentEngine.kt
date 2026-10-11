// path: app/src/main/java/com/losslesstransfer/bt/TorrentEngine.kt
package com.losslesstransfer.bt

import com.losslesstransfer.model.BtPhase
import com.losslesstransfer.model.TransferProgress
import com.losslesstransfer.storage.PathUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TorrentEngine(
    private val cacheDir: File,
    private val onProgress: (TransferProgress) -> Unit,
    private val onLog: (String) -> Unit,
    private val onFinished: (Boolean, String) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handleRef = AtomicReference<TorrentHandle?>(null)
    private val tempFiles = CopyOnWriteArrayList<File>()

    @Volatile
    private var sessionManager: SessionManager? = null

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val addTorrentListener = object : AlertListener {
        override fun types(): IntArray? = intArrayOf(AlertType.ADD_TORRENT.swig())
        override fun alert(alert: Alert<*>) {
            if (alert is AddTorrentAlert) {
                handleRef.compareAndSet(null, alert.handle())
            }
        }
    }

    fun addTorrent(source: String, saveDir: File) {
        scope.launch {
            run(source, saveDir)
        }
    }

    fun pause() {
        try {
            handleRef.get()?.pause()
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun resume() {
        try {
            handleRef.get()?.resume()
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun stop() {
        scope.cancel(CancellationException("BT engine stopped"))
        releaseSession()
        cleanupTempFiles()
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    private suspend fun run(source: String, baseSaveDir: File) {
        val startTime = System.currentTimeMillis()
        onLog("BT 下载开始，来源：${source.take(80)}...")
        try {
            if (!baseSaveDir.exists()) baseSaveDir.mkdirs()

            val resolved = resolveSource(source)

            val sm = createSession()
            sessionManager = sm
            sm.addListener(addTorrentListener)

            var isMagnet = false
            if (resolved is ResolvedTorrent) {
                val safeName = PathUtil.sanitizeName(resolved.info.name())
                val sub = File(baseSaveDir, safeName)
                sub.mkdirs()
                sm.download(resolved.info, sub)
                onLog("种子名称：${resolved.info.name()}，文件数：${resolved.info.numFiles()}")
                onLog("保存目录：${sub.absolutePath}")
            } else if (resolved is ResolvedMagnet) {
                isMagnet = true
                sm.download(resolved.uri, baseSaveDir, torrent_flags_t())
                onLog("磁力链已添加，正在获取元数据...")
            }

            val handle = waitForHandle(startTime)
            if (handle == null) {
                onLog("✗ 添加种子失败（未获得下载句柄）")
                onFinished(false, "添加种子失败")
                return
            }

            pollLoop(handle, baseSaveDir, isMagnet, startTime)
        } catch (e: CancellationException) {
            releaseSession()
            throw e
        } catch (e: Exception) {
            onLog("✗ BT 下载失败：${e.message}")
            onFinished(false, "下载失败")
        }
    }

    // ==================================================================
    // 来源解析
    // ==================================================================

    private sealed class Resolved
    private class ResolvedTorrent(val info: TorrentInfo) : Resolved()
    private class ResolvedMagnet(val uri: String) : Resolved()

    private fun resolveSource(source: String): Resolved {
        val lower = source.lowercase(Locale.US)
        return when {
            lower.startsWith("magnet:") -> ResolvedMagnet(source)
            lower.startsWith("http://") || lower.startsWith("https://") -> {
                onLog("正在下载种子文件：${source.take(80)}...")
                val file = downloadRemoteTorrent(source)
                try {
                    ResolvedTorrent(TorrentInfo(file))
                } catch (e: IllegalArgumentException) {
                    throw IOException("链接返回的不是有效的种子文件（可能是网页）")
                }
            }
            else -> {
                val f = File(source)
                if (!f.isFile) throw IOException("种子文件不存在：$source")
                ResolvedTorrent(TorrentInfo(f))
            }
        }
    }

    private fun downloadRemoteTorrent(url: String): File {
        val dir = File(cacheDir, "bt")
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "temp-${System.currentTimeMillis()}.torrent")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应体为空")
            FileOutputStream(tmp).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        tempFiles.add(tmp)
        onLog("种子已保存到：${tmp.absolutePath}")
        return tmp
    }

    // ==================================================================
    // 会话
    // ==================================================================

    private fun createSession(): SessionManager {
        val sp = SettingsPack()
        sp.listenInterfaces("0.0.0.0:6881")
        sp.setEnableDht(true)
        sp.setEnableLsd(true)
        sp.downloadRateLimit(0)
        sp.uploadRateLimit(0)
        // TODO(阶段5): SettingsPack 未找到 upnp/natpmp 的确认 setter；SessionParams 文档说明默认设置
        // TODO(阶段5): 已启用 upnp、nat-pmp、dht，因此此处不显式设置。
        // TODO(阶段5): 若 Android 设备 mmap 异常，可改用 SessionParams.setPosixDiskIO()（官方注释建议）。
        val sm = SessionManager()
        sm.start(SessionParams(sp))
        return sm
    }

    private suspend fun waitForHandle(startTime: Long): TorrentHandle? {
        while (System.currentTimeMillis() - startTime < ADD_TIMEOUT_MS) {
            handleRef.get()?.let { if (it.isValid()) return it }
            delay(200)
        }
        return null
    }

    // ==================================================================
    // 轮询循环
    // ==================================================================

    private suspend fun pollLoop(handle: TorrentHandle, baseDir: File, isMagnet: Boolean, startTime: Long) {
        var lastLog = 0L
        var lastState: TorrentStatus.State? = null
        var peerZeroSince = System.currentTimeMillis()
        var peerZeroWarned = false
        var metadataHandled = !isMagnet

        while (true) {
            val now = System.currentTimeMillis()

            val ts = try {
                handle.status()
            } catch (e: Exception) {
                delay(500)
                continue
            }

            // 磁力：元数据就绪后移动到独立子目录
            if (isMagnet && !metadataHandled && ts.hasMetadata()) {
                metadataHandled = true
                val name = PathUtil.sanitizeName(ts.name().ifBlank { "torrent" })
                val sub = File(baseDir, name)
                sub.mkdirs()
                try {
                    handle.moveStorage(sub.absolutePath)
                    onLog("种子名称：$name")
                    onLog("保存目录：${sub.absolutePath}")
                } catch (e: Exception) {
                    onLog("移动到独立子目录失败：${e.message}，将继续保存到接收目录")
                }
            }

            val state = ts.state()
            val pct = ts.progress() * 100f
            val speedMbps = ts.downloadRate().toFloat() / 1024f / 1024f
            val peers = ts.numPeers()
            val doneMb = ts.totalDone().toFloat() / 1024f / 1024f
            val totalMb = ts.totalWanted().toFloat() / 1024f / 1024f
            val phase = mapState(state)

            if (now - lastLog >= 1000 || state != lastState) {
                lastLog = now
                lastState = state
                onProgress(TransferProgress(pct, speedMbps, peers, phase, doneMb, totalMb))
                if (speedMbps > 0.01f || peers != 0 || state != TorrentStatus.State.DOWNLOADING) {
                    onLog(
                        "${phase.label}：${"%.1f".format(pct)}%，速度 ${"%.2f".format(speedMbps)} MB/s，" +
                            "Peer $peers，${"%.1f".format(doneMb)}/${"%.1f".format(totalMb)} MB"
                    )
                }
            }

            // 磁力元数据超时
            if (isMagnet && !metadataHandled && now - startTime > MAGNET_TIMEOUT_MS) {
                onLog("✗ 元数据获取超时")
                onFinished(false, "元数据获取超时")
                return
            }

            // 无 Peer 跟踪
            if (peers == 0 && speedMbps < 0.01f) {
                if (now - peerZeroSince > NO_PEER_WARN_MS && !peerZeroWarned) {
                    onLog("⚠ 已 60 秒无 Peer 连接：种子可能是死种，或需要在路由器做 6881 端口映射")
                    peerZeroWarned = true
                }
            } else {
                peerZeroSince = now
                peerZeroWarned = false
            }

            // 严格完成判定
            if ((state == TorrentStatus.State.FINISHED || state == TorrentStatus.State.SEEDING) && pct >= 99.9f) {
                onProgress(TransferProgress(100f, 0f, peers, BtPhase.SEEDING, totalMb, totalMb))
                onLog("BT 下载完成 ✔")
                onFinished(true, "下载完成")
                return
            }

            // 无 Peer 且进度 <10% 超过 10 分钟 → 失败
            if (peers == 0 && pct < 10f && now - peerZeroSince > NO_PEER_TIMEOUT_MS) {
                onLog("✗ 10 分钟没有任何 Peer 和进度，放弃下载")
                onFinished(false, "超时失败")
                return
            }

            delay(500)
        }
    }

    private fun mapState(state: TorrentStatus.State): BtPhase = when (state) {
        TorrentStatus.State.CHECKING_FILES -> BtPhase.CHECKING
        TorrentStatus.State.DOWNLOADING_METADATA -> BtPhase.FETCHING_METADATA
        TorrentStatus.State.DOWNLOADING -> BtPhase.DOWNLOADING
        TorrentStatus.State.FINISHED -> BtPhase.FINISHED
        TorrentStatus.State.SEEDING -> BtPhase.SEEDING
        TorrentStatus.State.CHECKING_RESUME_DATA -> BtPhase.CHECKING_RESUME
        // TODO(阶段5): libtorrent4j 的 TorrentStatus.State 枚举未暴露 queued_for_checking 与 allocating，
        // TODO(阶段5): 两者会落入 UNKNOWN；Models.kt 的 BtPhase.QUEUED / ALLOCATING 暂无法触发。
        else -> BtPhase.UNKNOWN
    }

    private fun releaseSession() {
        val sm = sessionManager
        sessionManager = null
        handleRef.set(null)
        if (sm != null) {
            // SessionManager.stop() 是阻塞调用（约 1 秒），放到后台线程销毁
            Thread({ try { sm.stop() } catch (t: Throwable) { /* ignore */ } }, "bt-engine-stop").start()
        }
    }

    private fun cleanupTempFiles() {
        val files = tempFiles.toList()
        tempFiles.clear()
        for (f in files) {
            try {
                f.delete()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val ADD_TIMEOUT_MS = 60_000L
        private const val MAGNET_TIMEOUT_MS = 300_000L
        private const val NO_PEER_WARN_MS = 60_000L
        private const val NO_PEER_TIMEOUT_MS = 600_000L
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}