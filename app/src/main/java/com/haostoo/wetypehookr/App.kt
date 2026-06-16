package com.haostoo.wetypehookr

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import java.net.URL

// ================= 配置文件路径 =================

internal fun configFile(context: Context): File {
    val dir = File(context.getExternalFilesDir(null), "haostoo/config")
    dir.mkdirs()
    val file = File(dir, "config.xml")
//    try {
//        File("/storage/emulated/0/Android/data/com.bytedance.android.doubaoime/files/haostoo/swipe_test.txt")
//            .appendText("configFile path = ${file.absolutePath}\n")
//    } catch (_: Exception) {}
    return file
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
                }
            }
            event = parser.next()
        }

        SettingsState(downMode, upMode, downDuration, downStrength, upDuration, upStrength, downSystem, upSystem)
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
    val isDoubaoIme = remember {
        context.packageName == "com.bytedance.android.doubaoime"
    }
    var showReloadDialog by remember { mutableStateOf(false) }


    val navController = rememberNavController()


    val loaded = remember { readConfig(context) }
    var initialState by remember { mutableStateOf(loaded) }
    var state by remember { mutableStateOf(loaded) }

    val changed by remember(state, initialState) {
        derivedStateOf { state != initialState }
    }
    var showReadmeDialog by remember { mutableStateOf(false) }
    var readmeText by remember { mutableStateOf("加载中...") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            readmeText = try {
                URL("https://raw.githubusercontent.com/Xposed-Modules-Repo/com.haostoo.wetypehookr/main/README.md")
                    .readText()
            } catch (e: Exception) {
                "加载失败：${e.message}"
            }
        }
    }
    // 当前路由（用于判断标题）
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        topBar = {

// 对话框放在 Scaffold 内
            OverlayDialog(
                title = "立即重载输入法？",
                summary = if (isDoubaoIme)"配置已保存，是否立即重启豆包输入法使配置生效？" else "配置已保存，是否立即重启微信输入法使配置生效？",
                show = showReloadDialog,
                onDismissRequest = { showReloadDialog = false }
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        text = "稍后",
                        onClick = { showReloadDialog = false },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "立即重载",
                        onClick = {
                            showReloadDialog = false
                            if (isDoubaoIme)
                            {MainModule.killDoubaoHld(context) { message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }}
                            else{
                            MainModule.killWeTypeHld(context) { message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }}
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
            WindowDialog(
                title = "使用说明",
                show = showReadmeDialog,
                onDismissRequest = { showReadmeDialog = false },
                modifier = Modifier.heightIn(max = 700.dp)
            ) {
                val dismiss = LocalDismissState.current
                Column {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        MarkdownText(
                            markdown = readmeText,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    TextButton(
                        text = "确定",
                        onClick = { dismiss?.invoke() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            SmallTopAppBar(
                title = "设置",

                navigationIcon = {
                    when {
                        onClose != null -> {
                            IconButton(onClose) {
                                Icon(MiuixIcons.Back, "close")
                            }
                        }
                    }
                },

                actions = {
                    if (currentRoute == "settings") {
                        IconButton(
                            onClick = { showReadmeDialog = true },
                            holdDownState = showReadmeDialog
                        ) {
                            Icon(MiuixIcons.Help, contentDescription = "使用说明")
                        }
                        Button(
                            onClick = {
                                writeConfig(context, state)
                                initialState = state
                                showReloadDialog = true
                            },
                            enabled = changed
                        ) {
                            Text("保存")
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