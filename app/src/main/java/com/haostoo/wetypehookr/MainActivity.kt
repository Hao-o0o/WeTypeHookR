package com.haostoo.wetypehookr

import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.DropdownMenuItem

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                SettingsScreen(this@MainActivity)
            }
        }
    }
}

///////////////////////////////////////////////////////

@Composable
fun SettingsScreen(context: Context) {

    val types = HapticType.values()

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Text(
            text = "按下震动",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        HapticDropdown(
            types = types,
            selected = down,
            onSelected = {
                down = it
                HapticConfig.save(context, HapticConfig.KEY_DOWN, it)
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "抬起震动",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        HapticDropdown(
            types = types,
            selected = up,
            onSelected = {
                up = it
                HapticConfig.save(context, HapticConfig.KEY_UP, it)
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
    onSelected: (Int) -> Unit
) {

    var expanded by remember { mutableStateOf(false) }

    val selectedLabel = types.find { it.value == selected }?.label ?: "未知"

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
        }
    }
}