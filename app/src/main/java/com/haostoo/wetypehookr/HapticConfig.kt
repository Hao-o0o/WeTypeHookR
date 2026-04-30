package com.haostoo.wetypehookr

import android.content.Context
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants

object HapticConfig {

    private const val SP_NAME = "haptic_config"

    const val KEY_DOWN = "down"
    const val KEY_UP = "up"

    private fun sp(context: Context): SharedPreferences {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    }

    // ================== Int get ==================
    fun get(context: Context, key: String, def: Int): Int {
        return sp(context).getInt(key, def)
    }

    // ================== Int save ==================
    fun save(context: Context, key: String, value: Int) {
        sp(context).edit().putInt(key, value).apply()
    }

    // ================== Hook侧“只读一次快照” ==================
    data class Snapshot(
        val downMode: Int,
        val upMode: Int,
        val downType: Int,
        val upType: Int,
        val downDuration: Long,
        val upDuration: Long,
        val downStrength: Int,
        val upStrength: Int
    )

    fun loadSnapshot(context: Context): Snapshot {
        val sp = sp(context)

        return Snapshot(
            downMode = sp.getInt("down_mode", 0),
            upMode = sp.getInt("up_mode", 0),

            downType = sp.getInt(KEY_DOWN, HapticFeedbackConstants.KEYBOARD_TAP),
            upType = sp.getInt(KEY_UP, HapticFeedbackConstants.KEYBOARD_TAP),

            downDuration = sp.getInt("down_duration", 10).toLong(),
            upDuration = sp.getInt("up_duration", 5).toLong(),

            downStrength = sp.getInt("down_strength", 80),
            upStrength = sp.getInt("up_strength", 40)
        )
    }
}