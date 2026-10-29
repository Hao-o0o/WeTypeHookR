package com.haostoo.wetypehookr

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 调试日志：直接写宿主日志目录。
 *
 * 与 Hook 同进程，不需要绑定 Service。开关来自当前生效配置的 `debug_log`；
 * 写盘放在单线程队列里，任何异常都只记 `Log.w`，绝不影响输入法。
 */
object DebugLog {

    private const val TAG = "WETYPE_CFG"

    /** 单文件阈值，超过后轮转为 `.1`。 */
    private const val MAX_BYTES = 512L * 1024L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "haostoo-log").apply { isDaemon = true }
    }

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val hardwareWritten = AtomicBoolean(false)

    /** 热重载前停止写盘线程。 */
    fun shutdown() {
        runCatching { executor.shutdownNow() }
    }

    fun isEnabled(): Boolean = HapticEngine.cachedSettings.debugLog

    fun log(context: Context, category: String, message: String) {
        if (!isEnabled()) return
        ensureHardwareInfo(context)
        append(context.applicationContext ?: context, category, message)
    }

    /** 记录一次硬件/系统信息，每个进程只写一次。 */
    fun ensureHardwareInfo(context: Context) {
        if (!isEnabled()) return
        if (!hardwareWritten.compareAndSet(false, true)) return
        append(context.applicationContext ?: context, "hardware", buildHardwareInfo(context))
    }

    private fun append(context: Context, category: String, message: String) {
        val line = "${timeFormat.format(Date())} [$category] $message\n"
        executor.execute {
            runCatching {
                val file = File(HostPaths.logs(context), "$category.log")
                if (file.isFile && file.length() > MAX_BYTES) {
                    val rotated = File(file.parentFile, "$category.log.1")
                    if (rotated.exists()) rotated.delete()
                    file.renameTo(rotated)
                }
                file.appendText(line)
            }.onFailure { Log.w(TAG, "log write failed: $category", it) }
        }
    }

    private fun buildHardwareInfo(context: Context): String = buildString {
        appendLine("===== Hardware / System Info =====")
        appendLine("Brand: ${Build.BRAND}")
        appendLine("Model: ${Build.MODEL}")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
        appendLine("Device: ${Build.DEVICE}")
        appendLine("Hardware Platform: ${Build.HARDWARE}")
        appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Security Patch: ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine("Kernel Version: ${System.getProperty("os.version")}")
        appendLine("Host Package: ${context.packageName}")
    }
}
