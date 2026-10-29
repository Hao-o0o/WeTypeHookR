package com.haostoo.wetypehookr

import android.content.Context
import java.io.File

/**
 * 宿主数据目录（唯一配置落点）。
 *
 * 优先外部私有目录 `/sdcard/Android/data/<host>/files/haostoo/`，不可用时回退内部 `filesDir`。
 * 所有路径只能从这里取，其它类不得自行拼接。
 */
object HostPaths {

    private const val ROOT = "haostoo"

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, ROOT).apply { mkdirs() }

    fun configDir(context: Context): File = File(root(context), "config").apply { mkdirs() }

    fun settings(context: Context): File = File(configDir(context), "settings.json")

    fun haptic(context: Context): File = File(configDir(context), "haptic.json")

    fun ui(context: Context): File = File(configDir(context), "ui.json")

    fun logs(context: Context): File = File(root(context), "logs").apply { mkdirs() }

    /** 旧版（文件型）配置位置，仅用于一次性迁移。 */
    fun legacyConfig(context: Context): File = File(configDir(context), "config.xml")
}
