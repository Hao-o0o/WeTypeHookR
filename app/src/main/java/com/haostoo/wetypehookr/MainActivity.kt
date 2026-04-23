package com.haostoo.wetypehookr

import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {

            MaterialTheme {

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    SettingsScreen(this@MainActivity)
                }
            }
        }
    }
}

///////////////////////////////////////////////////////

@Composable
fun SettingsScreen(context: Context) {

    val types = HapticType.values()

    // ⭐ 模式（分离）
    var downMode by remember {
        mutableStateOf(HapticConfig.get(context, "down_mode", 0))
    }

    var upMode by remember {
        mutableStateOf(HapticConfig.get(context, "up_mode", 0))
    }

    // ⭐ 系统震动
    var down by remember {
        mutableStateOf(
            HapticConfig.get(
                context,
                HapticConfig.KEY_DOWN,
                HapticFeedbackConstants.KEYBOARD_TAP
            )
        )
    }

    var up by remember {
        mutableStateOf(
            HapticConfig.get(
                context,
                HapticConfig.KEY_UP,
                HapticFeedbackConstants.KEYBOARD_TAP
            )
        )
    }

    // ⭐ 自定义参数
    var downDuration by remember {
        mutableStateOf(HapticConfig.get(context, "down_duration", 10))
    }

    var downStrength by remember {
        mutableStateOf(HapticConfig.get(context, "down_strength", 80))
    }

    var upDuration by remember {
        mutableStateOf(HapticConfig.get(context, "up_duration", 5))
    }

    var upStrength by remember {
        mutableStateOf(HapticConfig.get(context, "up_strength", 40))
    }

    // ⭐ 弹窗控制
    var showDialog by remember { mutableStateOf(false) }
    var isDownConfig by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        // ================= DOWN =================
        Text("按下震动", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(8.dp))

        HapticDropdown(
            types = types,
            selected = if (downMode == 0) down else -1,
            customLabel = if (downMode == 1) "${downDuration}ms / $downStrength" else null,
            onSelected = {

                if (it == -1) {
                    isDownConfig = true
                    downMode = 1
                    showDialog = true
                } else {
                    down = it
                    downMode = 0
                    HapticConfig.save(context, HapticConfig.KEY_DOWN, it)
                    HapticConfig.save(context, "down_mode", 0)
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ================= UP =================
        Text("抬起震动", style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(8.dp))

        HapticDropdown(
            types = types,
            selected = if (upMode == 0) up else -1,
            customLabel = if (upMode == 1) "${upDuration}ms / ${upStrength}" else null,
            onSelected = {

                if (it == -1) {
                    isDownConfig = false
                    upMode = 1
                    showDialog = true
                } else {
                    up = it
                    upMode = 0
                    HapticConfig.save(context, HapticConfig.KEY_UP, it)
                    HapticConfig.save(context, "up_mode", 0)
                }
            }
        )
    }

    // ================= 自定义弹窗 =================
    if (showDialog) {

        CustomHapticDialog(
            onConfirm = { duration, strength ->

                if (isDownConfig) {

                    downDuration = duration
                    downStrength = strength

                    HapticConfig.save(context, "down_duration", duration)
                    HapticConfig.save(context, "down_strength", strength)
                    HapticConfig.save(context, "down_mode", 1)

                } else {

                    upDuration = duration
                    upStrength = strength

                    HapticConfig.save(context, "up_duration", duration)
                    HapticConfig.save(context, "up_strength", strength)
                    HapticConfig.save(context, "up_mode", 1)
                }

                showDialog = false
            },
            onDismiss = {
                showDialog = false
            }
        )
    }
}
///////////////////////////////////////////////////////
// ✅ 通用下拉组件
///////////////////////////////////////////////////////

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HapticDropdown(
    types: Array<HapticType>,
    selected: Int,
    customLabel: String?,
    onSelected: (Int) -> Unit
) {

    var expanded by remember { mutableStateOf(false) }

    // ⭐ 当前显示文本
    val selectedLabel = when (selected) {
        -1 -> {
            if (customLabel != null)
                "自定义（$customLabel）"
            else
                "自定义"
        }
        else -> types.find { it.value == selected }?.label ?: "未知"
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {

        TextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("选择震动类型") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded)
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {

            // ⭐ 系统震动列表
            types.forEach { type ->

                DropdownMenuItem(
                    text = { Text(type.label) },
                    onClick = {
                        onSelected(type.value)
                        expanded = false
                    }
                )
            }

            // ⭐ 分割（可选，让UI更清晰）
            HorizontalDivider()

            // ⭐ 自定义选项（带参数展示）
            DropdownMenuItem(
                text = {
                    Text(
                        if (customLabel != null)
                            "自定义（$customLabel）"
                        else
                            "自定义"
                    )
                },
                onClick = {
                    onSelected(-1) // ⭐ -1 代表自定义
                    expanded = false
                }
            )
        }
    }
}
@Composable
fun CustomHapticDialog(
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {

    var durationText by remember { mutableStateOf("10") }
    var strengthText by remember { mutableStateOf("80") }

    val duration = durationText.toIntOrNull()
    val strength = strengthText.toIntOrNull()

    val isValid = duration != null &&
            strength != null &&
            duration in 1..100 &&
            strength in 1..255

    AlertDialog(
        onDismissRequest = onDismiss,

        title = {
            Text("自定义震动")
        },

        text = {

            Column {

                // ===== 时长 =====
                Text("时长 (ms)", style = MaterialTheme.typography.labelMedium)

                TextField(
                    value = durationText,
                    onValueChange = {
                        durationText = it.filter { c -> c.isDigit() }
                    },
                    singleLine = true,
                    placeholder = { Text("1 - 100") }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ===== 强度 =====
                Text("强度 (1-255)", style = MaterialTheme.typography.labelMedium)

                TextField(
                    value = strengthText,
                    onValueChange = {
                        strengthText = it.filter { c -> c.isDigit() }
                    },
                    singleLine = true,
                    placeholder = { Text("1 - 255") }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // ===== 错误提示 =====
                if (!isValid) {
                    Text(
                        text = "请输入有效范围：时长1-100ms，强度1-255",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },

        confirmButton = {
            Button(
                onClick = {
                    onConfirm(duration!!, strength!!)
                },
                enabled = isValid
            ) {
                Text("确定")
            }
        },

        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}