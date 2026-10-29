# HOST_NOTES

各宿主的实测差异记录。真机验证后补全；未确认项一律视为「待验证」。

## 1. 寄生 Activity 的 proxy

模块 Activity 通过 `registerModuleActivities(proxy)` 注册，`proxy` 必须是宿主 Manifest 中真实存在、
`launchMode = standard`、无 ActionBar、启动无副作用的 Activity。候选表见 `HostCatalog.PROXY_ACTIVITY`。

| 宿主 | proxy | launchMode | 结论 |
| --- | --- | --- | --- |
| `com.tencent.wetype` | `com.tencent.wetype.plugin.hld.reactnative.activity.ImeMainSettingActivity` | 待验证 | 可打开（实测） |
| `com.bytedance.android.doubaoime` | `com.bytedance.android.doubaoime.activity.LauncherActivity` | 待验证 | 可打开（实测） |

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
| `com.tencent.wetype` | `com.tencent.wetype.plugin.hld.view.ImeRootView` | `dispatchTouchEvent` | 沿用 1.3.0，待回归 |
| `com.bytedance.android.doubaoime` | `com.bytedance.android.input.keyboard.KeyboardView` | `onTouchEvent` | 沿用 1.3.0，待回归 |

日志验证：`adb logcat -s WETYPE_HOOK` 应出现 `hook installed: <类>#<方法>@<宿主>`。

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
