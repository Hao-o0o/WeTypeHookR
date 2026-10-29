package com.haostoo.wetypehookr

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 旧版（文件型）配置的手动导入解析。
 *
 * 旧文件位于宿主 `Android/data/<包名>/files/haostoo/config/`，系统文件选择器
 * 通常无法直接访问，需要用户先用文件管理器 / ADB / root 复制到可访问目录。
 */
object LegacyConfigImporter {

    /**
     * 解析旧 config.xml 文本为 SettingsState。
     * 旧 XML 缺少新字段时按 [SettingsModel.defaultSettings] 规则补齐。
     * 解析失败返回 null。
     */
    fun parseLegacyXml(text: String): SettingsModel.SettingsState? {
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(text))

            var downMode = 0; var upMode = 0
            var downSystem = 0; var upSystem = 0
            var downDuration = 50f; var downStrength = 0.5f
            var upDuration = 50f; var upStrength = 0.5f
            var themeMode = 2
            var debugLog = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "down_mode"     -> downMode     = parser.nextText().toInt()
                        "up_mode"       -> upMode       = parser.nextText().toInt()
                        "down_system"   -> downSystem   = parser.nextText().toInt()
                        "up_system"     -> upSystem     = parser.nextText().toInt()
                        "down_duration" -> downDuration = parser.nextText().toFloat()
                        "down_strength" -> downStrength = parser.nextText().toFloat()
                        "up_duration"   -> upDuration   = parser.nextText().toFloat()
                        "up_strength"   -> upStrength   = parser.nextText().toFloat()
                        "theme_mode"    -> themeMode    = parser.nextText().toInt()
                        "debug_log"     -> debugLog     = parser.nextText().toBoolean()
                    }
                }
                event = parser.next()
            }

            SettingsModel.SettingsState(
                downIndex = downMode,
                upIndex = upMode,
                downDuration = downDuration,
                downStrength = downStrength,
                upDuration = upDuration,
                upStrength = upStrength,
                downSystemIndex = downSystem,
                upSystemIndex = upSystem,
                themeMode = themeMode,
                debugLog = debugLog,
            )
        } catch (e: Exception) {
            null
        }
    }
}
