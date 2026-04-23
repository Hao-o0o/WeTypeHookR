package com.haostoo.wetypehookr

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

object HapticConfig {

    private const val TAG = "HAPTIC_SAVE"

    private const val MODULE_PKG = "com.haostoo.wetypehookr"
    private const val FILE_NAME = "haptic_config.txt"

    const val KEY_DOWN = "down_type"
    const val KEY_UP = "up_type"

    private fun getFile(): File {

        val base = Environment.getExternalStorageDirectory().absolutePath
        val dirPath = "$base/Android/data/$MODULE_PKG"

        val dir = File(dirPath)

        // ✅ 强制创建目录（关键）
        if (!dir.exists()) {
            val ok = dir.mkdirs()
            Log.e(TAG, "mkdirs result=$ok path=$dirPath")
        }

        return File(dir, FILE_NAME)
    }

    fun save(context: Context, key: String, value: Int) {

        try {

            val file = getFile()

            val map = mutableMapOf<String, Int>()

            // 读取旧数据
            if (file.exists()) {
                file.readLines().forEach {
                    val parts = it.split("=")
                    if (parts.size == 2) {
                        map[parts[0]] = parts[1].toIntOrNull() ?: 0
                    }
                }
            }

            // 写入新值
            map[key] = value

            file.writeText(
                map.entries.joinToString("\n") {
                    "${it.key}=${it.value}"
                }
            )

            Log.e(TAG, "saved OK -> ${file.absolutePath}")

        } catch (t: Throwable) {
            Log.e(TAG, "write failed=$t")
        }
    }

    fun get(context: Context, key: String, def: Int): Int {

        return try {

            val file = getFile()

            if (!file.exists()) return def

            file.readLines().forEach {
                val parts = it.split("=")
                if (parts.size == 2 && parts[0] == key) {
                    return parts[1].toIntOrNull() ?: def
                }
            }

            def

        } catch (t: Throwable) {
            Log.e(TAG, "read failed=$t")
            def
        }
    }
}