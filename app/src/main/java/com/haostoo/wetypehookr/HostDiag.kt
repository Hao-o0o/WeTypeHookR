package com.haostoo.wetypehookr

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * 关键路径的持久化诊断日志。
 *
 * logcat 会被系统轮转、也会被调试时清空，而「设置页打不开」这类问题往往只能复现一两次，
 * 因此把注册 / 启动失败的证据直接写进宿主日志目录，事后 `adb shell cat` 就能看到。
 */
object HostDiag {

    private const val FILE = "diag.log"

    private const val MAX_BYTES = 64L * 1024L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "haostoo-diag").apply { isDaemon = true }
    }

    fun log(context: Context?, tag: String, message: String) {
        val app = context?.applicationContext ?: return
        val line = "${HostInfo.formatTime(System.currentTimeMillis())} [$tag] $message\n"
        executor.execute {
            runCatching {
                val file = File(HostPaths.logs(app), FILE)
                if (file.isFile && file.length() > MAX_BYTES) file.delete()
                file.appendText(line)
            }
        }
    }
}
