// path: app/src/main/java/com/losslesstransfer/MainActivity.kt
package com.losslesstransfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.losslesstransfer.ui.MainScreen
import com.losslesstransfer.ui.MainViewModel
import com.losslesstransfer.ui.theme.LosslessTransferTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不影响主要流程 */ }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.sendFiles(uris) }

    private val folderSenderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.sendFolderTree(it) } }

    private val saveDirPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.applySaveDirUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            LosslessTransferTheme {
                MainScreen(
                    vm = vm,
                    onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                    onPickFolder = { folderSenderPicker.launch(null) },
                    onPickSaveDir = { saveDirPicker.launch(null) },
                )
            }
        }
    }

    override fun onDestroy() {
        vm.cleanupReceivedFilesOnExit()
        super.onDestroy()
    }
}