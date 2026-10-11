package com.losslesstransfer.data

import android.content.Context
import android.content.SharedPreferences

class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppConfig {
        val saveDir = prefs.getString(KEY_SAVE_DIR, "") ?: ""
        val autoCleanup = prefs.getBoolean(KEY_AUTO_CLEANUP, false)
        return AppConfig(saveDir = saveDir, autoCleanup = autoCleanup)
    }

    fun save(config: AppConfig) {
        prefs.edit()
            .putString(KEY_SAVE_DIR, config.saveDir)
            .putBoolean(KEY_AUTO_CLEANUP, config.autoCleanup)
            .apply()
    }

    fun saveSaveDir(saveDir: String) {
        prefs.edit().putString(KEY_SAVE_DIR, saveDir).apply()
    }

    fun saveAutoCleanup(autoCleanup: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CLEANUP, autoCleanup).apply()
    }

    companion object {
        private const val PREFS_NAME = "lossless_transfer_config"
        private const val KEY_SAVE_DIR = "save_dir"
        private const val KEY_AUTO_CLEANUP = "auto_cleanup"
    }
}
