package com.haostoo.wetypehookr

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject

/**
 * 配置导入 / 导出。
 *
 * 导出只包含**震动参数**（模式、时长、强度、波形），不带宿主、界面设置、时间戳；
 * 导入时由用户选择写入哪个宿主，因此同一份文件可以在任意宿主之间搬迁。
 *
 * 兼容两种历史格式：
 * - `1.6.x` 及更早的信封 `{format, host, state:{settings, waveform, ui}}`
 * - `1.3.0` 的扁平格式 `{package, settings..., waveform:"<json>", color_mode}`
 */
object ConfigTransfer {

    private const val FORMAT_VERSION = 2

    /** 导出文件的内容：只有震动参数。 */
    @Serializable
    data class HapticPayload(
        val format: Int = FORMAT_VERSION,
        val settings: SettingsModel.SettingsState = SettingsModel.SettingsState(),
        val waveform: SettingsModel.CustomHapticConfig? = null,
    )

    fun suggestedFileName(): String = "typevibrator-config.json"

    /** 序列化指定宿主的震动参数。 */
    fun exportJson(state: SettingsModel.HostState): String =
        SettingsModel.json.encodeToString(
            HapticPayload.serializer(),
            HapticPayload(settings = state.settings, waveform = state.waveform),
        )

    /**
     * 解析任意历史格式，取出震动参数。
     *
     * 优先按新格式（顶层有 `settings`）解析，其次解旧信封的 `state`，
     * 最后退回 1.3.0 的扁平格式。
     */
    fun parse(text: String): HapticPayload? {
        val root = runCatching { SettingsModel.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return parseLegacy(text)

        // 旧信封：{format, host, state:{...}}
        root["state"]?.takeIf { it !is JsonNull }?.let { element ->
            val state = runCatching { element.jsonObject }.getOrNull() ?: return@let
            return payloadOf(state["settings"], state["waveform"]) ?: parseLegacy(text)
        }

        return payloadOf(root["settings"], root["waveform"]) ?: parseLegacy(text)
    }

    private fun payloadOf(
        settingsElement: JsonElement?,
        waveformElement: JsonElement?,
    ): HapticPayload? {
        if (settingsElement == null) return null
        val settings = runCatching {
            SettingsModel.json.decodeFromJsonElement(
                SettingsModel.SettingsState.serializer(),
                settingsElement,
            )
        }.getOrNull() ?: return null
        val waveform = waveformElement?.takeIf { it !is JsonNull }?.let {
            runCatching {
                SettingsModel.json.decodeFromJsonElement(
                    SettingsModel.CustomHapticConfig.serializer(),
                    it,
                )
            }.getOrNull()
        }
        return HapticPayload(settings = settings, waveform = waveform)
    }

    /** 1.3.0 的扁平导出：settings 平铺在同一层，waveform 是字符串形式的 JSON。 */
    private fun parseLegacy(text: String): HapticPayload? = runCatching {
        val root = org.json.JSONObject(text)
        val settings = SettingsModel.parseLegacySettings(text)
        val waveform = root.optString("waveform", "")
            .takeIf { it.isNotBlank() }
            ?.let { SettingsModel.parseLegacyHaptic(it) }
        if (settings == null && waveform == null) return null
        HapticPayload(
            settings = settings ?: SettingsModel.defaultSettings(),
            waveform = waveform,
        )
    }.getOrNull()

    /**
     * 读取指定宿主的当前快照。
     *
     * 当前宿主走内存刷新；另一个宿主只能读外部目录，Android 11+ 可能读不到，返回 null。
     */
    fun loadState(context: Context, host: String, primaryHost: String): SettingsModel.HostState? =
        if (host == primaryHost) {
            SettingsStore.reload(context, host)
        } else {
            SettingsStore.readExternal(host)
        }

    /** 把震动参数写入指定宿主；界面设置（颜色模式）保持目标宿主原样。 */
    fun apply(
        context: Context,
        host: String,
        primaryHost: String,
        payload: HapticPayload,
    ): Boolean {
        if (host == primaryHost) {
            return SettingsStore.update(context, host) { current ->
                current.copy(settings = payload.settings, waveform = payload.waveform)
            }
        }
        val base = SettingsStore.readExternal(host) ?: SettingsModel.HostState()
        return SettingsStore.writeExternal(
            context,
            host,
            base.copy(settings = payload.settings, waveform = payload.waveform),
        )
    }

    /** 导出前判断宿主是否可读，用于把不可读的项标灰。 */
    fun canRead(host: String, primaryHost: String): Boolean =
        host == primaryHost || HostPaths.externalState(host).isFile
}
