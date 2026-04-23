package com.haostoo.wetypehookr

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class HapticProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.haostoo.wetypehookr.provider"
        val URI: Uri = Uri.parse("content://$AUTHORITY/haptic")
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {

        val context = context ?: return MatrixCursor(arrayOf())

        // ⭐ 分离模式
        val downMode = HapticConfig.get(context, "down_mode", 0)
        val upMode = HapticConfig.get(context, "up_mode", 0)

        // ⭐ 系统震动
        val downType = HapticConfig.get(context, HapticConfig.KEY_DOWN, 1)
        val upType = HapticConfig.get(context, HapticConfig.KEY_UP, 1)

        // ⭐ 自定义震动
        val downDuration = HapticConfig.get(context, "down_duration", 10)
        val downStrength = HapticConfig.get(context, "down_strength", 80)

        val upDuration = HapticConfig.get(context, "up_duration", 5)
        val upStrength = HapticConfig.get(context, "up_strength", 40)

        val cursor = MatrixCursor(
            arrayOf(
                "downMode",
                "upMode",
                "downType",
                "upType",
                "downDuration",
                "downStrength",
                "upDuration",
                "upStrength"
            )
        )

        cursor.addRow(
            arrayOf(
                downMode,
                upMode,
                downType,
                upType,
                downDuration,
                downStrength,
                upDuration,
                upStrength
            )
        )

        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}