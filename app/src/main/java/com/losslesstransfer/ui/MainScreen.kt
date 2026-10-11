// path: app/src/main/java/com/losslesstransfer/ui/MainScreen.kt
package com.losslesstransfer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MainScreen(
    vm: MainViewModel,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onPickSaveDir: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val bt by vm.btStatus.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("无损传输", style = MaterialTheme.typography.headlineSmall)

        // 状态区
        Card {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val recLine = if (ui.receiveRunning)
                    "接收服务：已开启  http://${ui.lanIp}:${ui.receivePort}/"
                else "接收服务：未开启"
                Text(recLine, fontSize = 13.sp)
                Text("本机 IP：${ui.lanIp}", fontSize = 13.sp)
                Text("接收目录：${ui.saveDirLabel}", fontSize = 12.sp, maxLines = 2)
            }
        }

        // 传输区
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ui.targetAddress,
                onValueChange = vm::updateTarget,
                label = { Text("目标地址") },
                placeholder = { Text("192.168.1.5:8765") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = { vm.check() }, modifier = Modifier.align(Alignment.CenterVertically)) {
                Text("检查")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickFiles, modifier = Modifier.weight(1f)) { Text("传输文件") }
            Button(onClick = onPickFolder, modifier = Modifier.weight(1f)) { Text("传输文件夹") }
            Button(
                onClick = { vm.toggleReceive() },
                modifier = Modifier.weight(1f),
            ) { Text(if (ui.receiveRunning) "停止接收" else "启动接收") }
        }

        // 下载区
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ui.downloadUrl,
                onValueChange = vm::updateUrl,
                label = { Text("下载地址") },
                placeholder = { Text("https://... 或 magnet:... 或 D:\\a.torrent") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = { vm.download() }, modifier = Modifier.align(Alignment.CenterVertically)) {
                Text("下载")
            }
        }

        // BT 后端状态
        Text(
            "BT 下载：可用（后端 libtorrent4j）",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
        )

        // 接收目录选择
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPickSaveDir) { Text("接收目录") }
            Text(ui.saveDirLabel, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
        }

        // 清理复选框
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = ui.autoCleanup,
                onCheckedChange = { vm.updateAutoCleanup(it) },
            )
            Text("关闭程序时清空本次接收到的文件", fontSize = 13.sp)
        }

        // 进度条
        if (ui.progressPercent >= 0 || ui.progressIndeterminate) {
            if (ui.progressIndeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { ui.progressPercent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(ui.progressLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }

        // 日志区
        Text("日志", style = MaterialTheme.typography.titleSmall)
        Card {
            Text(
                text = logs.takeLast(200).joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 320.dp)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}