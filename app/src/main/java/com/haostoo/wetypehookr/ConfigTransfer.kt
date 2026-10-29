package com.haostoo.wetypehookr

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 当前宿主的配置导入 / 导出。
 *
 * 外层是信封 `{format, host, state}`，`state` 就是 `state.json` 的完整结构；
 * 导入时先校验 `format` 与 `schema`，再经 [SettingsStore.update] 落盘，不直接写文件。
 * 同时兼容 1.3.0 时代导出的扁平格式（`{package, settings, waveform, color_mode}`）。
 */
object ConfigTransfer {

    private const val FORMAT_VERSION = 1

    @Serializable
    private data class Envelope(
        val format: Int = FORMAT_VERSION,
        val host: String = "",
        val state: SettingsModel.HostState = SettingsModel.HostState(),
    )

    /** 导出当前宿主快照。 */
    fun exportJson(context: Context): String {
        val envelope = Envelope(
            format = FORMAT_VERSION,
            host = context.packageName,
            state = SettingsStore.current(),
        )
        return SettingsModel.json.encodeToString(Envelope.serializer(), envelope)
    }

    /**
     * 导入配置；返回是否写入了任何内容。
     *
     * 校验失败（JSON 非法、format 不认识、schema 比当前新）直接返回 false。
     */
    fun importJson(context: Context, text: String): Boolean {
        val host = context.packageName
        val state = parseEnvelope(text) ?: parseLegacy(text) ?: return false
        return SettingsStore.update(context, host) { current ->
            current.copy(
                settings = state.settings,
                waveform = state.waveform,
                ui = state.ui,
            )
        }
    }

    private fun parseEnvelope(text: String): SettingsModel.HostState? {
        val root = runCatching { SettingsModel.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if (root["state"] == null) return null
        val format = root["format"]?.jsonPrimitive?.content?.toIntOrNull() ?: FORMAT_VERSION
        if (format != FORMAT_VERSION) return null
        val envelope = runCatching {
            SettingsModel.json.decodeFromJsonElement(Envelope.serializer(), JsonObject(root))
        }.getOrNull() ?: return null
        if (envelope.state.schema > SettingsModel.SCHEMA_VERSION) return null
        return envelope.state
    }

    /** 1.3.0 的扁平导出：settings 平铺在同一层，waveform 是字符串形式的 JSON。 */
    private fun parseLegacy(text: String): SettingsModel.HostState? = runCatching {
        val root = org.json.JSONObject(text)
        val settings = SettingsModel.parseLegacySettings(text)
        val waveform = root.optString("waveform", "")
            .takeIf { it.isNotBlank() }
            ?.let { SettingsModel.parseLegacyHaptic(it) }
        if (settings == null && waveform == null) return null
        SettingsModel.HostState(
            updatedAt = System.currentTimeMillis(),
            settings = settings ?: SettingsModel.defaultSettings(),
            waveform = waveform,
            ui = SettingsModel.UiState(colorMode = root.optInt("color_mode", 2)),
        )
    }.getOrNull()
}
