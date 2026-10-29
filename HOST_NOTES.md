# HOST_NOTES

各宿主的实测差异记录。真机验证后补全；未确认项一律视为「待验证」。

## 1. 寄生 Activity 的 proxy

模块 Activity 通过 `registerModuleActivities(proxy)` 注册，`proxy` 必须是宿主 Manifest 中真实存在、
`launchMode = standard`、无 ActionBar、启动无副作用的 Activity。当前取值见 `HostCatalog.SETTINGS_ACTIVITY`。

| 宿主 | proxy | launchMode | 结论 |
| --- | --- | --- | --- |
| `com.tencent.wetype` | `com.tencent.wetype.plugin.hld.reactnative.activity.ImeMainSettingActivity` | 待验证 | 待验证 |
| `com.bytedance.android.doubaoime` | `com.bytedance.android.doubaoime.activity.SettingsActivityNext` | 待验证 | 待验证 |

验证命令：

```bash
adb shell dumpsys package com.tencent.wetype | sed -n '/Activities:/,/Providers:/p'
adb shell dumpsys package com.bytedance.android.doubaoime | sed -n '/Activities:/,/Providers:/p'
```

判据：

- `launchMode` 不是 `standard` 时，二次打开可能只走 `onNewIntent`，页面不会重建 → 换候选；
- 主题带 ActionBar 或透明背景 → 换候选；
- 启动本身带副作用（例如自动弹权限）→ 换候选。

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
