package com.haostoo.wetypehookr

import android.content.Context
import org.json.JSONObject

/**
 * 当前宿主的配置导入 / 导出。
 *
 * 设置页运行在宿主进程内，只能访问本宿主的数据目录，因此导入导出都以「当前宿主」为单位，
 * 不再有跨宿主复制；颜色模式存在 `ui.json`，随配置一起导出。
 */
object ConfigTransfer {

    private const val FORMAT_VERSION = 1

    fun exportJson(context: Context): String {
        val host = context.packageName
        val snapshot = SettingsStore.load(context, host)
        val root = JSONObject().apply {
            put("format", FORMAT_VERSION)
            put("package", host)
            put("color_mode", snapshot.ui.colorMode)
            put("revision", snapshot.revision)
            put(
                "settings",
                JSONObject().apply {
                    val s = snapshot.settings
                    put("down_mode", s.downIndex)
                    put("up_mode", s.upIndex)
                    put("down_system", s.downSystemIndex)
                    put("up_system", s.upSystemIndex)
                    put("down_duration", s.downDuration.toDouble())
                    put("down_strength", s.downStrength.toDouble())
                    put("up_duration", s.upDuration.toDouble())
                    put("up_strength", s.upStrength.toDouble())
                    put("theme_mode", s.themeMode)
                    put("debug_log", s.debugLog)
                },
            )
        }
        snapshot.haptic?.let { root.put("waveform", SettingsModel.serializeCustomHaptic(it)) }
        return root.toString(2)
    }

    /** 导入当前宿主配置；返回是否写入了任何内容。 */
    fun importJson(context: Context, text: String): Boolean {
        val host = context.packageName
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return false
        }

        var any = false
        root.optJSONObject("settings")?.let { s ->
            any = ConfigRepository.saveSettings(
                context,
                host,
                SettingsModel.SettingsState(
                    downIndex = s.optInt("down_mode", 1),
                    upIndex = s.optInt("up_mode", 1),
                    downDuration = s.optDouble("down_duration", 50.0).toFloat(),
                    downStrength = s.optDouble("down_strength", 0.5).toFloat(),
                    upDuration = s.optDouble("up_duration", 50.0).toFloat(),
                    upStrength = s.optDouble("up_strength", 0.5).toFloat(),
                    downSystemIndex = s.optInt("down_system", 0),
                    upSystemIndex = s.optInt("up_system", 3),
                    themeMode = s.optInt("theme_mode", 2),
                    debugLog = s.optBoolean("debug_log", false),
                ),
            ) || any
        }

        root.optString("waveform", "").takeIf { it.isNotBlank() }?.let { wf ->
            SettingsModel.parseCustomHaptic(wf)?.let {
                any = ConfigRepository.saveWaveform(context, host, it) || any
            }
        }

        root.optInt("color_mode", -1).takeIf { it >= 0 }?.let { mode ->
            SettingsStore.saveUi(context, host, SettingsStore.load(context, host).ui.copy(colorMode = mode))
        }
        return any
    }
}
