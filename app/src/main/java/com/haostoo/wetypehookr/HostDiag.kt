package com.haostoo.wetypehookr

import android.content.Context
import android.util.Log
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

    private const val MAX_BYTES = 128L * 1024L

    private const val TAG = "WETYPE_DIAG"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "haostoo-diag").apply { isDaemon = true }
    }

    @Volatile
    private var crashHandlerInstalled = false

    fun log(context: Context?, tag: String, message: String) {
        val app = context?.applicationContext ?: return
        val line = "${HostInfo.formatTime(System.currentTimeMillis())} [$tag] $message\n"
        executor.execute { append(app, line) }
    }

    /**
     * 同步写入，用于崩溃与 `onCreate` 入口这类「下一秒可能进程就没了」的位置。
     *
     * 异步队列在进程被 abort 时不一定来得及刷盘，这里直接写文件保证留证。
     */
    fun logNow(context: Context?, tag: String, message: String) {
        val app = context?.applicationContext ?: return
        val line = "${HostInfo.formatTime(System.currentTimeMillis())} [$tag] $message\n"
        append(app, line)
    }

    /**
     * 安装进程级崩溃日志：宿主进程里模块导致的任何未捕获异常都会写进 diag.log。
     *
     * 必须保留并转发给原有 handler，否则会破坏宿主自己的崩溃处理（框架要拿它退出进程）。
     */
    fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val app = context.applicationContext ?: return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logNow(app, "CRASH", "thread=${thread.name} ${Log.getStackTraceString(throwable)}")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun append(app: Context, line: String) {
        runCatching {
            val file = File(HostPaths.logs(app), FILE)
            if (file.isFile && file.length() > MAX_BYTES) file.delete()
            file.appendText(line)
        }
    }
}
