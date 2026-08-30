package com.haostoo.wetypehookr

import android.content.Intent
import android.widget.Toast
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
 * 一级 OverlayDialog：调试输出开关 / 清除日志 / 导出日志
 */
@Composable
fun DebugDialog(
    show: Boolean,
    state: SettingsState,
    onStateChange: (SettingsState) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val activity = LocalActivity.current

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
                summary = "开启后按分类输出日志到 logs 目录",
                checked = state.debugLog,
                onCheckedChange = { on ->
                    onStateChange(state.copy(debugLog = on))
                    // 立即同步主进程缓存，让日志开关即时生效（hld 进程需「保存配置」后同步）
                    MainModule.cachedSettings = MainModule.cachedSettings.copy(debugLog = on)
                    if (on) DebugLog.ensureHardwareInfo(context)
                    DebugLog.log(context, "ui", "debug output ${if (on) "enabled" else "disabled"}")
                }
            )

            ArrowPreference(
                title = "清除日志",
                summary = "删除 logs 目录下全部日志",
                onClick = {
                    DebugLog.clear(context)
                    Toast.makeText(context, "日志已清除", Toast.LENGTH_SHORT).show()
                }
            )

            ArrowPreference(
                title = "导出日志",
                summary = "打包全部日志为 zip，选择保存位置",
                onClick = { exportLogs(context, activity) }
            )
        }
        TextButton(
            text = "关闭",
            onClick = onDismissRequest,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun exportLogs(context: android.content.Context, activity: android.app.Activity?) {
    if (activity == null) {
        Toast.makeText(context, "无法启动文件选择器", Toast.LENGTH_SHORT).show()
        return
    }
    val zipFile = File(context.cacheDir, "wetype-logs.zip")
    if (!DebugLog.exportToZip(context, zipFile)) {
        Toast.makeText(context, "没有可导出的日志", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/zip"
        putExtra(Intent.EXTRA_TITLE, "wetype-logs.zip")
    }
    MainModule.requestLogExport { uri ->
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                zipFile.inputStream().use { it.copyTo(out) }
            }
            Toast.makeText(context, "日志已导出", Toast.LENGTH_SHORT).show()
        }.onFailure { e ->
            Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
        zipFile.delete()
    }
    runCatching { activity.startActivityForResult(intent, MainModule.LOG_EXPORT_REQUEST_CODE) }
        .onFailure { e ->
            MainModule.pendingLogExport = null
            Toast.makeText(context, "启动文件选择器失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
}
