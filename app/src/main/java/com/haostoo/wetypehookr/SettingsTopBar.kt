package com.haostoo.wetypehookr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页顶栏：主标题为模块名（二级页显示页名），副标题为当前宿主名。
 *
 * miuix 的 `TopAppBar` 的 `title` 只接受 `String`，做不了两行结构与点击区域，
 * 因此用 `Surface + Row` 自绘，高度与 miuix 顶栏一致（56dp）。
 * 标题区整块可点 → 打开宿主选择。
 */
@Composable
fun SettingsTopBar(
    subtitle: String,
    onBack: (() -> Unit)?,
    onSwitchHost: () -> Unit,
    modifier: Modifier = Modifier,
    titleOverride: String? = null,
) {
    Surface(color = MiuixTheme.colorScheme.surface, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.action_back))
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onSwitchHost)
                    .padding(
                        start = if (onBack == null) 16.dp else 8.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = 4.dp,
                    ),
            ) {
                Text(
                    text = titleOverride ?: stringResource(R.string.app_name),
                    style = MiuixTheme.textStyles.headline2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        MiuixIcons.Tune,
                        stringResource(R.string.action_switch_host),
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(12.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }
        }
    }
}
