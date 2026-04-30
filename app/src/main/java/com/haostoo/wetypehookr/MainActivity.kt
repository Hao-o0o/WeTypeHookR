package com.haostoo.wetypehookr

import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

    // ========================
    // ⭐ 使用 Saveable（解决切后台恢复默认值）
    // ========================

    var downMode by rememberSaveable {
        mutableIntStateOf(HapticConfig.get(context, "down_mode", 0))
    }

    var upMode by rememberSaveable {
        mutableIntStateOf(HapticConfig.get(context, "up_mode", 0))
    }

    var down by rememberSaveable {
        mutableIntStateOf(
            HapticConfig.get(
                context,
                HapticConfig.KEY_DOWN,
                HapticFeedbackConstants.KEYBOARD_TAP
            )
        )
    }

    var up by rememberSaveable {
        mutableIntStateOf(
            HapticConfig.get(
                context,
                HapticConfig.KEY_UP,
                HapticFeedbackConstants.KEYBOARD_TAP
            )
        )
    }

    var downDuration by rememberSaveable {
        mutableIntStateOf(HapticConfig.get(context, "down_duration", 10))
    }

    var downStrength by rememberSaveable {
        mutableIntStateOf(HapticConfig.get(context, "down_strength", 80))
    }

    var upDuration by rememberSaveable {
        mutableIntStateOf(HapticConfig.get(context, "up_duration", 5))
    }

    var upStrength by rememberSaveable {
        mutableIntStateOf(HapticConfig.get(context, "up_strength", 40))
    }

    // ========================
    // dialog state
    // ========================
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
            customLabel = if (upMode == 1) "${upDuration}ms / $upStrength" else null,
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
// Dropdown（保持原样）
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

    val selectedLabel = when (selected) {
        -1 -> if (customLabel != null) "自定义（$customLabel）" else "自定义"
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

            types.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.label) },
                    onClick = {
                        onSelected(type.value)
                        expanded = false
                    }
                )
            }

            HorizontalDivider()

            DropdownMenuItem(
                text = {
                    Text(if (customLabel != null) "自定义（$customLabel）" else "自定义")
                },
                onClick = {
                    onSelected(-1)
                    expanded = false
                }
            )
        }
    }
}

///////////////////////////////////////////////////////
// Dialog（不变）
///////////////////////////////////////////////////////

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

        title = { Text("自定义震动") },

        text = {

            Column {

                Text("时长 (ms)")
                TextField(
                    value = durationText,
                    onValueChange = { durationText = it.filter(Char::isDigit) }
                )

                Spacer(Modifier.height(12.dp))

                Text("强度 (1-255)")
                TextField(
                    value = strengthText,
                    onValueChange = { strengthText = it.filter(Char::isDigit) }
                )

                if (!isValid) {
                    Text(
                        "范围错误",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },

        confirmButton = {
            Button(
                onClick = { onConfirm(duration!!, strength!!) },
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