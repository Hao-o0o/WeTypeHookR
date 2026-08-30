package com.haostoo.wetypehookr

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志工具：按分类输出到 <宿主>/files/haostoo/logs/ 目录。
 * 分类：hardware（硬件/系统信息）、vibrate（震动）、ui（界面）。
 * 受 SettingsState.debugLog 开关控制（保存配置后经 FileObserver 生效）。
 */
object DebugLog {

    fun logsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "haostoo/logs")
        dir.mkdirs()
        return dir
    }

    fun isEnabled(): Boolean = MainModule.cachedSettings.debugLog

    fun log(context: Context, category: String, message: String) {
        if (!isEnabled()) return
        runCatching {
            ensureHardwareInfo(context)
            val f = File(logsDir(context), "$category.log")
            f.appendText(
                "[${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}] $message\n"
            )
        }
    }

    @Volatile
    private var hardwareWritten = false

    /** 写入一次硬件/系统信息（文件已存在则跳过） */
    fun ensureHardwareInfo(context: Context) {
        if (hardwareWritten) return
        hardwareWritten = true
        runCatching {
            val f = File(logsDir(context), "hardware.log")
            if (f.exists()) return
            f.writeText(buildHardwareInfo(context))
        }
    }

    private fun buildHardwareInfo(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("===== Hardware / System Info =====")
        sb.appendLine("Brand: ${Build.BRAND}")
        sb.appendLine("Model: ${Build.MODEL}")
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("Device: ${Build.DEVICE}")
        sb.appendLine("Hardware Platform: ${Build.HARDWARE}")
        sb.appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Security Patch: ${Build.VERSION.SECURITY_PATCH}")
        sb.appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("Kernel Version: ${System.getProperty("os.version")}")
        sb.appendLine("Host Package: ${context.packageName}")
        return sb.toString()
    }

    /** 清除全部日志 */
    fun clear(context: Context) {
        runCatching { logsDir(context).listFiles()?.forEach { it.delete() } }
    }

    /** 把 logs 目录下全部日志打包为 zip 到 targetFile */
    fun exportToZip(context: Context, targetFile: File): Boolean {
        return runCatching {
            val logs = logsDir(context).listFiles()?.filter { it.isFile }.orEmpty()
            if (logs.isEmpty()) return false
            java.util.zip.ZipOutputStream(targetFile.outputStream()).use { zos ->
                logs.forEach { f ->
                    zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            true
        }.getOrDefault(false)
    }
}
