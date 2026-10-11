package com.losslesstransfer.util

import java.io.File
import java.util.Locale

object FileUtil {

    private val SIZE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "${bytes}B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < SIZE_UNITS.size - 1) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.2f%s", value, SIZE_UNITS[unit])
    }

    /**
     * 递归遍历 root 目录，返回所有文件相对 base 的路径（正斜杠分隔）。
     * 传输文件夹时 base 取被选目录的父目录，从而保留文件夹名与子目录结构。
     */
    fun listRelativeFiles(root: File, base: File): List<String> {
        val result = mutableListOf<String>()
        if (!root.isDirectory) return result
        val children = root.listFiles() ?: return result
        children.sortBy { it.name }
        for (child in children) {
            when {
                child.isDirectory -> result += listRelativeFiles(child, base)
                child.isFile -> result += child.toRelativeString(base).replace('\\', '/')
            }
        }
        return result
    }

    /** 从 Content-Type 推断扩展名（带点），未知返回 null。 */
    fun extensionFromContentType(contentType: String?): String? {
        if (contentType.isNullOrBlank()) return null
        val mime = contentType.substringBefore(';').trim().lowercase(Locale.US)
        return MIME_TO_EXT[mime]
    }

    private val MIME_TO_EXT = mapOf(
        "application/zip" to ".zip",
        "application/x-rar-compressed" to ".rar",
        "application/vnd.rar" to ".rar",
        "application/x-7z-compressed" to ".7z",
        "application/gzip" to ".gz",
        "application/x-gzip" to ".gz",
        "application/x-tar" to ".tar",
        "application/pdf" to ".pdf",
        "application/json" to ".json",
        "application/xml" to ".xml",
        "text/xml" to ".xml",
        "text/plain" to ".txt",
        "text/html" to ".html",
        "text/css" to ".css",
        "text/csv" to ".csv",
        "image/jpeg" to ".jpg",
        "image/png" to ".png",
        "image/gif" to ".gif",
        "image/webp" to ".webp",
        "image/svg+xml" to ".svg",
        "audio/mpeg" to ".mp3",
        "audio/mp4" to ".m4a",
        "audio/aac" to ".aac",
        "video/mp4" to ".mp4",
        "video/x-matroska" to ".mkv",
        "video/webm" to ".webm",
        "application/vnd.android.package-archive" to ".apk",
    )
}
