package com.haostoo.wetypehookr

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 宿主数据目录（唯一配置落点）。
 *
 * 优先外部私有目录 `/sdcard/Android/data/<host>/files/haostoo/`，不可用时回退内部 `filesDir`。
 * 所有路径只能从这里取，其它类不得自行拼接。
 */
object HostPaths {

    private const val ROOT = "haostoo"

    private const val EXTERNAL_ANDROID = "Android/data"

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, ROOT).apply { mkdirs() }

    fun configDir(context: Context): File = File(root(context), "config").apply { mkdirs() }

    /** 唯一配置快照。 */
    fun state(context: Context): File = File(configDir(context), "state.json")

    /** 迁移后的旧文件留档目录。 */
    fun legacyDir(context: Context): File = File(configDir(context), "legacy").apply { mkdirs() }

    fun logs(context: Context): File = File(root(context), "logs").apply { mkdirs() }

    // ================= 旧文件（仅迁移与备份用） =================

    fun legacySettings(context: Context): File = File(configDir(context), "settings.json")

    fun legacyHaptic(context: Context): File = File(configDir(context), "haptic.json")

    fun legacyUi(context: Context): File = File(configDir(context), "ui.json")

    fun legacyXml(context: Context): File = File(configDir(context), "config.xml")

    /**
     * 另一个宿主 `state.json` 的只读路径。
     *
     * 不同应用的外部私有目录在 Android 11+ 默认不可互访，读取失败是预期结果，
     * 调用方据此进入「不可读」只读态。
     */
    fun externalState(host: String): File = File(
        File(File(Environment.getExternalStorageDirectory(), EXTERNAL_ANDROID), "$host/files"),
        "$ROOT/config/state.json",
    )
}
