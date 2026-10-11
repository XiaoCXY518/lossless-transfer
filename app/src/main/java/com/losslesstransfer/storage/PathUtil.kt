package com.losslesstransfer.storage

import java.io.File

object PathUtil {

    /** 规整相对路径：反斜杠转正斜杠，去掉空段、"." 与 ".."。 */
    fun normalizeRelativePath(rel: String?): String {
        if (rel.isNullOrBlank()) return ""
        return rel.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
    }

    /** 清洗文件名/目录名中的非法字符（种子名等）。 */
    fun sanitizeName(name: String): String {
        val cleaned = name.replace(Regex("""[<>:"/\\|?*]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned.ifEmpty { "unnamed" }
    }

    /**
     * 在 baseDir 下安全解析相对路径。
     * 非法穿越（最终路径越出 baseDir）抛 IllegalArgumentException。
     */
    fun resolveSafe(baseDir: File, rel: String): File {
        val base = baseDir.absoluteFile
        val normalized = normalizeRelativePath(rel)
        val target = if (normalized.isEmpty()) base else File(base, normalized).absoluteFile

        val baseCanonical = base.canonicalFile
        val targetCanonical = target.canonicalFile
        if (targetCanonical != baseCanonical &&
            !targetCanonical.path.startsWith(baseCanonical.path + File.separator)
        ) {
            throw IllegalArgumentException("非法路径: $rel")
        }
        return target
    }

    /** 本地文件重名加序号：name(1).ext。 */
    fun uniqueFile(target: File): File {
        if (!target.exists()) return target
        val parent = target.parentFile ?: File(".")
        val name = uniqueName(target.name) { candidate -> File(parent, candidate).exists() }
        return File(parent, name)
    }

    /** 通用重名加序号，exists 判定由调用方提供（File 或 SAF 均可）。 */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val stem: String
        val ext: String
        if (dot > 0) {
            stem = name.substring(0, dot)
            ext = name.substring(dot)
        } else {
            stem = name
            ext = ""
        }
        var i = 1
        while (true) {
            val candidate = "$stem($i)$ext"
            if (!exists(candidate)) return candidate
            i++
        }
    }
}
