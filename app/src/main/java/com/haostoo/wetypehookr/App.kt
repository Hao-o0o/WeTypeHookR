package com.haostoo.wetypehookr

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.io.File

// ================= 配置文件路径 =================

internal fun configFile(context: Context): File {
    val dir = File(context.getExternalFilesDir(null), "haostoo/config")
    dir.mkdirs()
    return File(dir, "config.xml")
}

// ================= READ =================

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

internal fun readConfig(context: Context): SettingsState {
    val file = configFile(context)
    val default = defaultSettings()
    if (!file.exists()) return default

    return try {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(file.inputStream(), "UTF-8")

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

        SettingsState(downMode, upMode, downDuration, downStrength, upDuration, upStrength, downSystem, upSystem, themeMode, debugLog)
    } catch (e: Exception) {
        e.printStackTrace()
        default
    }
}

// ================= WRITE =================

internal fun writeConfig(context: Context, state: SettingsState) {
    try {
        val file = configFile(context)
        val xml = """
<config>
    <down_mode>${state.downIndex}</down_mode>
    <up_mode>${state.upIndex}</up_mode>
    <down_system>${state.downSystemIndex}</down_system>
    <up_system>${state.upSystemIndex}</up_system>
    <down_duration>${state.downDuration}</down_duration>
    <down_strength>${state.downStrength}</down_strength>
    <up_duration>${state.upDuration}</up_duration>
    <up_strength>${state.upStrength}</up_strength>
    <theme_mode>${state.themeMode}</theme_mode>
    <debug_log>${state.debugLog}</debug_log>
</config>
""".trimIndent()
        file.outputStream().use { it.write(xml.toByteArray(Charsets.UTF_8)) }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

// ================= UI =================

@Composable
fun App(onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(readConfig(context)) }

    // 外观主题：0=浅色 1=深色 2=跟随系统
    val darkTheme = when (state.themeMode) {
        0 -> false
        1 -> true
        else -> isSystemInDarkTheme()
    }

    val navController = rememberNavController()

    MiuixTheme(colors = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = "设置",
                    navigationIcon = {
                        when {
                            onClose != null -> IconButton(onClose) {
                                Icon(MiuixIcons.Back, "close")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "settings",
                modifier = Modifier
                    .padding(top = padding.calculateTopPadding())
                    .fillMaxSize()
            ) {
                composable("settings") {
                    SettingsScreen(
                        state = state,
                        onStateChange = { state = it }
                    )
                }
            }
        }
    }
}
