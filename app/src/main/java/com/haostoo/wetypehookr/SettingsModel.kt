package com.haostoo.wetypehookr

import org.json.JSONArray
import org.json.JSONObject

/**
 * 设置与波形的数据模型 + JSON 编解码。
 *
 * 只做纯数据与校验，不引用任何框架类型，也不碰文件（落盘由 [SettingsStore] 负责），
 * 因此 Hook 侧与 UI 侧可以共用。
 */
object SettingsModel {

    /** 当前配置结构版本；结构变更时自增。 */
    const val SCHEMA_VERSION = 2

    /** 波形段数范围。 */
    const val MIN_SEGMENTS = 2
    const val MAX_SEGMENTS = 8

    /** 单段时长上限（毫秒）与强度上限。 */
    const val MAX_TIMING_MS = 500L
    const val MAX_AMPLITUDE = 255

    data class SettingsState(
        val downIndex: Int,
        val upIndex: Int,
        val downDuration: Float,
        val downStrength: Float,
        val upDuration: Float,
        val upStrength: Float,
        var downSystemIndex: Int,
        var upSystemIndex: Int,
        var themeMode: Int = 2,     // 0=浅色 1=深色 2=跟随系统
        var debugLog: Boolean = false,
    )

    fun defaultSettings() = SettingsState(
        downIndex = 1,
        upIndex = 1,
        downDuration = 50f,
        downStrength = 0.5f,
        upDuration = 50f,
        upStrength = 0.5f,
        downSystemIndex = 0,
        upSystemIndex = 3,
        themeMode = 2,
        debugLog = false,
    )

    // ================= 自定义波形 =================

    data class WaveformHaptic(
        val timings: LongArray,
        val amplitudes: IntArray,
    )

    data class CustomHapticConfig(
        val down: WaveformHaptic?,
        val up: WaveformHaptic?,
    )

    /**
     * 解析波形 JSON；整体 JSON 非法返回 null，调用方据此保留上一个有效缓存。
     * down / up 缺失或不合法时对应项为 null（视为“未配置”）。
     */
    fun parseCustomHaptic(text: String): CustomHapticConfig? = try {
        val json = JSONObject(text)
        CustomHapticConfig(down = parseWave(json, "down"), up = parseWave(json, "up"))
    } catch (e: Exception) {
        null
    }

    private fun parseWave(json: JSONObject, key: String): WaveformHaptic? {
        val obj = json.optJSONObject(key) ?: return null
        val tArr = obj.optJSONArray("timings") ?: return null
        val aArr = obj.optJSONArray("amplitudes") ?: return null
        if (tArr.length() == 0 || tArr.length() != aArr.length()) return null
        val timings = LongArray(tArr.length()) { tArr.getLong(it) }
        val amplitudes = IntArray(aArr.length()) { aArr.getInt(it) }
        if (timings[0] != 0L) return null
        if (timings.any { it < 0 || it > MAX_TIMING_MS }) return null
        if (amplitudes.any { it !in 0..MAX_AMPLITUDE }) return null
        return WaveformHaptic(timings, amplitudes)
    }

    fun serializeCustomHaptic(config: CustomHapticConfig): String {
        val json = JSONObject()
        config.down?.let { json.put("down", waveToJson(it)) }
        config.up?.let { json.put("up", waveToJson(it)) }
        return json.toString()
    }

    /** 自定义波形的默认值（图形化编辑器首次打开时的预填）。 */
    fun defaultCustomHaptic(): CustomHapticConfig = CustomHapticConfig(
        down = WaveformHaptic(longArrayOf(0, 40, 10, 10), intArrayOf(0, 250, 0, 100)),
        up = WaveformHaptic(longArrayOf(0, 40, 10, 10), intArrayOf(0, 180, 0, 90)),
    )

    // ================= 注入状态 =================

    /** 进程内注入状态（由 [HookRuntime] 提供，不再落盘）。 */
    data class HookStatus(
        val hookInstalled: Boolean,
        val moduleVersion: String,
        val frameworkName: String?,
        val configRevision: Long,
        val heartbeatAt: Long,
    )

    private fun waveToJson(w: WaveformHaptic): JSONObject = JSONObject().apply {
        put("timings", JSONArray(w.timings.toList()))
        put("amplitudes", JSONArray(w.amplitudes.toList()))
    }
}
