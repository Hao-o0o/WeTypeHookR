# HOST_NOTES

各宿主的实测差异记录。真机验证后补全；未确认项一律视为「待验证」。

## 1. 寄生 Activity 的 proxy

模块 Activity 通过 `registerModuleActivities(proxy)` 注册，`proxy` 必须是宿主 Manifest 中真实存在、
`launchMode = standard`、无 ActionBar、启动无副作用的 Activity。候选表见 `HostCatalog.PROXY_ACTIVITY`。

| 宿主 | proxy | launchMode | 结论 |
| --- | --- | --- | --- |
| `com.tencent.wetype` | `com.tencent.wetype.plugin.hld.reactnative.activity.ImeMainSettingActivity` | 待验证 | 可打开（2026-10-30 实测） |
| `com.bytedance.android.doubaoime` | `com.bytedance.android.doubaoime.activity.LauncherActivity` | 待验证 | 可打开（2026-10-30 实测） |
| `com.iflytek.inputmethod` | `com.iflytek.inputmethod.LauncherSettingsActivity` | 待验证（未声明 → standard） | 新增，待真机验证 |
| `com.google.android.inputmethod.latin` | `com.google.android.libraries.inputmethod.launcher.LauncherActivity` | 待验证（未声明 → standard） | 新增，待真机验证 |

豆包为什么不用 `SettingsActivityNext`（实测踩过的坑）：

- 它带 `signature` 权限 `com.bytedance.android.doubaoime.permission.activity.SettingsActivityNext`；
- 更关键的是**记录复用**：一旦某次启动留在栈里的实例没画出来（`reportedDrawn=false`），
  之后每次 start 都命中同一条 `ActivityRecord`，走 `singleTop` 语义只回调 `onNewIntent`，
  根本不会再实例化 → 表现就是「点了没反应」，而且 `startActivity` 不报错。
- 换成当前不在栈里的 `LauncherActivity` 后，每次点击都会创建新记录，代理替换正常。

由此得到的选 proxy 规则：**导出、无权限限制、且当前不在 Activity 栈里**。

验证命令：

```bash
adb shell dumpsys package com.tencent.wetype | sed -n '/Activities:/,/Providers:/p'
adb shell dumpsys package com.bytedance.android.doubaoime | sed -n '/Activities:/,/Providers:/p'
adb shell dumpsys activity activities | grep -A3 记录里的 ActivityRecord
```

判据：

- `launchMode` 不是 `standard` 时，二次打开可能只走 `onNewIntent`，页面不会重建 → 换候选；
- 记录已存在且 `reportedDrawn=false` → 该 proxy 已被污染，换候选；
- 主题带 ActionBar 或透明背景 → 换候选；
- 启动本身带副作用（例如自动弹权限）→ 换候选。

## 1.1 设置页打不开时的定位步骤

1. 打开该宿主的设置页，点一次 FAB。
2. 取日志：

```bash
adb logcat -c && adb logcat -s WETYPE_FAB WETYPE_HOOK WETYPE_UI
```

3. 关注三行：
   - `launch settings: host=… proxy=… proc=…` → 说明点击生效，并给出当前选中的 proxy 与所在进程；
   - `launch SettingsActivity failed: host=… proxy=…` 后的异常：`ActivityNotFoundException` = proxy 不在宿主清单，`SecurityException` = 权限受限；
   - 紧随其后的 `host=… proc=… activities=N` 与 `activity: …` 列表 = 宿主真实声明的全部 Activity，用其中的设置页类名替换 `HostCatalog.SETTINGS_ACTIVITY_FALLBACK` 里的候选。

## 2. 触摸 Hook 目标

| 宿主 | 类 | 方法 | 结论 |
| --- | --- | --- | --- |
| `com.tencent.wetype` | `com.tencent.wetype.plugin.hld.view.ImeRootView` | `dispatchTouchEvent` | 沿用，2026-10-30 实测有震动 |
| `com.bytedance.android.doubaoime` | `com.bytedance.android.input.keyboard.KeyboardView` | `onTouchEvent` | 沿用，2026-10-30 实测有震动 |
| `com.iflytek.inputmethod` | `com.iflytek.inputmethod.keyboard.normal.InputView` | `dispatchTouchEvent` | 新增，待真机验证 |
| `com.google.android.inputmethod.latin` | `com.google.android.libraries.inputmethod.inputview.InputView` | `dispatchTouchEvent` | 新增，待真机验证 |

日志验证：`adb logcat -s WETYPE_HOOK` 应出现 `hook installed: <类>#<方法>@<宿主>`。

## 2.1 新宿主的进程与作用域

| 宿主 | 键盘进程 | 设置页进程 | 备注 |
| --- | --- | --- | --- |
| 讯飞 | `com.iflytek.inputmethod` | `com.iflytek.inputmethod.settings` | 设置页 `FlyImeSettingsActivity` 是 `launchMode=3`(singleInstance)，只能当 FAB 注入点、不能当 proxy |
| Gboard | `com.google.android.inputmethod.latin` | 同主进程 | 设置页与键盘类不在同一父包（`apps.inputmethod` vs `libraries.inputmethod`），类名兜底需两个前缀 |

讯飞定制版（`.oem` / `.miui`）不在静态 `scope` 里，需用户在 LSPosed 手动勾选；勾选后 `isSupported` 的前缀匹配生效。

## 2.2 系统级输入法震动接管（vivo OriginOS）

本机（Android 16 / OriginOS）的 `android.os.vibrator.SystemVibratorExtension` 内置了一份输入法白名单：

```java
VIBRATOR_IME_LIST = { OCTOPUS, BAIDU, QQ_PINYIN, IFLYTEK, LATIN, SOUXIN,
                      FACEMOJI, SWIFT, KK, WEIXIN, BAIDU_CUSTOM }
// WEIXIN = com.tencent.wetype
// LATIN  = com.google.android.inputmethod.latin
```

`SystemVibrator.vibrate(uid, opPkg, effect, reason, attributes)` 在 `IS_LINEAR_MOTOR` 为真时：

```java
int id = SystemVibratorExtension.inputMethodCastToEffectID(opPkg, effect);
if (id > 0) {
    vibratorPro(id, -1L, 2);   // 141~150 号内置效果
    return;                    // 我们的 effect 与 attributes 全部作废
}
```

判定条件：`effect instanceof VibrationEffect.Composed && VIBRATOR_IME_LIST.contains(opPkg)`，
命中后按 `inputMethodTimeToId()` 把时长映射成 141~150 号内置效果。

由此产生的结果：

- 名单内的输入法（微信、Gboard、讯飞、搜狗、百度…）发自定义 `Composed` 震动时，
  时长与波形被替换成 vivo 内置短效果，表现为「自定义震动被压成一下弱震」；
  转换发生在 `attributes` 被使用之前，**改 usage 无效**。
- `com.bytedance.android.doubaoime` **不在**名单内，震动原样下发，所以豆包正常。
- 「系统预设」走 `performHapticFeedback`，不经过该分支，始终正常。
- 能绕过判定的类型只有 `RichTapVibrationEffect` 的 `PatternHe` / `PatternHeParameter` /
  `ExtPrebaked`，均为 RichTap 私有格式（`createPatternHeWithParam` 要求 17 个 int、
  首位 4096/4097），没有 SDK 构造不出来；`Prebaked` 等其它类型在线性马达设备上会被直接丢弃。
- **结论：标准 API 无法绕过，属系统级限制。**

验证命令与判据：

```bash
adb shell dumpsys vibrator_manager | grep -B2 -A3 "com.tencent.wetype"
```

| 场景 | usage | played |
| --- | --- | --- |
| 名单内（微信 / Gboard） | `TOUCH` | `Stereo{vibrator(id=0): null}` |
| 名单外（豆包 / 讯飞不转换时） | `UNKNOWN` | `Step=200ms(amplitude=1.00)` |

## 3. 已知差异

- 跨宿主数据目录不可互访：设置页只能读写自己所在宿主的 `<host>/files/haostoo/`。
  因此「切换宿主」在本形态下必然只读，当前实现按此收敛：每个输入法内只管理自己的配置。
- 资源包 ID 使用 `0x64`；同一宿主若已装其他模块占用该段，需改到 `0x65`–`0x7e` 并重新验证。

## 4. 配置文件

- 唯一快照：`<host>/files/haostoo/config/state.json`（`schema = 3`）。
- 旧 `settings.json` / `haptic.json` / `ui.json` / `config.xml` 迁移后移入 `<host>/files/haostoo/config/legacy/`，不再读取。
- 内容非法时改名为 `state.bad.<时间戳>.json`，随后按默认值运行并写 `WETYPE_CFG` 日志。

```bash
adb shell ls /sdcard/Android/data/com.tencent.wetype/files/haostoo/config
adb shell cat /sdcard/Android/data/com.tencent.wetype/files/haostoo/config/state.json
```
