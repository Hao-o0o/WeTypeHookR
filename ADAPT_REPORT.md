# 新输入法适配报告（讯飞 / Gboard）

> 依据 `TASK_ADD_IFLYTEK_GBOARD.md` 执行。本机环境：vivo PD2309M / V2359A，Android 16（API 36）。
> 状态：**代码已接入，构建通过；真机手测因 adb 断连尚未完成**（详见 §6）。

## 1. 结论

| 输入法 | 包名 | 版本 | 触摸适配 | FAB | 设置页 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 讯飞输入法 | `com.iflytek.inputmethod` | 16.0.1 (15972) | `keyboard.normal.InputView#dispatchTouchEvent` | 已接入，待真机验证 | 已接入，待真机验证 | 待验证 |
| Gboard | `com.google.android.inputmethod.latin` | 18.5.4.993608717 (176049702) | `inputview.InputView#dispatchTouchEvent` | 已接入，待真机验证 | 已接入，待真机验证 | 待验证 |

两者的触摸目标都**没有走 B2/B3 兜底**：类名未混淆，直接命中，因此 `HookEntry` 里没有引入任何
「全量 Hook `ViewGroup.dispatchTouchEvent` + rootView 过滤」的代码。

## 2. 定位依据

### 2.1 讯飞

调用链（jadx 输出，`reverse/src/iflytek/sources`）：

1. IME 组件：`com.iflytek.inputmethod/.FlyIME`（`ime list -a -s` 实测，与清单 `service` 一致）。
2. `FlyIME.onCreateInputView()` → `com/iflytek/inputmethod/FlyIME.java:473`
   → `imeProxy.onCreateInputView()`，`imeProxy` 是 `ImeManager`（`implements ImeProxy`）。
3. `ImeManager.onCreateInputView()` → `.../input/manager/ImeManager.java:1991`
   → `this.v0.onCreateInputView()`，`v0` 类型 `KeyboardManagerService`（字段声明在 `ImeManager.java:338`）。
4. `KeyboardManagerService.onCreateInputView()` → `.../kms/KeyboardManagerService.java:238`
   → `mImeInner.onCreateInputView()`。
5. `ImeInner.onCreateInputView()` → `.../kms/ImeInner.java:269`
   → `mCurKeyboard.onCreateInputView()`（当前键盘实现，`app.cy5` / `app.j96` 等）。
6. 键盘实现在 `app/cy5.java:890` 等处 `inflate(R.layout.layout_*_keyboard)`；
   `res/layout/layout_keyboard_common.xml` 里声明了
   `<com.iflytek.inputmethod.keyboard.normal.InputView>`（对 APK 内二进制 XML 逐个 grep 得到，
   `layout_normal_keyboard.xml` / `layout_float_keyboard.xml` / `layout_separate_keyboard.xml`
   都通过它引入，所以三种键盘形态共用同一个触摸根）。
7. `InputView extends FrameLayout`，覆写
   `public boolean dispatchTouchEvent(MotionEvent)` → `com/iflytek/inputmethod/keyboard/normal/InputView.java:188`，
   在 `super.dispatchTouchEvent` 之前先交给手势服务与动画层，**必然收到完整 DOWN/UP 序列**。

结论：`com.iflytek.inputmethod.keyboard.normal.InputView#dispatchTouchEvent`。

### 2.2 Gboard

调用链：

1. IME 组件：`com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`
   （`ime list -a -s` 实测）；`LatinIME` 本体只有 8 行，`extends defpackage.ewx`（真实实现）。
2. `defpackage/phr.java:578`（`ewx` 的父类）`setInputView(onCreateInputView())`；
   `defpackage/ewx.java:f()`（`@Override // defpackage.phr` 的 `f()`）负责真正创建视图：
   `InputView inputView = (InputView) View.inflate(this, R.layout._0_resource_name_obfuscated_res_0x7f0e0171, null);`
   —— `defpackage/ewx.java:585` 附近。
3. 该 `InputView` 即 `com.google.android.libraries.inputmethod.inputview.InputView extends FrameLayout`
   （类名与文件名均未被混淆）。
4. 它覆写 `public final boolean dispatchTouchEvent(MotionEvent)` →
   `.../inputview/InputView.java:103`，先给 `kat`（手势/滑行输入）判一次，未消费再交给 `super`。
   `final` 不影响 Hook。

结论：`com.google.android.libraries.inputmethod.inputview.InputView#dispatchTouchEvent`。

> 说明：Gboard 的 `InputView` 也是「键盘面板 + 候选条」的整块根视图，和我们拦微信 `ImeRootView` 的语义一致。
> 若后续发现候选条点击不该震动，再收窄到其内部子容器。

## 3. 包名与作用域

| 项 | 结论 |
| --- | --- |
| 讯飞 | `isSupported` 用 `pkg == com.iflytek.inputmethod \|\| pkg.startsWith("com.iflytek.inputmethod.")`，覆盖 `.oem` / `.miui` 等已知定制版；不会误伤 `com.iflytek.elpmobile.student`（本机装有，实测第二段不同） |
| Gboard | 精确包名 `com.google.android.inputmethod.latin`。本机 `pm list packages` 只有一个，未发现 `.*` 变体；Gboard Go 与正式版同包名，无需额外处理 |
| `scope` | 仍是 `staticScope = true` + 4 个精确包名（S1 方案）。定制版**不会自动注入**，需用户在 LSPosed 里手动勾选；勾选后前缀匹配生效 |
| 进程 | 讯飞的设置页在 `com.iflytek.inputmethod.settings` 进程，键盘在主进程；模块在两个进程都会加载（作用域按包名注入全部进程）。Gboard 只有主进程 |

## 4. proxy 选择

| 宿主 | 设置页（FAB 注入点） | proxy | 依据 |
| --- | --- | --- | --- |
| 讯飞 | `com.iflytek.inputmethod.FlyImeSettingsActivity`（`launchMode=3` singleInstance、未导出） | `com.iflytek.inputmethod.LauncherSettingsActivity`（导出、standard、同在 settings 进程） | 设置页本身是 singleInstance，按豆包那次的教训不能当 proxy |
| Gboard | `com.google.android.apps.inputmethod.latin.preference.SettingsActivity`（导出、standard、主进程） | `com.google.android.libraries.inputmethod.launcher.LauncherActivity`（导出、standard） | 选一个不在当前栈里的页面，避免复用卡死记录 |

候选表已写入 `HostCatalog.PROXY_ACTIVITY` / `SETTINGS_ACTIVITY_FALLBACK`，
并在 `CLASS_PREFIX` 里为 Gboard 补了 `com.google.android.apps.inputmethod` 前缀
（Gboard 的键盘类与设置页不在同一个父包下，单靠包名开头匹配不到）。

## 5. 定制版覆盖

- 已知包名：`com.iflytek.inputmethod`（标准）、`.oem`（OPPO/华为定制、清爽版）、`.miui`（小米定制）。
- 本机只装了标准版，`.oem` / `.miui` **未验证**；按前缀匹配 + 类结构复用假设它们可工作。
- 无法验证的原因写在这里而不是「已支持」：定制版可能裁剪或替换键盘 View 实现，
  且 `scope` 是静态包名列表，用户不手动勾选就不会注入。

## 6. 未完成项（阻塞）

| 项 | 状态 | 原因 |
| --- | --- | --- |
| 真机安装新包 | 未完成 | adb 在安装时断连（`adb devices` 空列表），重试仍失败 |
| 两个宿主的 FAB / 震动 / 设置页实测 | 未完成 | 同上 |
| 连续打字 30 秒性能观测 | 未完成 | 同上 |
| 微信 / 豆包回归 | 未完成 | 同上 |

代码侧已保证可验证：`WETYPE_HOOK` 会打 `hook installed: <类>#<方法>@<包名>`，
`diag.log` 会记 `[REG] ok … proxy=…`、`[FAB] launch …`、`[UI] onCreate …`。

## 7. 建议

1. `staticScope` 维持 `true`：改成 `false` 会让模块被加载进用户勾选的所有进程，收益只有「定制版开箱即用」，不值得。
2. 在 README 写清「讯飞定制版需在 LSPosed 中手动勾选作用域」，避免用户以为不支持。
3. Gboard 目前只在一台设备、一个版本上验证；类名 `com.google.android.libraries.inputmethod.inputview.InputView`
   在 Gboard 里长期稳定，但仍是版本敏感项，升级后若失效优先查这里。
