package com.haostoo.wetypehookr

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

// 与设置页保持同一套间距：页面 16dp / 卡片内容 16dp（叠加卡片内缩 4dp = 20dp）
private val PagePadding = 16.dp
private val CardInset = 4.dp
private val ContentPadding = 16.dp
private val CardGap = 12.dp

/**
 * 设置板块：颜色模式 → 配置与日志 → 关于。
 */
@Composable
fun SettingsContent(
    state: SettingsModel.HostState?,
    readOnly: Boolean,
    insets: PaddingValues,
    onOpenDebug: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorModes = stringArrayResource(R.array.color_modes).toList()
    val exportedText = stringResource(R.string.toast_config_exported)
    val importedText = stringResource(R.string.toast_config_imported)
    val readFailed = stringResource(R.string.toast_read_file_failed)
    val badFormat = stringResource(R.string.error_import_format)

    var showSponsor by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(ConfigTransfer.exportJson(context).toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) exportedText else readFailed, Toast.LENGTH_SHORT).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val ok = text != null && withContext(Dispatchers.IO) { ConfigTransfer.importJson(context, text) }
            Toast.makeText(context, if (ok) importedText else badFormat, Toast.LENGTH_SHORT).show()
        }
    }

    val colorMode = state?.ui?.colorMode ?: 2

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = insets.calculateTopPadding())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PagePadding),
    ) {
        Spacer(Modifier.height(CardGap))

        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(CardInset)) {
            OverlayDropdownPreference(
                title = stringResource(R.string.title_color_mode),
                items = colorModes,
                selectedIndex = colorMode.coerceIn(0, colorModes.lastIndex),
                enabled = !readOnly,
                startAction = {
                    Icon(
                        MiuixIcons.Theme,
                        null,
                        modifier = Modifier.padding(end = 12.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                },
                onSelectedIndexChange = { mode ->
                    SettingsStore.updateAsync(context, context.packageName) {
                        it.copy(ui = it.ui.copy(colorMode = mode))
                    }
                },
            )
        }

        Spacer(Modifier.height(CardGap))

        // 配置导入导出 + 调试 + 帮助：同属「维护」类，放一张卡。
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(CardInset)) {
            ArrowPreference(
                title = stringResource(R.string.action_import_config),
                summary = stringResource(R.string.summary_import_config),
                enabled = !readOnly,
                startAction = {
                    Icon(
                        MiuixIcons.Import,
                        null,
                        modifier = Modifier.padding(end = 12.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                },
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
            )
            ArrowPreference(
                title = stringResource(R.string.action_export_config),
                summary = stringResource(R.string.summary_export_config),
                enabled = !readOnly,
                startAction = {
                    Icon(
                        MiuixIcons.UploadCloud,
                        null,
                        modifier = Modifier.padding(end = 12.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                },
                onClick = { exportLauncher.launch("wedouvibrator-config.json") },
            )
            ArrowPreference(
                title = stringResource(R.string.title_debug_logs),
                summary = stringResource(R.string.summary_debug_logs),
                startAction = {
                    Icon(
                        MiuixIcons.Notes,
                        null,
                        modifier = Modifier.padding(end = 12.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                },
                onClick = onOpenDebug,
            )
            ArrowPreference(
                title = stringResource(R.string.title_help),
                summary = stringResource(R.string.summary_help),
                startAction = {
                    Icon(
                        MiuixIcons.Help,
                        null,
                        modifier = Modifier.padding(end = 12.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                },
                onClick = onOpenHelp,
            )
        }

        Spacer(Modifier.height(CardGap))

        // 关于：四个入口同卡，赞助点开弹层。
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(CardInset)) {
            ArrowPreference(
                title = stringResource(R.string.title_repo),
                summary = AboutLinks.REPO,
                onClick = { openLink(context, AboutLinks.REPO) },
            )
            ArrowPreference(
                title = stringResource(R.string.title_coolapk),
                summary = AboutLinks.COOLAPK
                    ?: stringResource(R.string.link_unavailable),
                onClick = { openLink(context, AboutLinks.COOLAPK) },
            )
            ArrowPreference(
                title = stringResource(R.string.title_channel),
                summary = AboutLinks.CHANNEL
                    ?: stringResource(R.string.link_unavailable),
                onClick = { openLink(context, AboutLinks.CHANNEL) },
            )
            ArrowPreference(
                title = stringResource(R.string.title_sponsor),
                onClick = { showSponsor = true },
            )
        }

        Spacer(Modifier.height(CardGap))
        Spacer(Modifier.height(insets.calculateBottomPadding()))
    }

    if (showSponsor) {
        SponsorDialog(onDismiss = { showSponsor = false })
    }
}

/** 赞助弹层：二维码 + 一句致谢。 */
@Composable
private fun SponsorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.toast_saved_to_gallery)
    val saveFailed = stringResource(R.string.toast_save_failed)

    OverlayDialog(
        title = stringResource(R.string.title_sponsor),
        show = true,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.sponsor),
                contentDescription = stringResource(R.string.sponsor_qr_a11y),
                modifier = Modifier.size(200.dp),
            )
            Text(
                text = stringResource(R.string.sponsor_dialog_message),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        ArrowPreference(
            title = stringResource(R.string.action_save_image),
            onClick = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { AboutLinks.saveSponsor(context) }
                    Toast.makeText(context, if (ok) savedText else saveFailed, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

private fun openLink(context: Context, url: String?) {
    if (url.isNullOrBlank()) {
        Toast.makeText(context, context.getString(R.string.link_unavailable), Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_open_link_failed), Toast.LENGTH_SHORT).show()
    }
}

/** 调试与日志：开关、清除、导出 zip。 */
@Composable
fun DebugPage(
    state: SettingsModel.HostState?,
    readOnly: Boolean,
    insets: PaddingValues,
    onSettingsChange: (SettingsModel.SettingsState) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clearedText = stringResource(R.string.toast_logs_cleared)
    val exportedText = stringResource(R.string.toast_logs_exported)
    val emptyText = stringResource(R.string.toast_logs_empty)
    val failedTpl = stringResource(R.string.toast_export_failed, "%s")

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                val zip = File(context.cacheDir, "wedouvibrator-logs.zip")
                if (!LogStore.exportToZip(context, zip)) {
                    emptyText
                } else {
                    val result = runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            zip.inputStream().use { it.copyTo(out) }
                        }
                    }
                    zip.delete()
                    if (result.isSuccess) {
                        exportedText
                    } else {
                        failedTpl.format(result.exceptionOrNull()?.message ?: "")
                    }
                }
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = insets.calculateTopPadding())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PagePadding),
    ) {
        Spacer(Modifier.height(CardGap))
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(CardInset)) {
            SwitchPreference(
                title = stringResource(R.string.title_debug_logs),
                summary = stringResource(R.string.summary_debug_logs),
                checked = state?.settings?.debugLog == true,
                enabled = !readOnly,
                onCheckedChange = { enabled ->
                    val current = state?.settings ?: SettingsModel.defaultSettings()
                    onSettingsChange(current.copy(debugLog = enabled))
                },
            )
            ArrowPreference(
                title = stringResource(R.string.action_clear_logs),
                summary = stringResource(R.string.summary_clear_logs),
                enabled = !readOnly,
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { LogStore.clear(context) }
                        Toast.makeText(context, clearedText, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            ArrowPreference(
                title = stringResource(R.string.action_export_logs),
                summary = stringResource(R.string.summary_export_logs),
                onClick = { exportLauncher.launch("wedouvibrator-logs.zip") },
            )
        }
        Spacer(Modifier.height(CardGap))

        // 临时诊断卡：同一段 200ms 波形用四种方式各播一次，结果写 diag.log。
        val probeView = LocalView.current
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(CardInset)) {
            ArrowPreference(
                title = stringResource(R.string.title_haptic_probe),
                summary = stringResource(R.string.summary_haptic_probe),
                onClick = { VibratorProbe.play(probeView, VibratorProbe.Variant.RAW) },
            )
            ArrowPreference(
                title = stringResource(R.string.action_probe_raw),
                summary = "vibrate(createOneShot(200, 255))",
                onClick = { VibratorProbe.play(probeView, VibratorProbe.Variant.RAW) },
            )
            ArrowPreference(
                title = stringResource(R.string.action_probe_touch),
                summary = "createForUsage(USAGE_TOUCH)",
                onClick = { VibratorProbe.play(probeView, VibratorProbe.Variant.TOUCH) },
            )
            ArrowPreference(
                title = stringResource(R.string.action_probe_hardware),
                summary = "createForUsage(USAGE_HARDWARE_FEEDBACK)",
                onClick = { VibratorProbe.play(probeView, VibratorProbe.Variant.HARDWARE) },
            )
            ArrowPreference(
                title = stringResource(R.string.action_probe_haptic),
                summary = "performHapticFeedback(KEYBOARD_TAP)",
                onClick = { VibratorProbe.play(probeView, VibratorProbe.Variant.HAPTIC) },
            )
        }
        Spacer(Modifier.height(CardGap))
        Spacer(Modifier.height(insets.calculateBottomPadding()))
    }
}

/** 帮助：使用说明与波形格式（内置文案，不依赖网络）。 */
@Composable
fun HelpPage(insets: PaddingValues) {
    val lines = stringArrayResource(R.array.help_lines).toList()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = insets.calculateTopPadding())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PagePadding),
    ) {
        Spacer(Modifier.height(CardGap))
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(CardInset)) {
            Column(modifier = Modifier.padding(ContentPadding)) {
                Text(
                    text = stringResource(R.string.title_help),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                lines.forEach { line ->
                    Text(
                        text = line,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(CardGap))
        Spacer(Modifier.height(insets.calculateBottomPadding()))
    }
}

/** 作者相关外链与赞助图落盘。 */
private object AboutLinks {

    const val REPO: String = "https://github.com/Hao-o0o/WeTypeHookR"

    /** 待补充时保持 null，页面会显示占位文案。 */
    val COOLAPK: String? = null

    val CHANNEL: String? = null

    private const val ALBUM_DIR = "Pictures/WeDouVibrator"

    /** 把赞助二维码写入相册；失败返回 false。 */
    fun saveSponsor(context: Context): Boolean = runCatching {
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.sponsor) ?: return false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "wedouvibrator-sponsor.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM_DIR)
            } else {
                @Suppress("DEPRECATION")
                put(
                    MediaStore.Images.Media.DATA,
                    File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "WeDouVibrator/wedouvibrator-sponsor.png",
                    ).absolutePath,
                )
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        context.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        true
    }.getOrDefault(false)
}
