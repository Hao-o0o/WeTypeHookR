package com.haostoo.wetypehookr

import android.os.Environment
import android.util.Log
import java.io.File

object HapticFileConfig {

    private const val TAG = "HAPTIC_FILE"

    private const val MODULE_PKG = "com.haostoo.wetypehookr"
    private const val FILE_NAME = "haptic_config.txt"

    private fun getFile(): File {

        val base = Environment.getExternalStorageDirectory().absolutePath
        val path = "$base/Android/data/$MODULE_PKG/$FILE_NAME"

        return File(path)
    }

    fun getInt(key: String, default: Int): Int {

        return try {

            val file = getFile()

            if (!file.exists()) {
                Log.e(TAG, "file not found: ${file.absolutePath}")
                return default
            }

            file.readLines().forEach {
                val parts = it.split("=")
                if (parts.size == 2 && parts[0] == key) {

                    val value = parts[1].toIntOrNull()

                    Log.e(TAG, "read $key=$value")

                    return value ?: default
                }
            }

            default

        } catch (t: Throwable) {
            Log.e(TAG, "read error=$t")
            default
        }
    }
}