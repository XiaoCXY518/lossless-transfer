package com.losslesstransfer.data

/**
 * 应用配置。
 *
 * @param saveDir 接收目录。空串表示使用默认 app-specific external 目录；
 *                否则为本地绝对路径或 SAF 目录树 URI（content:// 开头）。
 * @param autoCleanup 关闭/退出时是否清空本次接收的文件。
 */
data class AppConfig(
    val saveDir: String = "",
    val autoCleanup: Boolean = false,
)
