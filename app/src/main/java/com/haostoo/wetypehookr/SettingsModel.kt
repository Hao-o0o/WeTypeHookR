package com.haostoo.wetypehookr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * 单快照配置的数据模型与编解码。
 *
 * - 设置 / 波形 / UI 偏好合并在 [HostState] 里，对应宿主目录下的一个 `state.json`；
 * - JSON 字段统一 snake_case（`@SerialName` 固定），Kotlin 属性保持 camelCase；
 * - 所有字段都有默认值 → 缺字段可读；`ignoreUnknownKeys` → 多字段可忽略；
 * - 只做纯数据与校验，不引用任何框架类型，也不碰文件（落盘见 [SettingsStore]）。
 */
object SettingsModel {

    /** 当前结构版本；结构变更时自增并补迁移分支。 */
    const val SCHEMA_VERSION = 3

    /** 波形段数范围。 */
    const val MIN_SEGMENTS = 2
    const val MAX_SEGMENTS = 8

    /** 单段时长上限（毫秒）与强度上限。 */
    const val MAX_TIMING_MS = 500L
    const val MAX_AMPLITUDE = 255

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 一个宿主的完整状态，也就是 `state.json` 的内容。 */
    @Serializable
    data class HostState(
        val schema: Int = SCHEMA_VERSION,
        @SerialName("updated_at") val updatedAt: Long = 0L,
        val settings: SettingsState = SettingsState(),
        val waveform: CustomHapticConfig? = null,
        val ui: UiState = UiState(),
    )

    @Serializable
    data class SettingsState(
        @SerialName("down_mode") val downIndex: Int = 1,
        @SerialName("up_mode") val upIndex: Int = 1,
        @SerialName("down_duration") val downDuration: Float = 50f,
        @SerialName("down_strength") val downStrength: Float = 0.5f,
        @SerialName("up_duration") val upDuration: Float = 50f,
        @SerialName("up_strength") val upStrength: Float = 0.5f,
        @SerialName("down_system") val downSystemIndex: Int = 0,
        @SerialName("up_system") val upSystemIndex: Int = 3,
        @SerialName("theme_mode") val themeMode: Int = 2,
        @SerialName("debug_log") val debugLog: Boolean = false,
    )

    @Serializable
    data class WaveformHaptic(
        val timings: List<Long> = emptyList(),
        val amplitudes: List<Int> = emptyList(),
    )

    @Serializable
    data class CustomHapticConfig(
        val down: WaveformHaptic? = null,
        val up: WaveformHaptic? = null,
    )

    /** 页面自己的偏好：FAB 位置与配色。 */
    @Serializable
    data class UiState(
        @SerialName("fab_x") val fabX: Float = 0f,
        @SerialName("fab_y") val fabY: Float = 0.62f,
        @SerialName("color_mode") val colorMode: Int = 2,
    )

    fun defaultSettings() = SettingsState()

    /** 自定义波形的默认值（图形化编辑器首次打开时的预填）。 */
    fun defaultCustomHaptic() = CustomHapticConfig(
        down = WaveformHaptic(listOf(0L, 40L, 10L, 10L), listOf(0, 250, 0, 100)),
        up = WaveformHaptic(listOf(0L, 40L, 10L, 10L), listOf(0, 180, 0, 90)),
    )

    // ================= 波形校验 =================

    /**
     * 是否是可用的波形：段数 2–8、两组等长、首段为 0、时长与强度在范围内、至少一段大于 0 ms。
     */
    fun isValidWaveform(w: WaveformHaptic?): Boolean {
        if (w == null) return false
        val n = w.timings.size
        if (n !in MIN_SEGMENTS..MAX_SEGMENTS) return false
        if (w.amplitudes.size != n) return false
        if (w.timings[0] != 0L) return false
        if (w.timings.any { it < 0L || it > MAX_TIMING_MS }) return false
        if (w.amplitudes.any { it !in 0..MAX_AMPLITUDE }) return false
        return w.timings.drop(1).sum() > 0L
    }

    /** 把越界数值夹到合法区间；结构不完整时返回 null。 */
    fun sanitizeWaveform(w: WaveformHaptic?): WaveformHaptic? {
        if (w == null) return null
        val n = w.timings.size
        if (n !in MIN_SEGMENTS..MAX_SEGMENTS) return null
        if (w.amplitudes.size != n) return null
        val timings = w.timings.mapIndexed { index, value ->
            if (index == 0) 0L else value.coerceIn(0L, MAX_TIMING_MS)
        }
        val amplitudes = w.amplitudes.map { it.coerceIn(0, MAX_AMPLITUDE) }
        val fixed = WaveformHaptic(timings, amplitudes)
        return if (isValidWaveform(fixed)) fixed else null
    }

    // ================= 旧文件迁移（仅用于 legacy） =================

    /** 解析旧 `settings.json`（schema 2，平铺结构）。 */
    fun parseLegacySettings(text: String): SettingsState? = runCatching {
        val o = JSONObject(text)
        SettingsState(
            downIndex = o.optInt("down_mode", 1),
            upIndex = o.optInt("up_mode", 1),
            downDuration = o.optDouble("down_duration", 50.0).toFloat(),
            downStrength = o.optDouble("down_strength", 0.5).toFloat(),
            upDuration = o.optDouble("up_duration", 50.0).toFloat(),
            upStrength = o.optDouble("up_strength", 0.5).toFloat(),
            downSystemIndex = o.optInt("down_system", 0),
            upSystemIndex = o.optInt("up_system", 3),
            themeMode = o.optInt("theme_mode", 2),
            debugLog = o.optBoolean("debug_log", false),
        )
    }.getOrNull()

    /** 解析旧 `haptic.json`（`{"down":{"timings":[],"amplitudes":[]}}`）。 */
    fun parseLegacyHaptic(text: String): CustomHapticConfig? = runCatching {
        val o = JSONObject(text)
        CustomHapticConfig(
            down = parseLegacyWave(o, "down"),
            up = parseLegacyWave(o, "up"),
        )
    }.getOrNull()

    private fun parseLegacyWave(o: JSONObject, key: String): WaveformHaptic? {
        val obj = o.optJSONObject(key) ?: return null
        val t = obj.optJSONArray("timings") ?: return null
        val a = obj.optJSONArray("amplitudes") ?: return null
        if (t.length() == 0 || t.length() != a.length()) return null
        return sanitizeWaveform(
            WaveformHaptic(
                timings = List(t.length()) { t.getLong(it) },
                amplitudes = List(a.length()) { a.getInt(it) },
            ),
        )
    }

    /** 解析旧 `ui.json`（`{"fab_x":0,"fab_y":0.62,"color_mode":2}`）。 */
    fun parseLegacyUi(text: String): UiState? = runCatching {
        val o = JSONObject(text)
        UiState(
            fabX = o.optDouble("fab_x", 0.0).toFloat(),
            fabY = o.optDouble("fab_y", 0.62).toFloat(),
            colorMode = o.optInt("color_mode", 2),
        )
    }.getOrNull()

    // ================= 注入状态 =================

    /** 进程内注入状态（由 [HookRuntime] 提供，不落盘）。 */
    data class HookStatus(
        val hookInstalled: Boolean,
        val moduleVersion: String,
        val frameworkName: String?,
        val configUpdatedAt: Long,
        val heartbeatAt: Long,
    )
}
