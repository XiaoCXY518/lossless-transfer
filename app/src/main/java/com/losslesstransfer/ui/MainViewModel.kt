// path: app/src/main/java/com/losslesstransfer/ui/MainViewModel.kt
package com.losslesstransfer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.losslesstransfer.LogBus
import com.losslesstransfer.data.ConfigRepository
import com.losslesstransfer.model.HttpDownloadProgress
import com.losslesstransfer.model.ReceiveStatus
import com.losslesstransfer.net.HttpDownloader
import com.losslesstransfer.net.Sender
import com.losslesstransfer.service.BtForegroundService
import com.losslesstransfer.service.BtServiceState
import com.losslesstransfer.service.ReceiveForegroundService
import com.losslesstransfer.storage.StorageManager
import com.losslesstransfer.util.FileUtil
import com.losslesstransfer.util.NetworkUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UiState(
    val targetAddress: String = "",
    val downloadUrl: String = "",
    val saveDirLabel: String = "",
    val autoCleanup: Boolean = false,
    val progressPercent: Int = -1,
    val progressIndeterminate: Boolean = false,
    val progressLabel: String = "",
    val lanIp: String = "",
    val receivePort: Int = 0,
    val receiveRunning: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val configRepo = ConfigRepository(app)
    private val storageManager = StorageManager(app)
    private val sender = Sender()
    private val downloader = HttpDownloader()

    val logs: StateFlow<List<String>> = LogBus.logs
    val btStatus: StateFlow<BtServiceState> = BtForegroundService.status

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    init {
        val cfg = configRepo.load()
        _ui.value = _ui.value.copy(
            saveDirLabel = cfg.saveDir.ifBlank { storageManager.ensureDefaultDir().absolutePath },
            autoCleanup = cfg.autoCleanup,
            lanIp = NetworkUtil.getLanIp(),
        )
        // 接收服务状态
        viewModelScope.launch {
            ReceiveForegroundService.status.collect { s: ReceiveStatus ->
                _ui.value = _ui.value.copy(
                    receiveRunning = s.running,
                    receivePort = s.port,
                    lanIp = s.lanIp.ifBlank { NetworkUtil.getLanIp() },
                )
            }
        }
        // BT 状态
        viewModelScope.launch {
            btStatus.collect { s ->
                when {
                    s.running -> _ui.value = _ui.value.copy(
                        progressPercent = s.progress.percent.toInt().coerceIn(0, 100),
                        progressIndeterminate = false,
                        progressLabel = "${s.progress.phase.label}  ${"%.1f".format(s.progress.percent)}%  Peer ${s.progress.peers}",
                    )
                    s.finished -> _ui.value = _ui.value.copy(
                        progressPercent = 100,
                        progressIndeterminate = false,
                        progressLabel = "完成",
                    )
                    s.failed -> _ui.value = _ui.value.copy(
                        progressPercent = -1,
                        progressIndeterminate = false,
                        progressLabel = s.message.ifBlank { "失败" },
                    )
                }
            }
        }
    }

    fun updateTarget(v: String) { _ui.value = _ui.value.copy(targetAddress = v) }
    fun updateUrl(v: String) { _ui.value = _ui.value.copy(downloadUrl = v) }

    fun updateAutoCleanup(v: Boolean) {
        _ui.value = _ui.value.copy(autoCleanup = v)
        val cfg = configRepo.load()
        configRepo.save(cfg.copy(autoCleanup = v))
    }

    fun applySaveDirUri(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) { }
        val cfg = configRepo.load()
        configRepo.save(cfg.copy(saveDir = uri.toString()))
        _ui.value = _ui.value.copy(saveDirLabel = uri.toString())
    }

    fun check() {
        LogBus.append("本机局域网 IP：${NetworkUtil.getLanIp()}")
        val rec = ReceiveForegroundService.status.value
        if (rec.running) {
            LogBus.append("接收端口：${rec.port}   接收目录：${rec.saveDir}")
            LogBus.append("其它设备浏览器访问：http://${rec.lanIp}:${rec.port}/")
        } else {
            LogBus.append("接收服务未启动")
        }
        val target = _ui.value.targetAddress.trim()
        if (target.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val (host, port) = Sender.parseTarget(target)
            try {
                val url = HttpUrl.Builder()
                    .scheme("http").host(host).port(port).addPathSegment("ping").build()
                val client = OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val body = r.body?.string()?.trim()
                    if (r.isSuccessful && body == "pong") {
                        LogBus.append("目标 $host:$port 在线，可以传输 ✔")
                    } else {
                        LogBus.append("目标 $host:$port 响应异常：HTTP ${r.code}")
                    }
                }
            } catch (e: Exception) {
                LogBus.append("目标 $host:$port 连接失败：${e.message}")
            }
        }
    }

    fun toggleReceive() {
        val ctx = getApplication<Application>()
        if (ReceiveForegroundService.status.value.running) {
            ReceiveForegroundService.stop(ctx)
        } else {
            ReceiveForegroundService.start(ctx)
        }
    }

    fun sendFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val target = _ui.value.targetAddress.trim()
        if (target.isEmpty()) {
            LogBus.append("[发送] 请先填写目标地址")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val (host, port) = Sender.parseTarget(target)
            val ctx = getApplication<Application>()
            val tempDir = File(ctx.cacheDir, "upload").apply { mkdirs() }
            val pairs = mutableListOf<Pair<File, String>>()
            for ((i, uri) in uris.withIndex()) {
                val name = queryDisplayName(uri) ?: "file_$i"
                val tmp = File(tempDir, "${System.nanoTime()}_$name")
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        FileOutputStream(tmp).use { outs -> ins.copyTo(outs) }
                    }
                    pairs += tmp to name
                } catch (e: Exception) {
                    LogBus.append("[发送] 准备 $name 失败：${e.message}")
                }
            }
            sender.sendFiles(host, port, pairs)
            pairs.forEach { it.first.delete() }
        }
    }

    fun sendFolderTree(treeUri: Uri) {
        val target = _ui.value.targetAddress.trim()
        if (target.isEmpty()) {
            LogBus.append("[发送] 请先填写目标地址")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val (host, port) = Sender.parseTarget(target)
            val ctx = getApplication<Application>()
            val root = DocumentFile.fromTreeUri(ctx, treeUri)
            if (root == null) {
                LogBus.append("[发送] 无法解析目录 URI")
                return@launch
            }
            val rootName = root.name ?: "folder"
            val tempDir = File(ctx.cacheDir, "upload_folder").apply { mkdirs() }
            val pairs = mutableListOf<Pair<File, String>>()

            fun walk(node: DocumentFile, rel: String) {
                for (c in node.listFiles()) {
                    val n = c.name ?: continue
                    val childRel = "$rel/$n"
                    when {
                        c.isDirectory -> walk(c, childRel)
                        c.isFile -> {
                            val tmp = File(tempDir, "${System.nanoTime()}_$n")
                            try {
                                ctx.contentResolver.openInputStream(c.uri)?.use { ins ->
                                    FileOutputStream(tmp).use { outs -> ins.copyTo(outs) }
                                }
                                pairs += tmp to childRel.trimStart('/')
                            } catch (e: Exception) {
                                LogBus.append("[发送] 准备 $childRel 失败：${e.message}")
                            }
                        }
                    }
                }
            }
            walk(root, rootName)
            sender.sendFiles(host, port, pairs)
            pairs.forEach { it.first.delete() }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver
            .query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
    } catch (_: Exception) { null }

    fun download() {
        val src = _ui.value.downloadUrl.trim()
        if (src.isEmpty()) {
            LogBus.append("[下载] 请填写下载地址")
            return
        }
        val lower = src.lowercase()
        val isMagnet = lower.startsWith("magnet:")
        val isRemoteTorrent = (lower.startsWith("http://") || lower.startsWith("https://")) &&
                (lower.endsWith(".torrent") || lower.contains("do=download") || lower.contains("byrutgame.org"))
        val isLocalTorrent = lower.endsWith(".torrent") && File(src).isFile

        if (isMagnet || isRemoteTorrent || isLocalTorrent) {
            val saveDir = currentSaveDirFile()
            _ui.value = _ui.value.copy(
                progressPercent = 0,
                progressIndeterminate = true,
                progressLabel = "BT 准备中…",
            )
            BtForegroundService.start(getApplication(), src, saveDir)
            return
        }

        val url = if (!lower.startsWith("http://") && !lower.startsWith("https://")) "http://$src" else src
        _ui.value = _ui.value.copy(progressPercent = 0, progressIndeterminate = false, progressLabel = "准备中…")
        viewModelScope.launch {
            try {
                val file = downloader.download(url, currentSaveDirFile()) { p: HttpDownloadProgress ->
                    _ui.value = _ui.value.copy(
                        progressPercent = p.percent,
                        progressLabel = "${FileUtil.humanSize(p.speedBytesPerSec)}/s",
                    )
                }
                _ui.value = _ui.value.copy(progressPercent = 100, progressLabel = "完成")
                LogBus.append("[下载] 完成：${file.absolutePath}")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(progressPercent = -1, progressLabel = "失败")
                LogBus.append("[下载] 失败：${e.message}")
            }
        }
    }

    private fun currentSaveDirFile(): File {
        val cfg = configRepo.load()
        return if (cfg.saveDir.isBlank() || cfg.saveDir.startsWith("content://")) {
            storageManager.ensureDefaultDir()
        } else {
            File(cfg.saveDir).apply { mkdirs() }
        }
    }

    fun cleanupReceivedFilesOnExit() {
        if (!_ui.value.autoCleanup) return
        val files = ReceiveForegroundService.receivedFilesSnapshot()
        ReceiveForegroundService.clearReceivedFiles()
        for (p in files) {
            try {
                if (p.startsWith("content://")) {
                    // SAF 文件：忽略，避免误删；TODO 后续可解析 DocumentFile 删除
                } else {
                    File(p).delete()
                }
            } catch (_: Exception) { }
        }
        LogBus.append("[清理] 已删除本次接收文件 ${files.size} 个")
    }
}