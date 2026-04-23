package com.haostoo.wetypehookr

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log

object HapticProviderClient {

    private const val TAG = "HAPTIC_PROVIDER"

    private val URI =
        Uri.parse("content://com.haostoo.wetypehookr.provider/haptic")

    /**
     * 返回 Pair<downType, upType>
     */
    fun get(context: Context): Pair<Int, Int> {

        return try {

            val cursor: Cursor? = context.contentResolver.query(
                URI,
                null,
                null,
                null,
                null
            )

            if (cursor != null && cursor.moveToFirst()) {

                val down = cursor.getInt(0)
                val up = cursor.getInt(1)

                cursor.close()

                Log.e(TAG, "read down=$down up=$up")

                Pair(down, up)

            } else {

                Log.e(TAG, "cursor empty")

                Pair(1, 1)
            }

        } catch (t: Throwable) {

            Log.e(TAG, "query failed=$t")

            Pair(1, 1)
        }
    }
}