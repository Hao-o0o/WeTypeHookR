package com.haostoo.wetypehookr

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log

// ⭐ 新配置结构（分离 DOWN / UP 模式）
data class HapticFullConfig(

    val downMode: Int,
    val upMode: Int,

    val downType: Int,
    val upType: Int,

    val downDuration: Long,
    val downStrength: Int,

    val upDuration: Long,
    val upStrength: Int
)

object HapticProviderClient {

    private const val TAG = "HAPTIC_PROVIDER"

    private val URI =
        Uri.parse("content://com.haostoo.wetypehookr.provider/haptic")

    fun getFull(context: Context): HapticFullConfig {

        return try {

            val cursor: Cursor? = context.contentResolver.query(
                URI,
                null,
                null,
                null,
                null
            )

            if (cursor != null && cursor.moveToFirst()) {

                val cfg = HapticFullConfig(

                    cursor.getInt(0), // downMode
                    cursor.getInt(1), // upMode

                    cursor.getInt(2), // downType
                    cursor.getInt(3), // upType

                    cursor.getLong(4), // downDuration
                    cursor.getInt(5),  // downStrength

                    cursor.getLong(6), // upDuration
                    cursor.getInt(7)   // upStrength
                )

                cursor.close()

                Log.wtf(
                    TAG,
                    "downMode=${cfg.downMode} upMode=${cfg.upMode}"
                )

                cfg

            } else {

                Log.e(TAG, "cursor empty")

                default()
            }

        } catch (t: Throwable) {

            Log.e(TAG, "query failed=$t")

            default()
        }
    }

    // ⭐ 默认配置（防崩）
    private fun default(): HapticFullConfig {

        return HapticFullConfig(
            0, 0, // downMode / upMode（默认系统）

            1, 1, // downType / upType

            10, 80, // down custom

            5, 40   // up custom
        )
    }
}