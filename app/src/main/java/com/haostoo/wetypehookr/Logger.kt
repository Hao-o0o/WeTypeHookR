package com.haostoo.wetypehookr

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private const val LOG_FILE = "kill_process.log"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private fun logFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "haostoo/log")
        dir.mkdirs()
        return File(dir, LOG_FILE)
    }

    fun log(context: Context, message: String) {
        try {
            val time = fmt.format(Date())
            logFile(context).appendText("[$time] $message\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clear(context: Context) {
        try {
            logFile(context).writeText("")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun path(context: Context): String = logFile(context).absolutePath
}