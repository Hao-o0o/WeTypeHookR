package com.haostoo.wetypehookr

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class HapticProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.haostoo.wetypehookr.provider"
        val URI = Uri.parse("content://$AUTHORITY/haptic")
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

        val down = HapticConfig.get(
            context,
            HapticConfig.KEY_DOWN,
            1
        )

        val up = HapticConfig.get(
            context,
            HapticConfig.KEY_UP,
            1
        )

        val cursor = MatrixCursor(arrayOf("down", "up"))
        cursor.addRow(arrayOf(down, up))

        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}