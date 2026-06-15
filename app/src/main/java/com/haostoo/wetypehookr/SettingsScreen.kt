package com.haostoo.wetypehookr

import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.haostoo.wetypehookr.MainModule.Companion.killDoubaoHld
import com.haostoo.wetypehookr.MainModule.Companion.killWeTypeHld
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class SettingsState(
    val downIndex: Int,
    val upIndex: Int,
    val downDuration: Float,
    val downStrength: Float,
    val upDuration: Float,
    val upStrength: Float,
    var downSystemIndex: Int,   // ✅ 新增
    var upSystemIndex: Int
)

@Composable
fun SettingsScreen(
    state: SettingsState,
    onStateChange: (SettingsState) -> Unit
) {
    val context = LocalContext.current
    val isDoubaoIme = remember {
        context.packageName == "com.bytedance.android.doubaoime"
    }

    fun loadCustomHapticPreview(context: android.content.Context): Pair<String, String> {
        val path = "/storage/emulated/0/Android/data/${context.packageName}/files/haostoo/config/customhaptic.json"
        val file = java.io.File(path)

        if (!file.exists()) {
            return Pair(
                "文件不存在：customhaptic.json",
                "文件不存在：customhaptic.json"
            )
        }

        return try {
            val json = JSONObject(file.readText())

            fun parse(key: String): String {
                val obj = json.optJSONObject(key)
                    ?: return "$key：未配置"

                val tArr = obj.optJSONArray("timings")
                    ?: return "$key：缺少 timings"

                val aArr = obj.optJSONArray("amplitudes")
                    ?: return "$key：缺少 amplitudes"

                if (tArr.length() != aArr.length()) {
                    error("$key: timings 与 amplitudes 长度不一致")
                }

                if (tArr.length() == 0) {
                    error("$key: 数组不能为空")
                }

                if (tArr.getLong(0) != 0L) {
                    error("$key: timings[0] 必须为 0")
                }

                val timings = LongArray(tArr.length()) {
                    tArr.getLong(it)
                }

                val amplitudes = IntArray(aArr.length()) {
                    aArr.getInt(it)
                }

                if (amplitudes.any { it !in 0..255 }) {
                    error("$key: amplitudes 值必须在 0~255 之间")
                }

                return buildString {
                    append("timings:\n")
                    append("[")
                    append(timings.joinToString(", "))
                    append("]\n\n")

                    append("amplitudes:\n")
                    append("[")
                    append(amplitudes.joinToString(", "))
                    append("]")
                }
            }

            Pair(
                parse("down"),
                parse("up")
            )

        } catch (e: Exception) {
            val msg = e.message ?: "未知错误"
            Pair(msg, msg)
        }
    }

    val options = listOf("无震动", "系统预设风格", "自定义", "自定义波形")
    val systemHaptics = listOf(
        "Virtual Key",
        "Virtual Key Release",

        "Keyboard Tap",
        "Keyboard Press",
        "Keyboard Release",

        "Long Press",
        "Context Click",
        "Confirm",
        "Reject",

        "Clock Tick",

        "Gesture Start",
        "Gesture End",
        "Gesture Threshold Activate",
        "Gesture Threshold Deactivate",

        "Drag Start",

        "Text Handle Move",

        "Segment Tick",
        "Segment Frequent Tick",

        "Toggle On",
        "Toggle Off"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp)
        ){
            Text(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                text = "ⓘ 根据HapticFeedbackConstants文档，系统预设风格提供了除去NoHaptic外共20种（下拉菜单可以滑动的意思）。由于Android版本不同和厂商魔改，可能出现无效/效果诡异，请自行体验确定。",
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                fontSize = 14.sp
            )
            Text(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                text = "❗❗❗ 在此页进行的任何修改都需要保存后并重启输入法进程生效❗❗❗",
                textDecoration = TextDecoration.Underline,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                fontSize = 14.sp
            )
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp)
        ) {

            // ===== 按下震动 =====
            OverlayDropdownPreference(
                title = "按下震动",
                items = options,
                selectedIndex = state.downIndex,
                onSelectedIndexChange = {
                    onStateChange(state.copy(downIndex = it))
                }
            )
            var view = LocalView.current
            if (state.downIndex == 1 ) run {
                OverlayDropdownPreference(
                    title = "按下系统震动风格",
                    items = systemHaptics,
                    selectedIndex = state.downSystemIndex,
                    onSelectedIndexChange = { index ->
                        onStateChange(state.copy(downSystemIndex = index))

                        // 👇 只有在“系统预设模式”下才触发预览震动
                        if (state.downIndex == 1) {
                            view.performHapticFeedback(hapticConstantForIndex(index))
                        }
                    },
                    //enabled = state.downIndex == 1 // 👈 系统预设模式
                )
            }
            if (state.downIndex == 2 ) run{
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "按下震动时长：${state.downDuration.toInt()} ms",
                        color = if (state.downIndex == 2) Color.Unspecified else Color.LightGray
                    )

                    Slider(
                        value = state.downDuration,
                        onValueChange = {
                            onStateChange(state.copy(downDuration = it))
                        },
                        valueRange = 10f..200f,
                        //enabled = state.downIndex == 2
                    )
                }

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "按下震动强度：${(state.downStrength * 100).toInt()} %",
                        color = if (state.downIndex == 2) Color.Unspecified else Color.LightGray
                    )

                    Slider(
                        value = state.downStrength,
                        onValueChange = {
                            onStateChange(state.copy(downStrength = it))
                        },
                        valueRange = 0f..1f,
                        //enabled = state.downIndex == 2
                    )
                }
            }
            if (state.downIndex == 3) {
                val preview = remember {
                    loadCustomHapticPreview(context)
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    insideMargin = PaddingValues(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = "按下（Down）",
                            fontSize = 13.sp
                        )

                        Text(
                            text = preview.first,
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                        )
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            )

            // ===== 抬起震动 =====
            OverlayDropdownPreference(
                title = "抬起震动",
                items = options,
                selectedIndex = state.upIndex,
                onSelectedIndexChange = {
                    onStateChange(state.copy(upIndex = it))
                }
            )
            view = LocalView.current
            if (state.upIndex == 1 ) run{
                OverlayDropdownPreference(
                    title = "抬起系统震动风格",
                    items = systemHaptics,
                    selectedIndex = state.upSystemIndex,
                    onSelectedIndexChange = { index ->
                        onStateChange(state.copy(upSystemIndex = index))

                        // 👇 只有在“系统预设模式”下才触发预览震动
                        if (state.upIndex == 1) {
                            view.performHapticFeedback(hapticConstantForIndex(index))
                        }
                    },
                    //enabled = state.upIndex == 1
                )
            }
            if (state.upIndex == 2 ) run{
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "抬起震动时长：${state.upDuration.toInt()} ms",
                        color = if (state.upIndex == 2) Color.Unspecified else Color.LightGray
                    )

                    Slider(
                        value = state.upDuration,
                        onValueChange = {
                            onStateChange(state.copy(upDuration = it))
                        },
                        valueRange = 10f..200f,
                        enabled = state.upIndex == 2
                    )
                }

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "抬起震动强度：${(state.upStrength * 100).toInt()} %",
                        color = if (state.upIndex == 2) Color.Unspecified else Color.LightGray
                    )

                    Slider(
                        value = state.upStrength,
                        onValueChange = {
                            onStateChange(state.copy(upStrength = it))
                        },
                        valueRange = 0f..1f,
                        enabled = state.upIndex == 2
                    )
                }
            }
            if (state.upIndex == 3) {
                val preview = remember {
                    loadCustomHapticPreview(context)
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    insideMargin = PaddingValues(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = "抬起（Up）",
                            fontSize = 13.sp
                        )

                        Text(
                            text = preview.second,
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant
                        )
                    }
                }
            }
        }

        // ===== 关于 =====
        var showAboutDialog by remember { mutableStateOf(false) }

        OverlayDialog(

            title = if(isDoubaoIme)"关于模块 | DoubaoIME" else "关于模块 | WeType",
            show = showAboutDialog,
            onDismissRequest = { showAboutDialog = false }
        ) {
            val moduleVersion = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
            val hostVersion = remember {
                try {
                    val info = context.packageManager.getPackageInfo(if(isDoubaoIme)"com.bytedance.android.doubaoime" else "com.tencent.wetype", 0)
                    "${info.versionName}(${
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                            info.longVersionCode
                        else
                            info.longVersionCode
                    })"
                } catch (e: Exception) { "未安装" }
            }

            Column(modifier = Modifier
                .padding(
                    start = 16.dp, end = 16.dp, bottom = 8.dp
                )
            ) {
                Text("模块版本：$moduleVersion")
                Text("宿主版本：$hostVersion")
            }

            ArrowPreference(
                title = "GitHub 仓库",
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        "https://github.com/Hao-o0o/WeTypeHookR".toUri()
                    )
                    context.startActivity(intent)
                }
            )

            HorizontalDivider()

            ArrowPreference(
                title = "酷安主页",
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        "https://www.coolapk.com/u/19859750".toUri()
                    )
                    context.startActivity(intent)
                }
            )
            TextButton(
                text = "确认",
                onClick = { showAboutDialog = false },
                modifier = Modifier.fillMaxWidth()
            )
        }



        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            ArrowPreference(
                title = if(isDoubaoIme)"关于模块 | DoubaoIME" else "关于模块 | WeType",
                summary = "版本信息与开发者",
                onClick = { showAboutDialog = true }
            )
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp)
        ){
            ArrowPreference(
                title = "恢复默认设置",
                summary = "恢复后请保存生效",
                onClick = {
                    val default = defaultSettings()
                    onStateChange(default)
                }
            )
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp)
        ){
            ArrowPreference(
                title = "重载输入法",
                summary = if(isDoubaoIme)"用于快速停止豆包输入法进程" else "需要授予微信输入法Root权限❗",
                onClick = {
                    if(isDoubaoIme){
                        killDoubaoHld(context) { message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                    else {
                        killWeTypeHld(context) { message ->
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }
}