package com.haostoo.wetypehookr

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 当前宿主的日志文件管理：查看 / 清理 / 打包。
 *
 * 日志由 [DebugLog] 直接写入 `<host>/files/haostoo/logs/`，
 * 设置页运行在同一进程，因此这里不需要跨进程读取。
 */
object LogStore {

    fun logsRoot(context: Context): File = HostPaths.logs(context)

    fun listFiles(context: Context): List<File> =
        logsRoot(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            .orEmpty()

    fun clear(context: Context) {
        listFiles(context).forEach { it.delete() }
    }

    /** 打包当前宿主全部日志为 zip；无日志返回 false。 */
    fun exportToZip(context: Context, targetFile: File): Boolean = runCatching {
        val files = listFiles(context)
        if (files.isEmpty()) return false
        ZipOutputStream(targetFile.outputStream()).use { zos ->
            files.forEach { f ->
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        true
    }.getOrDefault(false)
}
