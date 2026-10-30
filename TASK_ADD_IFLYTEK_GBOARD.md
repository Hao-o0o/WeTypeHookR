# 任务书：适配讯飞输入法与 Gboard（含厂商定制变体）

> **文档性质**：可执行任务书，面向对本项目 0 了解的执行代理。
> **前置文档**：`DEVELOPMENT_GUIDE.md`（架构与规范）、`TASK_HOST_SETTINGS_MIGRATION.md`（宿主内设置页与配置，含 P1.5 待办）。
> **本任务只做一件事**：把两个新输入法接入现有的「触摸 → 震动 + 宿主内设置页」体系，并保持与微信输入法 / 豆包输入法完全一致的适配方式。
> **优先级**：先拉 APK 并报告（P0），再动代码。

---

## 0. 使用说明

### 0.1 第一步必须做什么

1. 确认 `adb devices` 能看到设备；
2. 按 §5 P0 拉取两个输入法的 APK（含 split）到 `reverse/<包名>/`；
3. **立刻向用户报告**：APK 已拉取完成（列出路径、versionName、versionCode、split 列表），**可以断开 adb 连接**；
4. 之后所有反编译、定位、编码工作都基于本地 APK 进行，不再需要设备。

> 报告模板见 §10.1。在用户确认前不要开始改动 `app/`。

### 0.2 完成定义（DoD）

1. 两个输入法在其设置页出现 FAB，点击可打开模块设置页（运行在该输入法进程内）。
2. 打字时按下 / 抬起各触发一次震动，四种模式均生效，改配置即时生效。
3. 包名匹配不写死：讯飞按前缀匹配全部定制变体；Gboard 按精确包名匹配并预留前缀扩展点。
4. `assembleRelease` 通过；真机手测通过 §9。
5. 产出 `ADAPT_REPORT.md`（§10.2），逐项列出适配结果与无法适配的原因。
6. 提交在 `dev` 分支，不合并 `master`。

### 0.3 边界

- 只改模块代码与文档，**不改宿主 APK、不注入系统进程、不改 LSPosed 数据库**。
- 设备操作仅限：`pm path` / `pm list packages` / `dumpsys` / `pull` / `install` / `am force-stop <目标输入法>`。不重启手机、不卸载用户应用。
- 遇到无法适配的项：记录 → 跳过 → 写进报告，**不要**用「Hook 所有 App」「Hook 框架 View 全量事件」这类无差别方案硬顶，除非报告里明确说明并等用户批准。

---

## 1. 目标与范围

| 输入法 | 标准包名 | 定制/变体 | 本任务是否必须验证 |
| --- | --- | --- | --- |
| 讯飞输入法 | `com.iflytek.inputmethod` | `com.iflytek.inputmethod.oem`、`com.iflytek.inputmethod.miui`，以及其它 `com.iflytek.inputmethod.*` | 标准版必须；定制版只能靠前缀匹配 + 类结构复用，无法在本机验证 |
| Gboard | `com.google.android.inputmethod.latin` | Gboard Go 与正式版**同包名**，仅 APK 文件名/版本后缀不同 | 标准版必须 |

---

## 2. 联网调研结论（Gboard 包名与讯飞变体）

> 以下为公开资料结论，**必须用真机 `pm list packages` / `ime list -a` 复核**，复核结果写入报告。

### 2.1 Gboard 是否存在其他分发版本

| 结论 | 依据 |
| --- | --- |
| 正式版与 **Gboard Go** 共用 `com.google.android.inputmethod.latin` | GMS 应用对照表中 Gboard 对应 `LatinImeGoogle`、Gboard Go 对应 `LatinImeGoogleGo`，两者包名均写作 `com.google.android.inputmethod.latin` |
| Gboard Go 是同一包名的「lite」构建，会**覆盖安装**在正式版之上 | APKMirror 该版本标注 `App: Gboard Go`、`Package: com.google.android.inputmethod.latin`、版本后缀 `-lite_release`；Android Police 也说明它「installs on top of the standard app」 |
| 未发现 `com.google.android.inputmethod.latin.*` 之类的独立分发包 | 上述来源与 Play 商店条目均只有这一个包名 |

**推论**：Gboard 侧只需匹配 `com.google.android.inputmethod.latin` 一个包名即可覆盖正式版与 Go 版；`com.android.inputmethod.latin`（AOSP 键盘）是**另一个应用**，不要误匹配。

**实现建议**：Gboard 用「精确包名」，但在 `HostCatalog` 里预留 `com.google.android.inputmethod.` 前缀匹配能力（未来若出现变体，改一行开关即可），不要现在就把前缀打开——AOSP 键盘与部分 OEM 预置键盘的包名结构不同，前缀过宽会误伤。

### 2.2 讯飞的定制版本

| 包名 | 说明 | 依据 |
| --- | --- | --- |
| `com.iflytek.inputmethod` | 标准版（IME 组件 `com.iflytek.inputmethod/.FlyIME`） | 常见输入法 ID 对照表 |
| `com.iflytek.inputmethod.oem` | 厂商定制版（OPPO 版、华为定制版、所谓「清爽版」均为该包名） | 多个下载站的应用信息页一致标注该包名 |
| `com.iflytek.inputmethod.miui` | 小米定制版 | 多个下载站的应用信息页标注该包名 |

**推论**：讯飞用「`com.iflytek.inputmethod` 或 `com.iflytek.inputmethod.` 前缀」匹配，可覆盖已知三种与未来新增的 `.*` 变体。注意用 `pkg == base || pkg.startsWith("$base.")`，避免误匹配 `com.iflytek.inputmethodxxx` 这类不存在的同前缀串。

**风险**：定制版可能裁剪/替换键盘 View 实现。本机只装了标准版，定制版只能做静态复用假设，无法验证——必须在报告里写清「未验证」。

---

## 3. 现有实现回顾（适配必须与之一致）

| 能力 | 现有实现 | 位置 |
| --- | --- | --- |
| 入口 | `@YukiHookLibXposedEntry(targetApiVersion=102, scope=[…], staticScope=true)` + `encase { loadApp(host) { … } }` | `HookEntry.kt` |
| 宿主表 | `HostCatalog.ALL` / `isSupported` / `touchTarget` / `labelRes` / `SETTINGS_ACTIVITY_FALLBACK` / `PROXY_ACTIVITY` / `resolveSettingsActivity` | `HostCatalog.kt` |
| 触摸 Hook | 反编译得到宿主根 View 类 + `dispatchTouchEvent`（微信）或 `onTouchEvent`（豆包），`before` 里调 `HapticEngine.dispatch` | `HookEntry.installTouchHooks` |
| 震动 | `HapticEngine.dispatch(view, event)` → 按下 / 抬起 / 多指；四种模式 | `HapticEngine.kt` |
| FAB | Hook `Activity.onResume`，命中白名单设置页后 `HostFabInjector.attach` | `HookEntry.installFabHook` |
| 设置页 | `registerModuleActivities(proxy)` + `SettingsActivity : BaseHostActivity(), ModuleActivity` | `HookEntry` / `SettingsActivity.kt` |
| 配置 | 宿主目录 `files/haostoo/config/*.json`，文件监听热更新 | `SettingsStore.kt` |

**两条硬约束（决定了改造方式）**：

1. `loadApp(name)` 是**精确包名**匹配。要支持未知变体，必须改成在 `encase` 内用 `packageName` 做模式判断。
2. `staticScope = true` 时，注入范围由注解里的 `scope` 列表决定，**只能是精确包名**。未知变体不会被注入——这是本任务最大的结构性限制，处理方式见 §4.2。

---

## 4. 包名与作用域策略（不写死）

### 4.1 运行时匹配

在 `HostCatalog` 里增加「家族（Family）」概念，包名判断集中一处：

```kotlin
object HostCatalog {
    const val WETYPE = "com.tencent.wetype"
    const val DOUBAO = "com.bytedance.android.doubaoime"
    const val IFLYTEK = "com.iflytek.inputmethod"
    const val GBOARD = "com.google.android.inputmethod.latin"

    /** 已确认可精确枚举的包名（用于 scope 与 UI 列表）。 */
    val ALL: List<String> = listOf(WETYPE, DOUBAO, IFLYTEK, GBOARD)

    /** 运行时判定：前缀家族 + 精确包名。 */
    fun isSupported(pkg: String): Boolean =
        pkg == WETYPE || pkg == DOUBAO || pkg == GBOARD ||
        pkg == IFLYTEK || pkg.startsWith("$IFLYTEK.")

    /** 是否属于某个家族（决定用哪套触摸目标与文案）。 */
    fun familyOf(pkg: String): String? = when {
        pkg == WETYPE -> WETYPE
        pkg == DOUBAO -> DOUBAO
        pkg == GBOARD -> GBOARD
        pkg == IFLYTEK || pkg.startsWith("$IFLYTEK.") -> IFLYTEK
        else -> null
    }
}
```

改造点：

- `HookEntry.onHook()` 里把 `HostCatalog.ALL.forEach { loadApp(it) { … } }` 改成
  `encase { if (HostCatalog.isSupported(packageName)) { … } }`，其余装配逻辑不变。
- 所有按宿主编排的表（触摸目标 / proxy / 文案）从「按包名」改成「按 `familyOf(pkg)` 取，取不到再回退到精确包名」。

### 4.2 scope 的两难与处理

| 方案 | 做法 | 代价 |
| --- | --- | --- |
| S1（默认采用） | 注解 `scope` 里列 4 个精确包名，`staticScope = true` | 定制版（如 `.oem` / `.miui`）**不会被注入**，除非用户手动加作用域 |
| S2（预案） | `staticScope = false`，由用户在 LSPosed 里勾选任意应用，模块内用 `isSupported` 过滤 | 用户必须手动勾选；模块会被加载进用户勾选的所有进程（过滤后立即 return，开销可忽略） |

**决策**：本任务按 S1 交付，并在 README / 报告里写明「定制版需在 LSPosed 中手动勾选作用域」；如果用户明确要求开箱即用支持定制版，再切 S2（切之前要确认 `encase` 在非目标进程里提前 return 的路径足够轻）。

> 提醒：`scope` 只是「推荐作用域」，LSPosed 允许用户手动添加任意包。S1 下用户手动勾选定制版后，`isSupported` 的前缀匹配会生效，功能可用——这一点要写进用户文档。

### 4.3 包可见性

- 设置页运行在宿主进程内，查询**自身**包名/版本不需要 `<queries>`。
- `AndroidManifest.xml` 的 `<queries>` 仍建议补齐 4 个已知包名，供「宿主切换」里查询另一个宿主的版本。
- 定制版无法枚举 → 切过去时版本显示「未知」属预期，不报错。

---

## 5. 阶段计划与闸门

### P0 — 拉取 APK（先做，做完就报告）

```bash
# 1) 确认安装与包名
adb shell pm list packages | grep -Ei "iflytek|inputmethod"
adb shell ime list -a -s            # 拿到 IME 组件全名与所属包

# 2) 版本与拆分信息
adb shell dumpsys package com.iflytek.inputmethod | grep -E "versionName|versionCode|primaryCpuAbi"
adb shell dumpsys package com.google.android.inputmethod.latin | grep -E "versionName|versionCode|primaryCpuAbi"

# 3) 逐个 APK 路径（Gboard 通常是 split，会有多行）
adb shell pm path com.iflytek.inputmethod
adb shell pm path com.google.android.inputmethod.latin

# 4) 拉取到仓库外的临时目录（不要提交进 git）
mkdir -p reverse/iflytek reverse/gboard
adb pull /data/app/.../base.apk reverse/iflytek/base.apk
adb pull /data/app/.../split_config.arm64_v8a.apk reverse/gboard/split_config.arm64_v8a.apk
# …每个 pm path 输出都 pull 一次，保持同名

# 5) 记录校验信息
sha256sum reverse/*/*.apk > reverse/SHA256SUMS.txt
```

**闸门 P0**

- [ ] 两个包都成功 pull，split 齐全（与 `pm path` 行数一致）。
- [ ] 记录 `versionName` / `versionCode` / `minSdk` / ABI。
- [ ] 已向用户报告「APK 已拉取，可以断开 adb」。

> `reverse/` 目录加入 `.gitignore`（或在报告里说明不提交），避免把几十 MB 的 APK 塞进仓库。

### P1 — 反编译与定位触摸入口

```bash
# 资源与清单（base 即可；需要完整资源时再 apktool 合并 split）
apktool d -f -s reverse/iflytek/base.apk -o reverse/out/iflytek
apktool d -f -s reverse/gboard/base.apk  -o reverse/out/gboard

# Java 源码（多 dex 一次搞定）
jadx -d reverse/src/iflytek reverse/iflytek/base.apk
jadx -d reverse/src/gboard  reverse/gboard/base.apk
```

定位顺序（**与现有宿主一致**）：

1. 先从 `ime list -a` / `dumpsys input_method` 拿到 IME 的 Service 类名（这是系统记录的，必然准确）。
2. 在 jadx 里找该 Service 的 `onCreateInputView()` / `onStartInputView()`，看它把哪个 View 交给 `setInputView(...)`。
3. 顺着这个 View 往上/往下找承载按键触摸的容器：优先找**覆写了 `dispatchTouchEvent(MotionEvent)`** 的类，其次 `onTouchEvent(MotionEvent)`。
4. 判定「可用」的标准：
   - 方法存在且非 `final` 之外无特殊限制（能 hook 即可，`final` 不影响 hook 方法本身）；
   - 该方法能收到完整的 DOWN / UP 序列（`ViewGroup` 的 `dispatchTouchEvent` 通常满足；单个子 View 的 `onTouchEvent` 可能只收到部分）；
   - 类名若被混淆，记录「混淆但可定位」，并在报告里标注版本脆弱性。

**关于混淆的关键事实**：`dispatchTouchEvent` / `onTouchEvent` 是框架回调，**方法名不会被 R8 改写**；被改写的只有类名。所以「找未混淆的触摸类」在 Gboard 上大概率失败，但「找到覆写了这两个方法的具体类」仍然可行——只是类名可能是 `a.b.c`。

**定位记录表**（填完写进报告）：

| 项 | 讯飞 | Gboard |
| --- | --- | --- |
| IME Service 组件 | `?` | `com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`（待核实） |
| 根 View 类 | `?` | `?` |
| Hook 方法 | `?` | `?` |
| 是否混淆 | `?` | `?` |
| 依据（文件/行/调用链） | `?` | `?` |

**闸门 P1**

- [ ] 两个输入法都拿到「类名 + 方法名」，或明确判定为「不可定位」并写下原因。
- [ ] 对每个候选，说明它为什么能收到 DOWN/UP（调用链证据），不接受「看起来像」。

### P2 — 触摸 Hook 适配

按现有方式接入，不改 `HapticEngine`：

```kotlin
// HostCatalog
private val TOUCH_TARGET = mapOf(
    WETYPE to ("com.tencent.wetype.plugin.hld.view.ImeRootView" to "dispatchTouchEvent"),
    DOUBAO to ("com.bytedance.android.input.keyboard.KeyboardView" to "onTouchEvent"),
    IFLYTEK to ("<P1 得到的类>" to "<方法>"),
    GBOARD to ("<P1 得到的类>" to "<方法>"),
)
```

`HookEntry.installTouchHooks()` 已经做了「多 ClassLoader 轮询 + 失败只记日志」，新宿主复用即可，不要另写一套。

**Gboard 混淆的兜底方案（按顺序尝试，前一个失败才用下一个）**：

| 方案 | 做法 | 前提 / 代价 |
| --- | --- | --- |
| B1 | 直接 hook 反编译得到的类（可能混淆） | 类名随版本变化，需在报告里标「版本敏感」 |
| B2 | hook `InputMethodService.onStartInputView(EditorInfo, boolean)`（类名来自 manifest，稳定）缓存 `window.window.decorView`；再 hook `android.view.ViewGroup.dispatchTouchEvent`，用 `view.rootView === decorView` 过滤 | 全进程热路径，需实测按键延迟；只在 Gboard 进程启用 |
| B3 | hook `com.android.internal.policy.DecorView#dispatchTouchEvent`（隐藏类），仅命中 IME 窗口 | 隐藏 API，跨 Android 版本可能变；最脏，仅当 B1/B2 都失败 |

B2 的过滤逻辑（O(1) 判断，避免逐事件遍历视图树）：

```kotlin
private var imeDecor: WeakReference<View>? = null

// onStartInputView 之后
imeDecor = WeakReference(service.window?.window?.decorView)

// ViewGroup.dispatchTouchEvent hook 内
val v = instanceOrNull as? View ?: return@before
if (v.rootView !== imeDecor?.get()) return@before
```

**闸门 P2**

- [ ] 关掉日志后实际打字，按下 / 抬起各一次，无重复触发、无漏触发。
- [ ] 长按、滑动输入、多指（若输入法支持）不产生异常震动或崩溃。
- [ ] 连续打字 30 秒，无明显掉帧（B2/B3 必须测这一项）。

### P3 — FAB 与设置页 Activity

1. **找设置主 Activity**：
   ```bash
   adb shell dumpsys package com.iflytek.inputmethod | sed -n '/Activities:/,/Providers:/p'
   adb shell dumpsys package com.google.android.inputmethod.latin | sed -n '/Activities:/,/Providers:/p'
   ```
   或在 `reverse/out/<pkg>/AndroidManifest.xml` 里找带 `SETTINGS`/`LAUNCHER` 的 Activity。
2. 在 `HostCatalog.SETTINGS_ACTIVITY_FALLBACK` / `PROXY_ACTIVITY` 里加入候选（每宿主 2–3 个，按优先级）。
3. 校验 proxy 的 `launchMode`：必须是 `standard`；若只有 `singleTask`，换下一个候选（现有代码里 `resolveSettingsActivity` 会按 `exported` / `declared` 过滤，但**不校验 launchMode**，需要人工确认并在报告里记录）。
4. FAB 白名单沿用 `HostCatalog.isSettingsActivity(host, className)`，它对未知类名已有「包名开头 + 含 Setting」的兜底；新宿主若命名不含 Setting，需要在表里补精确类名。
5. 设置页标题的宿主名：`HostCatalog.labelRes()` 增加 `iflytek` / `gboard` 两条映射；定制版（前缀命中但无精确映射）显示家族名 + 包名后缀，避免出现「未知」。

**闸门 P3**

- [ ] 两个输入法设置页各出现且只出现一个 FAB；其它页面不出现。
- [ ] 点 FAB 能打开设置页，页面内 `packageName` 是输入法包名。
- [ ] 返回后回到输入法设置页，输入法不重启。
- [ ] 定制版无法验证时，在报告里写明「按前缀匹配 + 类结构复用假设，未验证」。

### P4 — 回归与报告

1. `assembleRelease` 通过；新旧四个宿主都不回归（微信 / 豆包必须回归）。
2. 填 `ADAPT_REPORT.md`（§10.2）。
3. 更新 `README.md` 的支持列表与「定制版需手动勾选作用域」说明。
4. 提交 `dev`，等待 leader / 用户判定后再考虑合并。

---

## 6. 代码改动清单

| 文件 | 改动 |
| --- | --- |
| `HostCatalog.kt` | 新增 `IFLYTEK` / `GBOARD` 常量；`isSupported` 改为前缀匹配；新增 `familyOf`；`TOUCH_TARGET` / `SETTINGS_ACTIVITY_FALLBACK` / `PROXY_ACTIVITY` 增加两条；`labelRes` 增加两条 + 家族兜底 |
| `HookEntry.kt` | `ALL.forEach { loadApp(it) }` → `encase { if (isSupported(packageName)) … }`；其余装配不变 |
| `res/values/strings.xml` + `values-en/strings.xml` | `host_name_iflytek` = 讯飞输入法 / iFlytek Keyboard；`host_name_gboard` = Gboard；`host_name_iflytek_oem`（定制版）如需要 |
| `AndroidManifest.xml` | `<queries>` 补 4 个已知包名 |
| `HookEntry` 注解 | `scope` 补 2 个包名（`staticScope` 是否改 false 见 §4.2，默认不改） |
| `.gitignore` | 加 `reverse/` |
| `README.md` | 支持列表、定制版说明、已知限制 |

**不要动**：`HapticEngine.kt`、`SettingsStore.kt`、`SettingsModel.kt`、`SettingsActivity.kt`、`HostFabInjector.kt` 的既有逻辑；本任务只做「加宿主」。

---

## 7. 无法适配时的处理

出现以下情况，**记录并跳过**，不要硬顶：

| 情况 | 判定依据 | 记录内容 |
| --- | --- | --- |
| 找不到能收到 DOWN/UP 的 View/方法 | P1 的所有候选都不满足 | 试过的类、为什么不行 |
| 类名完全混淆且运行时无法定位 | 类名 `a.b.c` 且无法通过 `onStartInputView` 定位根 View | 版本号 + 混淆程度 |
| 找到了但 Hook 后崩溃 / 输入法异常 | 装模块后输入法重启、闪退、按键无响应 | 崩溃栈、复现步骤 |
| 设置页找不到可用的 proxy（全部 singleTask / 未导出） | `resolveSettingsActivity` 返回 null 或启动失败 | 候选清单与 launchMode |
| 加固 / 反调试导致无法注入 | 模块加载即崩、类加载失败 | 现象 + 日志 |

**跳过 = 代码里不启用该宿主**（`isSupported` 不匹配它，或 `TOUCH_TARGET` 缺省），并在 `ADAPT_REPORT.md` 里列为「未适配」。

---

## 8. 与现有实现的偏离记录

允许的偏离（必须写进报告）：

- Gboard 使用 B2/B3 兜底方案；
- 定制版仅做前缀匹配、未验证；
- proxy 需要新增候选或降级到「包内 Setting 页面」兜底。

不允许的偏离：新增一套并行 Hook 体系、改动 `HapticEngine` 的震动语义、把 FAB 注入到所有 Activity。

---

## 9. 验收清单

**功能**

- [ ] 讯飞：设置页有且仅有一个 FAB；点开设置页；打字按下/抬起各一次震动
- [ ] Gboard：同上
- [ ] 四种模式（无 / 系统预设 / 简单自定义 / 自定义波形）在两个输入法上均生效
- [ ] 改配置 1 秒内生效，无需重启输入法
- [ ] 设置页内「重载输入法」可重启对应输入法（root 设备）

**回归**

- [ ] 微信输入法、豆包输入法功能未回归（FAB、震动、设置页）
- [ ] 模块构建通过，APK 体积无非预期增长（新增 Hook 不引入新依赖）

**工程**

- [ ] `reverse/` 未提交
- [ ] `ADAPT_REPORT.md` 完成
- [ ] `README.md` 支持列表更新

---

## 10. 报告模板

### 10.1 P0 拉取完成报告（发给用户，然后等确认）

```
APK 已拉取完成，可以断开 adb。

讯飞输入法 com.iflytek.inputmethod
  versionName / versionCode：…
  APK：reverse/iflytek/base.apk（… MB，sha256 …）
  split：无 / 列表

Gboard com.google.android.inputmethod.latin
  versionName / versionCode：…
  APK：reverse/gboard/base.apk + split_config.arm64_v8a.apk（… MB）
  IME 组件：com.google.android.inputmethod.latin/<service>

下一步：本地反编译定位触摸入口，不再需要设备。
```

### 10.2 `ADAPT_REPORT.md` 模板

```markdown
# 新输入法适配报告

## 结论
| 输入法 | 包名 | 版本 | 触摸适配 | FAB | 设置页 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 讯飞 | com.iflytek.inputmethod | x.y.z (code) | 类#方法 / 未适配 | ✅/❌ | ✅/❌ | 可用 / 部分 / 跳过 |
| Gboard | com.google.android.inputmethod.latin | x.y.z (code) | 类#方法 / 未适配 | ✅/❌ | ✅/❌ | 可用 / 部分 / 跳过 |

## 定位依据
（每个宿主：IME Service → 根 View → 触摸方法 的调用链证据，附 jadx 文件路径与行号）

## 定制版覆盖
（讯飞前缀匹配覆盖了哪些已知包名；哪些只能靠假设；未验证项列清楚）

## 无法适配项
（按 §7 的表格填写，含尝试过但失败的方案）

## 性能与稳定性
（B2/B3 方案的事件开销实测；连续打字 30 秒表现；崩溃/ANR 情况）

## 建议
（是否把 staticScope 改为 false；是否接受混淆方案的版本脆弱性；是否要为用户提供「手动勾选作用域」引导）
```

---

## 11. 禁止事项

1. 不要用通配 scope 或 `system` 作用域「顺便」覆盖所有输入法。
2. 不要 Hook `View.dispatchTouchEvent` 全量事件来替代定位（B2/B3 仅在定位失败时使用，且必须限定 `rootView`）。
3. 不要修改宿主 APK、不要改 LSPosed 数据库、不要 kill 系统进程。
4. 不要把 `reverse/` 下的 APK 提交进仓库。
5. 不要在未验证的情况下声称定制版「已支持」。
6. 不要为了「让 FAB 出现」而把注入扩大到宿主所有 Activity。
7. 不要把适配失败静默吞掉——必须在报告里出现。

---

## 附录 A：命令速查

```bash
# 包与组件
adb shell pm list packages | grep -Ei "iflytek|inputmethod"
adb shell ime list -a -s
adb shell dumpsys input_method | grep -E "mCurMethodId|mCurId|mCurClient"

# 版本与路径
adb shell dumpsys package <pkg> | grep -E "versionName|versionCode|primaryCpuAbi"
adb shell pm path <pkg>

# 反编译
apktool d -f -s <base.apk> -o out
jadx -d src <base.apk>

# 让 Hook 重新注入
adb shell am force-stop <pkg>

# 日志
adb logcat -s YukiHook WETYPE_HOOK WETYPE_UI WETYPE_FAB WETYPE_CFG
```

## 附录 B：调研来源

- GMS 应用与包名对照（Gboard / Gboard Go 同为 `com.google.android.inputmethod.latin`）：https://devpress.csdn.net/v1/article/detail/135879804
- APKMirror Gboard Go 页面（`Package: com.google.android.inputmethod.latin`，`-lite_release`）：https://www.apkmirror.com/apk/google-inc/gboard/gboard-6-9-4-181945924-release/gboard-the-google-keyboard-6-9-4-181945924-lite_release-arm64-v8a-android-apk-download/
- Android Police：Gboard Go 覆盖安装在标准版之上：https://www.androidpolice.com/2018/01/22/gboard-go-starts-rolling-android-8-1-devices-low-ram-apk-download/
- 常见输入法 IME 组件对照（讯飞 `/.FlyIME`、Gboard `/com.android.inputmethod.latin.LatinIME`）：https://blog.csdn.net/u010345983/article/details/162424955
- 讯飞定制版包名 `com.iflytek.inputmethod.oem`：https://www.fxxz.com/azsoft/595652.html
- 讯飞小米定制版包名 `com.iflytek.inputmethod.miui`：https://www.32r.com/app/61533.html

> 以上均为第三方资料，**必须**用真机 `pm list packages` / `ime list -a` 复核后才可写进报告。

## 附录 C：现有宿主参数对照（新增宿主参考）

| 项 | 微信输入法 | 豆包输入法 |
| --- | --- | --- |
| 包名 | `com.tencent.wetype` | `com.bytedance.android.doubaoime` |
| 触摸类#方法 | `...plugin.hld.view.ImeRootView#dispatchTouchEvent` | `...input.keyboard.KeyboardView#onTouchEvent` |
| 设置页候选 | `...reactnative.activity.ImeMainSettingActivity` | `...activity.SettingsActivityNext` 等 |
| proxy 候选 | 同上 | `...activity.LauncherActivity` 优先 |

新宿主请照此表补齐「包名 / 触摸类#方法 / 设置页候选 / proxy 候选」四列后再动代码。
