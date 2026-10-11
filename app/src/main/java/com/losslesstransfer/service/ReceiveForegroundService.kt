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
import com.losslesstransfer.data.ConfigRepository
import com.losslesstransfer.model.ReceiveStatus
import com.losslesstransfer.net.HttpReceiveServer
import com.losslesstransfer.net.ReceiveDir
import com.losslesstransfer.storage.StorageManager
import com.losslesstransfer.util.NetworkUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class ReceiveForegroundService : Service() {

    private var server: HttpReceiveServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(NOTIFICATION_ID, buildNotification("接收服务启动中…"))
        if (server == null) {
            startServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        val storageManager = StorageManager(this)
        val configRepo = ConfigRepository(this)
        val receiveDir = ReceiveDir.resolve(storageManager, configRepo.load().saveDir)

        try {
            val srv = HttpReceiveServer.start(storageManager, receiveDir) { identifier ->
                receivedFiles.add(identifier)
            }
            server = srv
            val ip = NetworkUtil.getLanIp()
            _status.value = ReceiveStatus(
                running = true,
                port = srv.port,
                lanIp = ip,
                saveDir = receiveDir.displayName,
            )
            updateNotification(buildNotification("接收服务运行中 · 端口 ${srv.port}"))
            LogBus.append("接收服务已启动，端口 ${srv.port}")
            LogBus.append("其它设备浏览器访问：http://$ip:${srv.port}/")
            LogBus.append("接收目录：${receiveDir.displayName}")
        } catch (e: IOException) {
            LogBus.append("接收服务启动失败：${e.message}")
            stopSelf()
        }
    }

    private fun stopServer() {
        server?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                LogBus.append("停止接收服务异常：${e.message}")
            }
        }
        server = null
        _status.value = ReceiveStatus(running = false)
        LogBus.append("接收服务已停止")
    }

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
                getString(R.string.channel_receive_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_receive_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_receive)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "receive_service_channel"
        private const val NOTIFICATION_ID = 1001

        private val _status = MutableStateFlow(ReceiveStatus())
        val status: StateFlow<ReceiveStatus> = _status

        private val receivedFiles = CopyOnWriteArrayList<String>()

        fun receivedFilesSnapshot(): List<String> = receivedFiles.toList()

        fun clearReceivedFiles() {
            receivedFiles.clear()
        }

        fun start(context: Context) {
            val intent = Intent(context, ReceiveForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiveForegroundService::class.java))
        }
    }
}
