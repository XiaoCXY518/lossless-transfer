package com.losslesstransfer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.losslesstransfer.LogBus
import com.losslesstransfer.MainActivity
import com.losslesstransfer.R
import com.losslesstransfer.bt.TorrentEngine
import com.losslesstransfer.model.TransferProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class BtServiceState(
    val running: Boolean = false,
    val progress: TransferProgress = TransferProgress(),
    val finished: Boolean = false,
    val failed: Boolean = false,
    val message: String = "",
)

class BtForegroundService : Service() {

    private var engine: TorrentEngine? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(NOTIFICATION_ID, buildNotification(0, "BT 下载准备中…"))

        val source = intent?.getStringExtra(EXTRA_SOURCE)
        val saveDir = intent?.getStringExtra(EXTRA_SAVE_DIR)

        if (source.isNullOrBlank() || saveDir.isNullOrBlank()) {
            LogBus.append("[BT] 缺少下载参数，停止服务")
            stopSelf()
            return START_NOT_STICKY
        }

        if (engine == null) {
            startEngine(source, File(saveDir))
        }
        return START_NOT_STICKY
    }

    private fun startEngine(source: String, saveDir: File) {
        val e = TorrentEngine(
            cacheDir = cacheDir,
            onProgress = { p ->
                _status.value = BtServiceState(
                    running = true,
                    progress = p,
                    finished = false,
                    failed = false,
                )
                updateNotification(
                    buildNotification(
                        p.percent.toInt(),
                        "${p.phase.label} · ${"%.1f".format(p.percent)}% · Peer ${p.peers}",
                    )
                )
            },
            onLog = { msg -> LogBus.append(msg) },
            onFinished = { ok, msg ->
                val prev = _status.value
                _status.value = BtServiceState(
                    running = false,
                    progress = prev.progress,
                    finished = ok,
                    failed = !ok,
                    message = msg,
                )
                LogBus.append("[BT] $msg")
                stopSelf()
            },
        )
        engine = e
        _status.value = BtServiceState(running = true)
        e.addTorrent(source, saveDir)
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        _status.value = _status.value.copy(running = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            startForeground(id, notification, type)
        } else {
            startForeground(id, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_bt_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_bt_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: Int, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BtForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bt)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_stat_bt, "停止", stopIntent)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "bt_service_channel"
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_SOURCE = "extra_source"
        private const val EXTRA_SAVE_DIR = "extra_save_dir"
        private const val ACTION_STOP = "com.losslesstransfer.action.STOP_BT"

        private val _status = MutableStateFlow(BtServiceState())
        val status: StateFlow<BtServiceState> = _status

        fun start(context: Context, source: String, saveDir: File) {
            val intent = Intent(context, BtForegroundService::class.java)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_SAVE_DIR, saveDir.absolutePath)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BtForegroundService::class.java))
        }
    }
}
