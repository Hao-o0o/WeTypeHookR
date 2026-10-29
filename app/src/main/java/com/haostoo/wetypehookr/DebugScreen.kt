package com.haostoo.wetypehookr

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.io.File

/**
 * 一级 OverlayDialog：调试输出开关 / 清除日志 / 导出日志（按当前宿主）。
 */
@Composable
fun DebugDialog(
    show: Boolean,
    state: SettingsModel.SettingsState,
    onStateChange: (SettingsModel.SettingsState) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val zipFile = File(context.cacheDir, "wetype-logs.zip")
            if (LogStore.exportToZip(context, zipFile)) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        zipFile.inputStream().use { it.copyTo(out) }
                    }
                    Toast.makeText(context, "日志已导出", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
                zipFile.delete()
            } else {
                Toast.makeText(context, "没有可导出的日志", Toast.LENGTH_SHORT).show()
            }
        }
    }

    OverlayDialog(
        title = "调试与日志",
        show = show,
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        ) {
            SwitchPreference(
                title = "调试输出",
                summary = "开启后按分类写入宿主日志目录（保存后生效）",
                checked = state.debugLog,
                onCheckedChange = { on ->
                    onStateChange(state.copy(debugLog = on))
                }
            )

            ArrowPreference(
                title = "清除日志",
                summary = "删除当前宿主的全部日志",
                onClick = {
                    LogStore.clear(context)
                    Toast.makeText(context, "日志已清除", Toast.LENGTH_SHORT).show()
                }
            )

            ArrowPreference(
                title = "导出日志",
                summary = "打包当前宿主日志为 zip，选择保存位置",
                onClick = { exportLauncher.launch("wetype-logs.zip") }
            )
        }
        TextButton(
            text = "关闭",
            onClick = onDismissRequest,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
