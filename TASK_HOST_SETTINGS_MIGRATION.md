# 开发跟进任务书：设置页迁入宿主进程 + 配置回归宿主目录

> **文档性质**：可直接执行的任务书（Task Spec），面向对本项目 0 了解的执行代理。
> **生成时间**：2026-10-29　·　**适用基线**：`dev` 分支 `1ada506`（含 `DEVELOPMENT_GUIDE.md`）、版本 `1.3.0 (1300)`
> **本任务会推翻 `DEVELOPMENT_GUIDE.md` 的以下结论**（完成后必须回写该文档）：独立 Launcher 设置 App、LibXposed Remote Preferences/Files 作为配置通道、`XposedService` 服务连接、`HostGrant` 一次性凭证、日志 Messenger 上报。
> **不要凭记忆改代码**：本任务书里所有类名、方法名、签名均已对照仓库源码与 `api-102.0.0.aar` / `miuix-ui-0.9.3.aar` 校验；但**宿主侧类名、`launchMode`、主题必须真机 `dumpsys` 确认**（见 §6 P0）。

---

## 0. 给执行者的使用说明

### 0.1 先读这三份（按顺序，不要跳）

| 顺序 | 文件 | 读什么 |
| --- | --- | --- |
| 1 | `DEVELOPMENT_GUIDE.md` | §2 目录与依赖、§4 钩子实现要点、§7 构建与调试、§8 红线 |
| 2 | 本文件 | 全部；尤其 §4 技术方案、§5 阶段计划、§10 禁止事项 |
| 3 | `HOST_INJECTED_SETTINGS_ACTIVITY_GUIDE.md` | **只读 §2 与 §5 的边界讨论**，注意它的结论是"不要真的把 Activity 注册进宿主"，本任务明确推翻该结论，但其中关于 UID 校验、注入卡措辞的部分仍可参考 |

`NONROOT_FRAMEWORK_SUPPORT_GUIDE.md` 已作废，不要读，不要据此恢复任何免 Root 代码。

### 0.2 完成定义（DoD）

同时满足才算完成：

1. 安装模块后，桌面/启动器**没有**模块图标，`AndroidManifest.xml` 里没有任何带 `MAIN`/`LAUNCHER` 的 Activity。
2. 在微信输入法设置页点 FAB，弹出一个**运行在微信输入法进程内**的设置页；在豆包输入法设置页点 FAB，弹出运行在豆包进程内的设置页。
3. 设置页顶栏：第一行是模块标题，第二行是宿主名（副标题）；点击标题或副标题弹出宿主选择，可切换到另一个宿主（见 §4.8 的能力边界）。
4. 所有配置写入 `<宿主数据目录>/haostoo/config/`，改完即时生效（无需重启输入法），Hook 通过文件变化热更新。
5. 页面里不再出现框架连接/Remote/同步等待类文案；不再有"复制到其他宿主"。
6. 全项目源码里**没有中文字面量**（除注释、`Texts.kt` 的映射表本身、日志 tag 与 README）。UI 文案全部来自 `Texts.kt`。
7. `assembleRelease` 通过；两个宿主真机手测通过 §8 验收清单。
8. 提交到 `dev` 分支；**不得**合并到 `master`（需用户批准）。

### 0.3 工作方式

- 每完成一个阶段（§5 的 P0…P5）就 `git commit` 一次，commit message 用本文件给出的建议值。
- 每个阶段的"验证闸门"不通过，**停下来写报告**，不要带病进入下一阶段。
- 遇到本文件未覆盖的宿主差异，先记录到仓库根目录的 `HOST_NOTES.md`（新建），再继续。

---

## 1. 背景与目标

### 1.1 现状（1.3.0）

模块是一个独立 App：用户从桌面图标打开 `MainActivity`，进入「概览 / 宿主 / 设置」三页壳层，选宿主后进入 `HostSettingsScreen`。配置通过 LibXposed **Remote Preferences / Remote Files** 读写，需要 `XposedService` 连接与 `PROP_CAP_REMOTE` 能力；宿主内只能看到一个 FAB，点击后用 `PendingIntent` 凭证跳到模块自己的 `HostSettingsActivity`。

问题：用户必须先装一个"App"，且配置依赖框架 Remote 能力，离线/不支持 Remote 的框架下设置不可写；同时模块进程与宿主进程两套存储带来 outbox/冲突/心跳等一整层复杂度。

### 1.2 目标（本任务）

把**设置页搬进宿主进程**，配置**回归宿主数据目录的普通文件**，删除独立 App 形态。

| 编号 | 用户需求原文 | 实现要点 |
| --- | --- | --- |
| R1 | 移除设置应用，即模块无 MainActivity | 删除 `MainActivity` 与 `LAUNCHER` intent-filter；模块只保留模块身份（`META-INF/xposed/*`） |
| R2 | 设置页改为向宿主注册新 activity，在此 activity 中实现模块设置 | 在宿主进程内"注册"一个未声明的 Activity：客户端 Activity 注入（stub 替换 + `Instrumentation` hook），见 §4 |
| R3 | 配置路径修改，仍然改到宿主数据目录下 | 配置/日志/波形全部落到宿主自己的 `files` 目录，同进程直接 IO，删除 Remote 通道 |
| R4 | 设置 activity 复用当前代码；配置页按宿主包名动态决定；宿主名作副标题显示在标题下方；标题可点击切换宿主，不实现跨宿主读写 | 复用 `HostSettingsScreen` 的内容组件；`packageName` 决定目标宿主；`TopAppBar(subtitle=…)` 或自定义顶栏；切换仅重定向目标，不迁移数据 |
| R5 | 所有文字内容列表映射，便于统一修改与多语言 | 新建 `Texts.kt` 单点映射（枚举 + 中英双语），删除全部硬编码中文字面量；**不用** Android 资源（理由见 §4.9） |

### 1.3 为什么不用 Android 资源做多语言（R5 的技术依据）

设置页运行在**宿主进程**，`getResources()` 默认返回宿主资源，模块 APK 的 `strings.xml` / 主题不可直接使用（要接管资源需改 `attachBaseContext`，会连带主题、`setTheme` 的资源 id 校验一起出问题，风险远大于收益）。因此文案走 **Kotlin 常量表**：零资源依赖、可编译期检查、可加语言。这也正好是需求里"列表映射"的形态。

例外：赞助二维码图片必须读模块资源，用 `packageManager.getResourcesForApplication(getModuleApplicationInfo())` 取（公开 API，见 §4.6）。

---

## 2. 现状盘点与文件处置

### 2.1 处置总表

符号说明：**保留**=基本不动；**改造**=保留骨架改内部；**重写**=职责变化需新写；**删除**=移除文件。

| 文件 | 处置 | 说明 |
| --- | --- | --- |
| `MainModule.kt` | 改造 | 保留触摸 Hook 与配置缓存装配；新增 Activity 注入 Hook；删除 Remote 读取、心跳文件、FAB 的 grant 逻辑 |
| `HapticEngine.kt` | 改造 | 保留 `triggerHapticPreview` / `cachedSettings` / `cachedCustomHaptic`；`killHost` 保留；**继续禁止引用任何 `io.github.libxposed.*`** |
| `HapticUtils.kt` | 保留 | 无改动 |
| `RemoteConfig.kt` | 重写 | 只保留 `SettingsState` / `WaveformHaptic` / `CustomHapticConfig` / 默认值与波形校验/序列化；删除全部 prefs 键、group/file 名、revision 键、`HookStatus` |
| `ConfigRepository.kt` | 重写 | 改名职责为"宿主目录文件仓库"：读/写/监听文件，产出 `SettingsSnapshot`；删除 `SyncState`/outbox/conflict |
| `ConfigStore.kt` | 重写 | 变成 `HostPaths` + 原子写工具（临时文件 → fsync → rename） |
| `RemoteService.kt` | 删除 | 不再连接 `XposedService` |
| `SettingsActivity.kt` | **新增** | 运行在宿主进程的设置页（`ComponentActivity`，Compose 入口） |
| `HostActivityInjector.kt` | **新增** | 负责 stub 选择、marker、`Instrumentation.newActivity` hook、启动 |
| `HostCatalog.kt` | **新增** | 宿主包名/显示名/stub 表/版本查询（吸收 `RemoteConfig` 的宿主常量与 `HostInfo`） |
| `HookRuntime.kt` | **新增** | 进程内运行状态（Hook 是否装好、模块版本、最近配置 revision、心跳时间），替代 `hook_status_*.json` |
| `Texts.kt` | **新增** | 全量文案映射（中/英）与列表映射 |
| `SettingsScreen.kt` | 改造 | `HostSettingsScreen` → `SettingsContent`；数据源换新仓库；顶栏换新；删"复制到其他宿主"；注入卡读 `HookRuntime` |
| `WaveformEditor.kt` | 保留 | 除文案替换外不改逻辑 |
| `WaveformCurve.kt` | 保留 | 除 `contentDescription` 文案外不改 |
| `HostFabInjector.kt` | 改造 | 保留拖动/吸附/位置记忆；点击改为 `HostActivityInjector.launch(...)`；Toast 文案进 `Texts`；FAB 位置不再写 Remote，改写本地文件（见 §4.5） |
| `HostInfo.kt` | 合并进 `HostCatalog` | 版本查询逻辑保留 |
| `LogStore.kt` | 改造 | 路径改宿主目录；保留查看/清理/打包 |
| `DebugLog.kt` | 重写 | 删除 Messenger/Service 绑定，直接在当前进程追加写文件（同进程，无需 IPC） |
| `LogService.kt` | 删除 | 同进程不再需要跨进程日志服务 |
| `DebugScreen.kt` | 改造 | `DebugDialog` 接入新页面的「调试与日志」二级页 |
| `LegacyConfigImporter.kt` | 改造并接入 | 作为首次迁移的兜底来源（§4.4.3） |
| `ConfigTransfer.kt` | 改造 | 由"全部宿主"改为"当前宿主"导入导出 |
| `ModulePrefs.kt` | 删除 | 颜色模式并入宿主配置文件（per-host） |
| `MainActivity.kt` | 删除 | R1 |
| `App.kt` | 删除 | 无 NavHost；页面切换在 `SettingsActivity` 内用状态机完成 |
| `MainScreen.kt` | 删除（内容迁移） | 「概览/宿主/设置」三页壳层不再需要；其中模块级设置内容迁移为 `ModuleSettingsPage`（新文件或并入 `SettingsScreen.kt`） |
| `HostSettingsActivity.kt` | 删除 | 被宿主内 Activity 取代 |
| `HostGrant.kt` | 删除 | 同进程启动，无跨进程凭证需求 |
| `LiquidGlassBar.kt` / `liquid/` / `miuixanim/` | 删除 | 底栏取消，无引用；同时可移除 `miuix-blur` 依赖 |
| `ui/theme/*` | 删除 | Material3 模板残留 |
| `AndroidManifest.xml` | 改造 | 删除两个 Activity 与 `LogService`、`android:name` Application；保留 `VIBRATE` 与 `<queries>` |
| `WeTypeHookRApp.kt` | 删除 | 只为 `RemoteService.init` 存在 |
| `app/build.gradle.kts` | 改造 | 移除 `io.github.libxposed:service` / `interface`、`miuix-blur`、`navigation-compose`、`navigationevent-compose`、`coil`（若最终无引用） |

> 删除前用 `grep -rn` 确认零引用；每阶段构建必须通过。

### 2.2 关键现存行为（改造时必须保住）

- Hooker 只读内存缓存、永远 `chain.proceed()`、不在输入路径做 IO。
- `HapticEngine` 与 `RemoteConfig` 不得引用 Xposed 类型（`compileOnly` 导致 App/Hook 分层红线依然有效；虽然现在都在宿主进程，但 `MainModule` 仍只能在框架注入时存在）。
- 波形 `timings[0] == 0`、段数 2–8、强度 0–255、至少一段 > 0ms 的校验。
- 设置自动保存（滑杆防抖 250ms、离开页面补写）。
- 弹层一律 miuix `Overlay*`，且必须位于某个 `Scaffold` 子树内。
- FAB 只在两个白名单设置页注入，可拖动、贴边、位置按宿主记忆。

---

## 3. 目标架构

```
┌──────────────── 宿主进程（com.tencent.wetype / com.bytedance.android.doubaoime）────────────────┐
│                                                                                                │
│  MainModule : XposedModule                                                                     │
│    ├─ onPackageReady                                                                           │
│    │    ├─ installTouchHooks()            # 原有                                    │
│    │    ├─ ConfigRepository.attach(host)  # 读宿主目录文件 → HapticEngine 缓存 + FileObserver   │
│    │    ├─ HookRuntime.markLoaded()       # 进程内状态                                          │
│    │    └─ HostFabInjector.install(host)  # Activity.onResume hook，注入 FAB                    │
│    │                                                                                           │
│    ├─ SettingsActivity（真实类在模块 dex，被 newActivity 替换注入，运行于本进程）                │
│    │    └─ Compose: SettingsContent(hostPackage)                                               │
│    │         ├─ SettingsTopBar（标题 + 宿主副标题，可点 → 宿主选择）                            │
│    │         ├─ 注入状态卡（读 HookRuntime）                                                    │
│    │         ├─ 按下/抬起震动卡（复用现有组件）                                                  │
│    │         ├─ 其他选项（恢复默认 / 重载输入法）                                                │
│    │         └─ 二级页：波形编辑器 / 模块设置 / 调试与日志 / 帮助 / 关于                         │
│    │                                                                                           │
│    └─ HostActivityInjector                                                                     │
│         ├─ Instrumentation.newActivity hook：识别 marker → 换成模块 ClassLoader + 目标类        │
│         └─ launch(hostActivity)：startActivity(stub + marker + NEW_TASK|MULTIPLE_TASK)          │
│                                                                                                │
│  数据：<host files>/haostoo/{config/settings.json, config/haptic.json, logs/*.log, ui.json}     │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

进程内只有一套状态：UI 与 Hook 共享 `HapticEngine.cachedSettings` 和同一批文件，**没有 IPC、没有 Remote、没有 outbox、没有心跳文件**。

---

## 4. 关键技术方案

### 4.1 "在宿主里注册 Activity"的三种做法与选型

Android 不允许运行时把组件真正登记进别的 APK 的 `PackageManager`（system_server 解析必然失败）。可行路径只有三条：

| 方案 | 原理 | 结论 |
| --- | --- | --- |
| A. 包名校验 + 模块自己的 exported Activity | 现状（1.3.0）：Activity 属于模块包，跑在模块进程 | 不满足 R2/R3（跨进程，写不到宿主目录） |
| **B. stub 替换 + `Instrumentation.newActivity` 换类** | 客户端用宿主已声明的 Activity 骗过 system_server，在真正实例化前把"类加载器 + 类名"换成本模块的 Activity | **选定**。不需要 dex 注入、不需要 hidden API、不需要改 system_server |
| C. 反射 `BaseDexClassLoader.pathList.addDexPath` 注入 dex 后伪造 `ActivityInfo` | 需改 `ActivityInfo` 且 system_server 仍不认；`pathList` 属 hidden API | 放弃：hidden API 限制 + 版本脆弱 |

方案 B 的关键事实（决定了它为什么安全）：

1. `ActivityThread.performLaunchActivity` 通过 `mInstrumentation.newActivity(cl, className, intent)` 实例化，`cl` 是宿主 `LoadedApk` 的 ClassLoader。
2. `Instrumentation.newActivity` 的签名 `(ClassLoader, String, Intent)` **从 API 1 至今未变**，且是 public —— 可以稳定 hook。
3. `XposedInterface.Chain` 支持 `proceed(Object[])` 替换参数，因此可以把 `cl` 换成**模块 ClassLoader**、把 `className` 换成模块 Activity 类名。
4. 实例化完成后，`ActivityThread` 用**宿主的** `ActivityClientRecord`、`Application`、`ActivityInfo` 去 `attach`，所以该 Activity 的 `Context`、`packageName`、`filesDir`、`getSystemService` 全是**宿主的** —— 正好满足 R2/R3。
5. 因为不动 `activityInfo`，宿主资源与主题仍然有效，不会出现资源 id 跨包崩溃。

### 4.2 注入实现（`HostActivityInjector.kt`）

```kotlin
object HostActivityInjector {
    const val EXTRA_MARK = "com.haostoo.wetypehookr.extra.HOST_SETTINGS_MARK"

    /** P0 期填空，真机 dumpsys 后写入（每宿主可多个候选，按顺序尝试）。 */
    private val STUBS = mapOf(
        HostCatalog.WETYPE to listOf(/* "com.tencent.wetype.....XxxActivity" */),
        HostCatalog.DOUBAO to listOf(/* "com.bytedance.android.doubaoime.....XxxActivity" */),
    )

    private var moduleLoader: ClassLoader? = null

    /** 在 onPackageReady 中调用一次。 */
    fun install(host: String, loader: ClassLoader, xposed: XposedInterface) {
        moduleLoader = SettingsActivity::class.java.classLoader
        runCatching {
            val m = Instrumentation::class.java.getDeclaredMethod(
                "newActivity", ClassLoader::class.java, String::class.java, Intent::class.java,
            )
            xposed.hook(m).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val intent = chain.args.getOrNull(2) as? Intent
                    if (intent == null || !intent.getBooleanExtra(EXTRA_MARK, false)) {
                        return chain.proceed()
                    }
                    val cl = moduleLoader ?: return chain.proceed()
                    return chain.proceed(
                        arrayOf(cl, SettingsActivity::class.java.name, intent),
                    )
                }
            })
        }.onFailure { Log.e(TAG, "newActivity hook failed", it) }
    }

    /** 由 FAB 点击调用。 */
    fun launch(from: Activity, host: String): Boolean {
        val stub = pickStub(from, host) ?: return false
        val i = Intent().setClassName(host, stub)
            .putExtra(EXTRA_MARK, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        return runCatching { from.startActivity(i); true }.getOrDefault(false)
    }
}
```

**四个必须遵守的细节**

1. **marker 判断要极快**：`newActivity` 每次创建 Activity 都会进 hook，先判 extra 再判类名，不要做字符串拼接/反射。
2. **只替换自己的类**：不要顺手改别的 intent，宿主自己的 Activity 必须原样放行。
3. **`FLAG_ACTIVITY_MULTIPLE_TASK` 不能省**：stub 若被宿主声明为 `singleTask`/`singleTop`，不加此 flag 会走 `onNewIntent`，`newActivity` 根本不会执行，表现为"点了没反应"。
4. **失败要可见**：`launch` 返回 false 或 500ms 内 `SettingsActivity.onCreate` 未置位时，Toast 提示（`Texts.ToastInjectFailed`），不要静默。
5. **宿主可能自定义 `Instrumentation`**：先取 `ActivityThread.currentActivityThread().mInstrumentation.javaClass`，若不是 `android.app.Instrumentation` 本身，则对该实现类的 `newActivity` 再 hook 一次（子类覆写会绕过基类 hook）。两边都装，重复替换无副作用（marker 判断天然幂等）。
6. **不要用 `intent.component` 判断宿主**：替换后 intent 里仍是 stub 组件名；宿主身份一律用 `context.packageName`。

**stub 选择标准（P0 必须真机确认，写入 `HostCatalog`）**

- 属于宿主自己的包（`packageName` 相同），`enabled=true`；
- `launchMode` 为 `standard`（**不要** `singleTask` / `singleInstance` / `singleTop`）；
- 主题无 ActionBar、非透明、非 `Dialog` 主题；
- 启动本身无副作用（不要选会发网络请求、会清理数据、会弹登录的页面）。

查询命令：

```bash
adb shell dumpsys package com.tencent.wetype | sed -n '/Activities:/,/Providers:/p'
adb shell dumpsys package com.bytedance.android.doubaoime | sed -n '/Activities:/,/Providers:/p'
```

`dumpsys` 里每个 `<activity>` 段会标 `launchMode`。把候选记入 `HostCatalog.STUBS`，并在 `pickStub` 里做运行时校验（`pm.getActivityInfo(ComponentName(host, name), 0)`，检查 `enabled` 与 `launchMode`，不可用就试下一个）。

### 4.3 窗口、主题与返回

- Activity 的 `packageName` = 宿主；`getWindow()` 的主题来自 stub 的 `activityInfo.theme`，通常是宿主设置页的 NoActionBar 主题，可直接用。
- 在 `onCreate` 里（`setContent` 之前）做一次保险：`runCatching { requestWindowFeature(Window.FEATURE_NO_TITLE) }`，并 `window.setBackgroundDrawable(ColorDrawable(surfaceColor))`，避免宿主主题底色透出。
- `enableEdgeToEdge()` 可用（`androidx.activity` 在模块 dex 中）；底栏留白沿用 `navigationBars` 内边距。
- 关闭：`finish()`（顶栏返回按钮 + 系统返回键），回到宿主原设置页。**不要**用 `overridePendingTransition` 自定义动画，保持与宿主一致。
- 任务栈：因 `NEW_TASK|MULTIPLE_TASK`，会多一条 task 记录，`Recent` 中显示为输入法本身——可接受，写入文档即可。

### 4.4 配置存储（R3）

#### 4.4.1 路径

```kotlin
object HostPaths {
    private const val ROOT = "haostoo"
    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, ROOT).apply { mkdirs() }
    fun configDir(ctx: Context) = File(root(ctx), "config").apply { mkdirs() }
    fun settingsFile(ctx: Context) = File(configDir(ctx), "settings.json")
    fun hapticFile(ctx: Context) = File(configDir(ctx), "haptic.json")
    fun uiFile(ctx: Context) = File(configDir(ctx), "ui.json")     // FAB 位置、颜色模式
    fun logsDir(ctx: Context) = File(root(ctx), "logs").apply { mkdirs() }
}
```

- 默认落在 `getExternalFilesDir(null)`（即 `/sdcard/Android/data/<host>/files/haostoo/…`），与 1.2.x 行为一致，便于用户备份/排障；为 null 时回退 `filesDir`。
- 全部路径只允许从 `HostPaths` 取，禁止在别处拼字符串。

#### 4.4.2 文件格式与写入

`settings.json`：

```json
{"schema": 2, "revision": 1730000000000, "updated_at": 1730000000000,
 "down_mode": 1, "up_mode": 1, "down_system": 0, "up_system": 3,
 "down_duration": 50.0, "down_strength": 0.5,
 "up_duration": 50.0, "up_strength": 0.5,
 "theme_mode": 2, "debug_log": false}
```

`haptic.json`：沿用现有 `{"down":{"timings":[…],"amplitudes":[…]}, "up":{…}}` 结构。

写入规则（沿用 `ConfigStore.writeAtomic`）：

1. 写 `xxx.json.tmp`；
2. `FileOutputStream(tmp, true).use { it.fd.sync() }`；
3. 删旧文件 → `renameTo`（失败则 `copyTo` 后删临时文件）；
4. 先写 `haptic.json` 再写 `settings.json`（settings 里的 `revision` 是这次逻辑提交的标记）。

`revision` 用 `System.currentTimeMillis()`，`max(settings.revision, haptic mtime)` 作为整体版本，避免"设置变了但 revision 没变"。

#### 4.4.3 旧配置迁移（一次）

首次启动（`settings.json` 不存在）时按顺序尝试：

1. `LegacyConfigImporter` 读 `<host>/files/haostoo/config/config.xml`（1.2.x 位置，外部目录同一路径）；
2. 仍无 → 尝试从 LibXposed Remote Preferences 读一次（`getRemotePreferences("wetype_settings"/"doubao_settings")`，能力检测 `PROP_CAP_REMOTE`，包 `runCatching`）——为 1.3.0 用户保留配置；
3. 仍无 → `RemoteConfig.defaultSettings()`。

迁移成功后写新文件，**此后不再读 Remote**；Remote 相关代码在 P5 阶段整体删除（P1 阶段先保留读取能力）。

#### 4.4.4 热更新（Hook 侧）

`ConfigRepository` 在 Hook 进程侧挂 `FileObserver(configDir, CLOSE_WRITE or MOVED_TO)`：

- 收到事件后 200ms 去抖，对比 `revision`，变化则重读 `settings.json` + `haptic.json`，原子替换 `HapticEngine.cachedSettings` / `cachedCustomHaptic`；
- 解析失败保留上一个有效缓存并 `Log.w`；
- `FileObserver` 引用必须用字段强引用持有，避免被 GC。

UI 侧保存后不需要通知 Hook —— 同进程 + 文件监听即可，**不要在 UI 里直接改 `HapticEngine.cachedSettings`**（会让"文件才是权威源"这条契约失效；测试时可接受 200ms 延迟）。

### 4.5 FAB 与 UI 偏好

- FAB 位置（`fab_x`/`fab_y`）与颜色模式不再写 Remote Preferences，改写 `ui.json`（`HostPaths.uiFile`），字段：`{"fab_x":0.0,"fab_y":0.62,"color_mode":2}`。
- `ModulePrefs` 删除；`color_mode` 变成 per-host（跟 `ui.json` 走）。这是行为变化，需在 README 更新日志里说明。
- FAB 仍在白名单设置页注入，逻辑不变；只把点击改成 `HostActivityInjector.launch(activity, host)`，失败时 Toast。

### 4.6 资源边界（唯一需要模块资源的地方）

赞助二维码：`R.drawable.sponsor` 是模块资源。在宿主进程取法：

```kotlin
val appInfo = moduleAppInfo            // MainModule 里 XposedInterface.getModuleApplicationInfo() 缓存到静态字段
val res = context.packageManager.getResourcesForApplication(appInfo)
val bmp = BitmapFactory.decodeStream(res.openRawResource(R.drawable.sponsor))
Image(bitmap = bmp.asImageBitmap(), contentDescription = null)
```

- 不要在宿主进程调用 `createPackageContext(MODULE_PKG, …)`：宿主没有对模块包的 `<queries>`，Android 11+ 会抛 `NameNotFoundException`。
- 除这张图，页面**不得**再引用任何 `R.string/R.style/R.dimen`。
- `HostFabInjector` 的 FAB 是 Canvas 纯绘制，无资源依赖，保持现状。

### 4.7 顶栏：标题 + 宿主副标题（可点击）— R4 的落地点

**先确认事实**（已对照 `miuix-ui-0.9.3` 与官方属性表）：

- `TopAppBar(title: String, subtitle: String = "", largeTitle: String = title, …)` **支持副标题**，官方描述为"显示在标题栏下方的副标题文本"。
- 但 `title` 的类型是 `String`，**无法挂点击事件**；`navigationIcon` / `actions` 才是 slot。

因此按下面二选一实现，**默认 T1**：

| 方案 | 做法 | 取舍 |
| --- | --- | --- |
| **T1（选定）** | 自写 `SettingsTopBar`：`Surface` + `Row(返回按钮 + 可点击 Column(主标题 / 副标题 + 指示箭头))`，标题区整体 `clickable` | 完全满足"点标题/副标题切换宿主"；放弃 miuix 的折叠大标题行为（设置页不需要） |
| T2（保底） | 直接用 `TopAppBar(title = 模块标题, subtitle = 宿主名)`，切换入口放 `actions` 的 `IconButton` | 视觉最"原生"，但不符合"点标题"的字面需求 |

T1 骨架（放在 `SettingsActivity.kt` 或独立 `SettingsTopBar.kt`）：

```kotlin
@Composable
fun SettingsTopBar(
    hostName: String,
    onBack: (() -> Unit)?,
    onSwitchHost: () -> Unit,
    scrollBehavior: ScrollBehavior? = null,
) {
    Surface(color = MiuixTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, Str.Back.s()) }
            }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = Str.SwitchHost.s()) { onSwitchHost() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(Str.AppTitle.s(), style = MiuixTheme.textStyles.headline2,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hostName, style = MiuixTheme.textStyles.footnote1,
                         color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(MiuixIcons.ArrowUpDown, Str.SwitchHost.s(),
                         modifier = Modifier.padding(start = 2.dp).size(12.dp),
                         tint = MiuixTheme.colorScheme.onSurfaceContainerVariant)
                }
            }
        }
    }
}
```

注意：

- `MiuixIcons.ArrowUpDown` 若在 0.9.3 不存在（先用 IDE 补全确认，不要猜），改用 `MiuixIcons.Tune`。
- 返回按钮只在二级页（波形编辑 / 模块设置）显示；一级页 `onBack = null`，此时标题区补 `padding(start = 16.dp)`。
- 文案不许写字面量，用 §9 的 `Str`。
- 内容区顶部要留出顶栏高度（`Scaffold(topBar = { SettingsTopBar(...) })` 并消费 `padding.calculateTopPadding()`），否则首张卡片被压住。

### 4.8 宿主识别与手动切换（R4）

**自动识别**：`SettingsActivity` 运行在宿主进程，直接用 `packageName`：

```kotlin
val autoHost = context.packageName            // 一定是宿主
val target = if (autoHost in HostCatalog.ALL) autoHost else HostCatalog.WETYPE
```

**手动切换**（备选方案，不实现跨宿主读写）：

- 顶栏标题/副标题整体可点击 → `OverlayDialog` 列出两个宿主，当前项打勾；
- 选中另一个宿主后：把页面 `targetHost` 改成它，尝试读取**该宿主**的配置目录：
  - 可读（同 UID / 共享 UID 场景）→ 正常展示与保存；
  - 不可读（`File.canRead()==false` 或目录不存在，不同 UID 的常见情况）→ 页面进入只读态，显示一条说明（`Texts.HostSwitchNoAccess`），保存按钮禁用；
- **不复制、不迁移**任何数据；原「复制到其他宿主」功能删除。

这条实现既满足"点击标题换宿主"的交互，也不假装能做跨 UID 读写。

### 4.9 文案映射（R5）

新建 `Texts.kt`，形态为枚举 + 中英双语，供 `Str.xxx` / `StrList.xxx` 直接取用：

```kotlin
enum class Str(val zh: String, val en: String) {
    AppTitle("震动设置", "Vibration"),
    DownVibration("按下震动", "Press vibration"),
    ...
    ;
    fun s(vararg args: Any?): String = String.format(pick(), *args)
    private fun pick() = if (Locale.getDefault().language.startsWith("zh")) zh else en
}

enum class StrList(val zh: List<String>, val en: List<String>) {
    Modes(listOf("无震动", "系统预设风格", "简单自定义", "自定义波形"),
          listOf("Off", "System preset", "Simple custom", "Custom waveform")),
    SystemHaptics(listOf("Virtual Key", ...), listOf("Virtual Key", ...)),
    ColorModes(listOf("浅色", "深色", "跟随系统"), listOf("Light", "Dark", "Follow system")),
    ;
    fun items(): List<String> = if (Locale.getDefault().language.startsWith("zh")) zh else en
}
```

- 参数化文案统一用 `%s` / `%d`（**不要**用 Kotlin 字符串模板传参，否则无法复用/翻译）。
- 列表（模式名、系统风格名、颜色模式名）走 `StrList`，页面里 `StrList.Modes.items()`。
- 严禁在 Compose 里出现中文字面量；`grep -nP '"[^"]*[\x{4e00}-\x{9fff}]' app/src/main/java` 结果只允许命中 `Texts.kt`。
- 系统的 `SYSTEM_HAPTICS` 20 个名字是 Android 常量名，中英相同，仍入表以便统一调整。
- 全量 key 清单见 §9。

---

## 5. 实施阶段与验证闸门

> 每个阶段的"闸门"不通过就停下报告。**P0 是全任务最大风险点，先做它。**

### P0 — 可行性验证（不改任何现有功能）

**目标**：用最小代价证明"能在宿主进程里跑起一个 Activity 并写宿主目录"。

1. 新增 `SettingsActivity`，内容只有一个 `TextView`：`"host=" + packageName + " pid=" + Process.myPid() + " filesDir=" + filesDir`。
2. 新增 `HostActivityInjector`（§4.2 骨架）+ `HostCatalog.STUBS`。
3. `HostFabInjector` 的点击改为 `HostActivityInjector.launch(...)`。
4. `MainModule.onPackageReady` 调用 `HostActivityInjector.install(...)`。
5. **暂不删除** `MainActivity`/`MainScreen`，保证随时能回退。

**闸门 P0（两个宿主都要过）**

- [ ] 点 FAB 后出现新页面，文本里的 `host=` 是对应输入法包名，`pid` 与 `adb shell pidof <host>` 一致；
- [ ] `adb shell run-as <host> ls files` 能看到 Application 创建的证据（或直接在页面里写一个探针文件并用 `run-as` 确认存在）；
- [ ] 返回键 / 顶栏返回能回到输入法设置页，输入法不崩；
- [ ] 宿主其余功能正常（设置页其它按钮、输入法打字）。

**若闸门失败**：改走 §7 的回退方案 F1，并写报告说明失败点（是 `newActivity` 没被调用，还是类加载失败，还是资源/主题崩溃）。

**建议 commit**：`feat(host-ui): 宿主进程内设置 Activity 可行性验证（P0）`

### P1 — 存储层迁到宿主目录

1. 新增 `HostPaths`、重写 `ConfigStore`（原子写工具）、新增 `SettingsRepository`（读/写/监听/快照）。
2. `RemoteConfig` 精简为数据模型 + 波形编解码；`RemoteConfig.displayName` 迁到 `HostCatalog`。
3. `MainModule` 改为使用 `SettingsRepository.attach(host)`（读文件 + `FileObserver` + 迁移逻辑 §4.4.3）。
4. `FAB 位置` 从 Remote 改到 `ui.json`。
5. 此阶段 UI 仍走旧 `ConfigRepository`（保留双写风险窗口，仅用于过渡），但**新页面 P0 的 TextView 能显示读到的配置**。

**闸门 P1**

- [ ] 手动改 `settings.json`（`adb shell`），输入法震动行为在 1s 内变化（FileObserver 生效）；
- [ ] 波形改 `haptic.json` 后同样生效；
- [ ] 首次进入时能从 1.3.0 Remote 配置或旧 `config.xml` 迁移出正确数值（构造一份旧文件测）；
- [ ] 文件写坏（写入非法 JSON）时 Hook 不崩、保留上一份缓存。

**建议 commit**：`feat(config): 配置改存宿主目录并支持文件热更新（P1）`

### P2 — 设置页正式迁移

1. `SettingsScreen.kt`：`HostSettingsScreen(hostPackage, onBack)` → `SettingsContent(host, onBack, onOpenModuleSettings)`；数据源换 `SettingsRepository`；删除 `SyncState/syncNotice`；注入卡改读 `HookRuntime`；删「复制到其他宿主」。
2. 顶栏：`SettingsTopBar`（标题 + 宿主副标题 + 可点击）；实现宿主选择弹层（§4.8）。
3. `SettingsActivity`：`setContent { MiuixTheme(...) { Scaffold { SettingsContent(...) } } }`，处理 `enableEdgeToEdge`、返回。
4. 自动保存沿用 250ms 防抖 + `onStop` 补写。

**闸门 P2**

- [ ] 两个宿主的设置页都能改模式/时长/强度/波形并即时生效；
- [ ] 顶栏第二行是宿主名；点击弹出宿主选择，切到另一个宿主时按 §4.8 行为处理（可读→可编辑；不可读→只读+说明）；
- [ ] 返回后输入法正常；再次进入是同一份配置（无重复保存、无状态错乱）；
- [ ] 界面无残留"框架/Remote/同步"字样。

**建议 commit**：`feat(host-ui): 设置页迁入宿主进程并复用现有组件（P2）`

### P3 — 文案映射

1. 新建 `Texts.kt`，按 §9 清单落全部 key。
2. 逐文件替换字面量：`SettingsScreen`、`WaveformEditor`、`WaveformCurve`、`HostFabInjector`、`HapticEngine`（Toast）、`ConfigTransfer`、`DebugScreen`、`HostCatalog`、错误/校验提示。
3. 校验：`grep` 中文只命中 `Texts.kt`。

**闸门 P3**

- [ ] 编译通过，`grep -nP '"[^"]*[\x{4e00}-\x{9fff}]' app/src/main/java` 仅 `Texts.kt`；
- [ ] 把系统语言切到 English，页面文案全部英文且不溢出（重点看 `ArrowPreference.summary`、波形提示、对话框按钮）。

**建议 commit**：`refactor(i18n): 文案集中到 Texts 映射表（P3）`

### P4 — 模块设置与调试二级页

1. 从 `MainScreen` 迁出模块级内容到 `ModuleSettingsPage`：颜色模式（写 `ui.json`）、配置导入导出（当前宿主）、调试与日志、帮助文档（在线 README + `compose-markdown`）、关于作者/赞助。
2. `DebugDialog` 接入「调试与日志」二级页；`DebugLog` 改直写文件（宿主目录 `logs/`）。
3. `LegacyConfigImporter` 接入「导入旧配置」入口（文件选择器 → 解析 → 写当前宿主配置）。
4. `ConfigTransfer` 改为单宿主导出/导入。

**闸门 P4**

- [ ] 颜色模式切换即时生效并持久化（重启输入法后仍是该值）；
- [ ] 导出 zip/JSON 成功、内容正确；导入后配置生效；
- [ ] 调试开关打开后 `logs/` 出现日志文件，关闭后不再增长；清除/导出可用。

**建议 commit**：`feat(host-ui): 模块设置与调试页并入宿主内设置页（P4）`

### P5 — 清理与回归

1. 删除 §2.1 标"删除"的文件；清理 `AndroidManifest.xml`（无 launcher、无 Activity、无 Service、无 `android:name`）。
2. 移除依赖：`io.github.libxposed:service`、`interface`、`miuix-blur`、`navigation-compose`、`navigationevent-compose`、`coil`（逐个 `grep` 确认后删）。
3. `proguard-rules.pro` 增加/确认：`SettingsActivity`、`Texts`、`HostActivityInjector` 不被移除（`-keep class com.haostoo.wetypehookr.** { *; }` 已覆盖，确认即可）。
4. 全量回归 + 更新文档（`DEVELOPMENT_GUIDE.md`、README 更新日志）。

**闸门 P5**

- [ ] `assembleRelease` 通过，产物可安装；
- [ ] 桌面无模块图标；`adb shell cmd package resolve-activity --brief com.haostoo.wetypehookr` 无 launcher 结果；
- [ ] §8 验收清单全绿；
- [ ] `DEVELOPMENT_GUIDE.md` 已改为新架构（至少 §1、§2.4、§3、§4.2、§4.10、§5.4 需重写，删除 Remote/Service/凭证相关段落）。

**建议 commit**：`refactor!: 移除独立设置 App 与 Remote 配置通道（P5）`

---

## 6. P0 的详细操作步骤（照做即可）

1. **确认基线**
   ```bash
   cd D:/Users/huang/IdeaProjects/WeTypeHookR
   git status --short      # 应干净（或只有本任务新增文件）
   git rev-parse --abbrev-ref HEAD   # 必须是 dev
   ```
2. **构建基线**（仓库无 `gradlew`）：
   ```bash
   /c/Users/huang/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat \
     -p 'D:/Users/huang/IdeaProjects/WeTypeHookR' :app:assembleRelease --console=plain
   ```
3. **查宿主 Activity 清单**（决定 stub），把结果写进 `HostCatalog.STUBS`：
   ```bash
   adb shell dumpsys package com.tencent.wetype        | sed -n '/Activities:/,/Providers:/p'
   adb shell dumpsys package com.bytedance.android.doubaoime | sed -n '/Activities:/,/Providers:/p'
   ```
   选 `launchMode` 为空（standard）、名称看起来"无害"的（如 `*SplashActivity`、`*WebActivity`、`*AboutActivity`）。
4. **按 §4.2 写代码**，`SettingsActivity` 先用 `TextView`。
5. **安装验证**：
   ```bash
   adb install -r app/build/outputs/apk/release/app-universal-release.apk
   adb shell am force-stop com.tencent.wetype     # 让 Hook 重新注入
   adb logcat -s WETYPE_HOOK WETYPE_INJECT WETYPE_FAB
   ```
6. 过闸门 → 记录 stub 选择结果与现象 → commit。

---

## 7. 风险与回退

| 风险 | 概率 | 表现 | 应对 |
| --- | --- | --- | --- |
| stub 被宿主声明为 `singleTask`，`newActivity` 不触发 | 中 | 点 FAB 无反应或直接进了宿主自己的页面 | 换 stub（多候选轮询）；`launch()` 后 500ms 检测未启动则 Toast |
| 宿主版本升级改了 Activity 名 | 中 | FAB 点击 Toast 失败 | `HostCatalog` 多候选 + 失败提示文案；FAB 本身不受影响 |
| 类加载失败（模块 ClassLoader 不能加载 UI 类） | 低 | `ClassNotFoundException` 崩溃宿主 | 在 `newActivity` hook 里 `try/catch`，失败回退 `chain.proceed()`（宿主走原页面），并记日志 |
| 主题/ActionBar 导致视觉异常或崩溃 | 中 | 顶部多一条 ActionBar 或 `IllegalStateException` | `requestWindowFeature(FEATURE_NO_TITLE)`；换 NoActionBar 的 stub |
| 宿主自定义 `Instrumentation` 与 hook 冲突 | 低 | 注入不生效 | 记录宿主 `Instrumentation` 类名；必要时改为 hook `ActivityThread.performLaunchActivity`（Android 13 与 14+ 签名不同，需双分支） |
| 外部存储不可用 | 低 | 配置写失败 | `getExternalFilesDir` 为 null 时回退 `filesDir` |
| 无 launcher 后 FAB 注入失败 = 用户没有入口 | 中 | 无法进入设置 | 失败提示 + README FAQ 说明；后续可考虑宿主内长按手势入口（不在本任务范围） |

**回退方案 F1（P0 失败时）**：不动 Activity 注入，改为"在宿主设置 Activity 之上叠一个全屏 `Dialog` + `ComposeView`"：

```kotlin
val dialog = Dialog(hostActivity, android.R.style.Theme_Material_Light_NoActionBar)
val view = ComposeView(hostActivity).apply {
    setViewTreeLifecycleOwner(hostActivity as? LifecycleOwner ?: HostLifecycle())
    setViewTreeSavedStateRegistryOwner(...)
    setContent { ... }
}
dialog.setContentView(view); dialog.show()
```

限制：宿主 Activity 若不是 `ComponentActivity`，需要自建 `LifecycleOwner`/`SavedStateRegistryOwner`；窗口不进入任务栈；部分输入法会因 `Dialog` 遮挡而出现输入法面板闪烁。**F1 是兜底，不是首选**；采用它必须在报告里写明原因。

---

## 8. 验收清单（交付前逐条勾）

**形态**
- [ ] 桌面无模块图标，无 launcher Activity，Manifest 无多余组件
- [ ] 模块仍被 LSPosed 正常识别（模块列表可见、作用域两个宿主）

**入口**
- [ ] 微信输入法设置页有且只有一个 FAB；其它页面没有
- [ ] 豆包输入法设置页同上
- [ ] 点 FAB 打开设置页，页面运行在宿主进程（pid、packageName 校验）
- [ ] 返回后回到输入法原设置页，输入法不重启、不崩溃

**页面**
- [ ] 顶栏第一行模块标题、第二行宿主名；点击标题/副标题出现宿主选择
- [ ] 切换宿主：可读则切换并可编辑；不可读则只读 + 说明，且不误写对方配置
- [ ] 按下/抬起：四种模式全部可用；系统预设 20 项可选；简单自定义滑杆可用；自定义波形可编辑保存
- [ ] 曲线预览点击节点可编辑；非法值有提示且保存被禁用
- [ ] 恢复默认、重载输入法（root）可用
- [ ] 模块设置：颜色模式、导入/导出配置、调试与日志、帮助文档、关于/赞助
- [ ] 页面无"框架/Remote/同步中"字样；错误提示均为 `Texts` 文案

**数据**
- [ ] 配置写入 `<host>/files/haostoo/config/`，结构符合 §4.4.2
- [ ] 改文件后 1s 内 Hook 生效
- [ ] 两个宿主的配置互不影响
- [ ] 从 1.3.0（Remote）或 1.2.x（config.xml）升级时配置被正确迁移

**工程**
- [ ] `assembleRelease` 通过；APK 内 `META-INF/xposed/*` 三个文件正确
- [ ] 源码无中文字面量（`Texts.kt` 除外）
- [ ] `DEVELOPMENT_GUIDE.md` 已同步新架构
- [ ] 提交在 `dev` 分支，未动 `master`

---

## 9. 文案 key 清单（R5 全量对照表）

> 用法：`Str.XXX.s()`；带参数用 `%s`/`%d` 并传 `s(arg)`。列表用 `StrList.XXX.items()`。
> "来源"列是**当前**出现位置，替换后应消失。

### 9.1 通用与动作

| Key | 中文（现状原文） | EN | 来源 |
| --- | --- | --- | --- |
| `AppTitle` | 震动设置 | Vibration | 新页面标题（原无） |
| `Cancel` | 取消 | Cancel | SettingsScreen:249/325/350、WaveformEditor:197 |
| `Confirm` | 确认 | OK | SettingsScreen:328 |
| `Save` | 保存 | Save | WaveformEditor:200 |
| `Close` | 关闭 | Close | DebugScreen:90、MainScreen:711/814 |
| `Back` | 返回 | Back | SettingsScreen:249 |
| `Loading` | 加载中… | Loading… | SettingsScreen:290、MainScreen:807 |
| `Unknown` | 未知 | Unknown | HostInfo:23、SettingsScreen:443 |
| `VersionFmt` | 版本 %s | Version %s | SettingsScreen:443 |
| `ComingSoon` | 链接待补充 | Link not available | MainScreen:652/659 |
| `ChannelLinkMissing` | 频道链接待补充 | Channel link not available | MainScreen:666 |
| `OpenLinkFailed` | 无法打开链接 | Cannot open link | MainScreen:530 |

### 9.2 宿主与身份

| Key | 中文 | EN | 来源 |
| --- | --- | --- | --- |
| `HostWeType` | 微信输入法 | WeType | RemoteConfig:27 |
| `HostDoubao` | 豆包输入法 | Doubao IME | RemoteConfig:28 |
| `InjectionStatus` | 注入状态 | Injection status | SettingsScreen:456 |
| `InjectNone` | 未观测到注入 | Injection not observed | SettingsScreen:458 |
| `InjectNoneHint` | 打开输入法并切换一次键盘后再回来查看 | Open the IME and switch the keyboard once, then check again | SettingsScreen:458 |
| `InjectHookMissing` | 模块已加载，Hook 未安装 | Module loaded, hook not installed | SettingsScreen:459 |
| `InjectHookMissingHint` | 输入法版本可能已变更，可通过日志反馈 | The IME version may have changed; report with logs | SettingsScreen:459 |
| `InjectOk` | Hook 已注入 | Hook injected | SettingsScreen:460 |
| `InjectOkHint` | 当前生效配置 revision %d | Active config revision %d | SettingsScreen:460 |
| `InjectHeartbeat` | 最近心跳 %s · 模块 %s | Last heartbeat %s · module %s | SettingsScreen:475 |
| `HostPickerTitle` | 选择宿主 | Choose host | 新增 |
| `HostSwitchNoAccess` | 无法读取该宿主配置（不同应用数据目录不可互访），仅供查看当前宿主 | Cannot read that host's config (separate app data), current host only | 新增 |
| `ToastInjectFailed` | 无法在输入法内打开设置页，请反馈输入法版本 | Cannot open settings inside the IME; please report the IME version | 新增（替代 HostFabInjector:163） |
| `SwitchHost` | 切换宿主 | Switch host | 新增（点击区域无障碍描述） |

### 9.3 震动设置

| Key | 中文 | EN | 来源 |
| --- | --- | --- | --- |
| `DownVibration` | 按下震动 | Press vibration | SettingsScreen:495 |
| `UpVibration` | 抬起震动 | Release vibration | SettingsScreen:495 |
| `SystemHapticStyle` | 系统震动风格 | System haptic style | SettingsScreen:514 |
| `DownShort` | 按下 | Press | SettingsScreen:174/526/540 |
| `UpShort` | 抬起 | Release | SettingsScreen:174/526/540 |
| `CurvePreview` | 曲线预览 | Curve preview | SettingsScreen:565 |
| `CurveSummaryFmt` | 强度 0~255 · 时间累计 %d ms（曲线为预览，实际按离散数值执行） | Amplitude 0–255 · total %d ms (preview only) | SettingsScreen:579 |
| `SetupWaveform` | 设置波形 | Edit waveform | SettingsScreen:585 |
| `WaveformNotSet` | 未配置 · 将载入默认四段波形 | Not set · default 4-segment waveform will load | SettingsScreen:586 |
| `WaveformSetFmt` | 已配置 · %d 段 | Configured · %d segments | SettingsScreen:586 |
| `TestDown` | 测试按下震动 | Test press vibration | SettingsScreen:596（`测试$title`） |
| `TestUp` | 测试抬起震动 | Test release vibration | 同上 |
| `DownDurationFmt` | 按下震动时长：%d ms | Press duration: %d ms | SettingsScreen:526 |
| `UpDurationFmt` | 抬起震动时长：%d ms | Release duration: %d ms | 同上 |
| `DownStrengthFmt` | 按下震动强度：%d %% | Press strength: %d%% | SettingsScreen:540 |
| `UpStrengthFmt` | 抬起震动强度：%d %% | Release strength: %d%% | 同上 |

### 9.4 其他选项与提示

| Key | 中文 | EN | 来源 |
| --- | --- | --- | --- |
| `ResetDefault` | 恢复默认配置 | Restore defaults | SettingsScreen:319/619 |
| `ResetDefaultSummary` | 恢复当前宿主的默认震动与波形 | Restore default vibration and waveform for this host | SettingsScreen:620 |
| `ResetConfirm` | 将当前宿主的震动设置与自定义波形恢复为默认值？ | Restore this host's vibration settings and waveform to defaults? | SettingsScreen:320 |
| `ResetDone` | 已恢复默认配置 | Defaults restored | SettingsScreen:205 |
| `ReloadIme` | 重载输入法 | Reload IME | SettingsScreen:627 |
| `ReloadImeSummary` | 通过 Root 快速重启目标输入法进程 | Restart the target IME process via root | SettingsScreen:628 |
| `ReloadOkFmt` | 成功：已重启 %s | Done: %s restarted | HapticEngine:96 |
| `ReloadNoRoot` | 失败：没有 root 权限 | Failed: no root | HapticEngine:93 |
| `ReloadNoSu` | 失败：找不到 su | Failed: su not found | HapticEngine:94 |
| `ReloadFailFmt` | 失败：%s | Failed: %s | HapticEngine:95/98 |
| `WaveformSaveFailed` | 波形保存失败 | Failed to save waveform | SettingsScreen:169 |
| `WaveformSavedFmt` | %s波形已保存 | %s waveform saved | SettingsScreen:174 |
| `WaveformInvalid` | 波形数据不合法 | Invalid waveform data | SettingsScreen:195 |
| `WaveformLegacyWarn` | 检测到历史波形数据长度或数组不一致，已载入默认波形草稿；保存后将按当前数量规范化。 | Legacy waveform data is inconsistent; a default draft was loaded and will be normalized on save. | SettingsScreen:181 |
| `ReadConfigFailed` | 读取当前配置失败 | Failed to read config | SettingsScreen:218 |

### 9.5 波形编辑器

| Key | 中文 | EN | 来源 |
| --- | --- | --- | --- |
| `DownWaveform` | 按下波形 | Press waveform | WaveformEditor:212/230、SettingsScreen:236 |
| `UpWaveform` | 抬起波形 | Release waveform | 同上 |
| `SegmentCountFmt` | 有效波段数（%d~%d） | Segments (%d–%d) | WaveformEditor:245 |
| `SegmentIndexFmt` | 波段 %d | Segment %d | WaveformEditor:280/331 |
| `SegmentZeroFixed` | 0（固定） | 0 (fixed) | WaveformEditor:287 |
| `TimingLabel` | 时长(ms) | Duration (ms) | WaveformEditor:298 |
| `AmplitudeLabel` | 强度(0-255) | Amplitude (0–255) | WaveformEditor:306 |
| `TimingLabelRangeFmt` | 时长(ms，0~%d) | Duration (ms, 0–%d) | WaveformEditor:339 |
| `TimingZeroFixedHint` | 时长：0 ms（首段固定，不可编辑） | Duration: 0 ms (first segment is fixed) | WaveformEditor:347 |
| `AmplitudeLabelRangeFmt` | 强度(0~%d) | Amplitude (0–%d) | WaveformEditor:356 |
| `WaveformSyncHint` | 调整会立即同步到曲线与下方输入行。 | Changes sync to the curve and rows below. | WaveformEditor:363 |
| `WaveformDownDesc` | 手指按下时的自定义波形；曲线为预览，实际按离散数值执行。 | Custom waveform on press; the curve is a preview. | WaveformEditor:232 |
| `WaveformUpDesc` | 手指抬起时的自定义波形；曲线为预览，实际按离散数值执行。 | Custom waveform on release; the curve is a preview. | WaveformEditor:234 |
| `WaveformEditorSummaryFmt` | 强度 0~255 · 时间累计 %d ms · 选中：波段 %d（时长 %dms，强度 %d） | Amplitude 0–255 · total %d ms · selected: segment %d (%d ms, amplitude %d) | WaveformEditor:262 |
| `InputInteger` | 请输入整数 | Enter an integer | WaveformEditor:401 |
| `InputRangeFmt` | 范围 %d~%d | Range %d–%d | WaveformEditor:402 |
| `ErrSegmentCountFmt` | 有效波段数需在 %d~%d 之间 | Segment count must be %d–%d | WaveformEditor:94 |
| `ErrSegmentIncomplete` | 波段数据不完整 | Incomplete segment data | WaveformEditor:95 |
| `ErrFirstTimingZero` | 首段时长必须为 0 | First segment duration must be 0 | WaveformEditor:96 |
| `ErrTimingNegativeFmt` | 波段 %d 时长不能为负 | Segment %d duration cannot be negative | WaveformEditor:98 |
| `ErrTimingTooLongFmt` | 波段 %d 时长不能超过 %d ms | Segment %d duration cannot exceed %d ms | WaveformEditor:99 |
| `ErrAmplitudeRangeFmt` | 波段 %d 强度需在 0~%d 之间 | Segment %d amplitude must be 0–%d | WaveformEditor:100 |
| `ErrNoPositiveTiming` | 至少需要一段大于 0 ms 的波段 | At least one segment must be longer than 0 ms | WaveformEditor:102 |
| `WaveformCurveA11y` | 波形曲线预览，点击节点可编辑对应波段；下方输入行同样可编辑 | Waveform preview; tap a node to edit that segment | WaveformCurve:168 |

### 9.6 模块设置 / 调试 / 关于

| Key | 中文 | EN | 来源 |
| --- | --- | --- | --- |
| `ColorMode` | 颜色模式 | Color mode | MainScreen:544 |
| `DebugAndLogs` | 调试与日志 | Debug & logs | DebugScreen:56 |
| `DebugOutput` | 调试输出 | Debug output | DebugScreen:66、MainScreen:588 |
| `DebugOutputSummary` | 开启后按分类上报日志到模块目录（保存后生效） | Write categorized logs to the config dir | DebugScreen:67 |
| `ClearLogs` | 清除日志 | Clear logs | DebugScreen:75、MainScreen:608 |
| `ClearLogsSummary` | 删除该宿主全部日志 | Delete this host's logs | DebugScreen:76 |
| `ClearLogsAllSummary` | 删除全部宿主日志 | Delete all logs | MainScreen:609 |
| `LogsCleared` | 日志已清除 | Logs cleared | DebugScreen:79、MainScreen:615 |
| `ExportLogs` | 导出日志 | Export logs | DebugScreen:84、MainScreen:600 |
| `ExportLogsSummary` | 打包该宿主日志为 zip，选择保存位置 | Zip this host's logs | DebugScreen:85 |
| `ExportLogsAllSummary` | 打包全部宿主日志为 zip | Zip all logs | MainScreen:601 |
| `LogsExported` | 日志已导出 | Logs exported | DebugScreen:44、MainScreen:513 |
| `LogsEmpty` | 没有可导出的日志 | No logs to export | DebugScreen:50、MainScreen:519 |
| `ExportFailedFmt` | 导出失败：%s | Export failed: %s | DebugScreen:46、MainScreen:481/515 |
| `ExportConfig` | 导出配置 | Export config | MainScreen:563 |
| `ExportConfigSummary` | 导出当前宿主设置与波形（JSON） | Export this host's settings and waveform (JSON) | MainScreen:564（原为"全部宿主"） |
| `ImportConfig` | 导入配置 | Import config | MainScreen:571 |
| `ImportConfigSummary` | 从 JSON 恢复当前宿主设置与波形 | Restore this host's settings from JSON | MainScreen:572 |
| `ConfigExported` | 配置已导出 | Config exported | MainScreen:479 |
| `ConfigImported` | 配置已导入 | Config imported | MainScreen:496 |
| `ReadFileFailed` | 读取文件失败 | Failed to read file | MainScreen:494 |
| `ImportFormatError` | 导入失败：文件格式不正确 | Import failed: bad file format | MainScreen:498 |
| `HelpDoc` | 帮助文档 | Help | MainScreen:628/774 |
| `HelpDocSummary` | 使用说明与波形格式 | Usage and waveform format | MainScreen:629 |
| `HelpLoadFailed` | 加载失败，请检查网络，或到仓库查看 README。 | Failed to load. Check network or read the README in the repo. | MainScreen:801 |
| `AboutAuthor` | 关于作者 | About | MainScreen:645 |
| `Repo` | 仓库 | Repository | MainScreen:648 |
| `Coolapk` | 酷安主页 | Coolapk | MainScreen:655 |
| `Channel` | 频道 | Channel | MainScreen:662 |
| `Sponsor` | 赞助 | Sponsor | MainScreen:669/689 |
| `SponsorSummary` | 扫码支持作者，也可以先保存图片再识别 | Scan to support the author, or save the image first | MainScreen:690 |
| `SponsorQrA11y` | 赞助二维码 | Sponsor QR code | MainScreen:698 |
| `SaveImage` | 保存图片 | Save image | MainScreen:717 |
| `SavedToGallery` | 已保存到相册：Pictures/WeDouVibrator | Saved to Pictures/WeDouVibrator | MainScreen:756 |
| `SaveFailed` | 保存失败 | Save failed | MainScreen:756 |

### 9.7 列表映射（`StrList`）

| StrList | 中文 | EN |
| --- | --- | --- |
| `Modes` | 无震动 / 系统预设风格 / 简单自定义 / 自定义波形 | Off / System preset / Simple custom / Custom waveform |
| `SystemHaptics` | Virtual Key、Virtual Key Release、Keyboard Tap、Keyboard Press、Keyboard Release、Long Press、Context Click、Confirm、Reject、Clock Tick、Gesture Start、Gesture End、Gesture Threshold Activate、Gesture Threshold Deactivate、Drag Start、Text Handle Move、Segment Tick、Segment Frequent Tick、Toggle On、Toggle Off（20 项，中英相同） | 同左 |
| `ColorModes` | 浅色 / 深色 / 跟随系统 | Light / Dark / Follow system |

> 系统设置页的「系统版本 / 应用版本 / Android 版本 / 框架版本 / 设备型号」属概览页内容，概览页随 `MainScreen` 删除；如需保留，改为「关于」二级页，key 为 `SysVersion` / `AppVersion` / `AndroidVersion` / `FrameworkVersion` / `DeviceModel`。

---

## 10. 禁止事项

1. **不要**尝试 `BaseDexClassLoader`/`pathList`/`addDexPath` 注入 dex（hidden API + 版本脆弱，本任务不需要）。
2. **不要**去 hook `PackageManager` 伪造 `ActivityInfo`：system_server 不认，客户端伪造只会引入不一致。
3. **不要**恢复 LibXposed Remote / `XposedService` / AIDL / `HostGrant` / 免 Root 方案。
4. **不要**在宿主进程里 `createPackageContext(模块包)` 取资源（会被包可见性拦截），只用 `getResourcesForApplication(getModuleApplicationInfo())`。
5. **不要**在 `Instrumentation.newActivity` hook 里做除"判断 marker + 换两参数"之外的任何事，更不能吞掉宿主自己的 Activity 创建。
6. **不要**在输入事件 Hook 路径里加 IO、锁、反射查找。
7. **不要**用 `androidx.compose.ui.window.Dialog` / 自绘悬浮窗替代 miuix `Overlay*`；所有弹层必须在 `Scaffold` 子树内。
8. **不要**删除 `META-INF/xposed/` 三个文件；**不要**给模块加回 `LAUNCHER`。
9. **不要**把配置写到模块自己的目录（R3 明确要求宿主目录）。
10. **不要**在未获用户批准时合并到 `master`，也不要 `git push --force`。
11. 设备操作边界：未经许可不重启手机、不改 LSPosed 数据库、不 `kill` 系统进程；`am force-stop <宿主>` 仅在你自己的调试流程里使用，并在报告里说明。

---

## 11. 调试手册

| 场景 | 命令 / 方法 |
| --- | --- |
| 看注入是否装好 | `adb logcat -s WETYPE_HOOK WETYPE_INJECT` |
| 看 Activity 是否被替换 | 在 `newActivity` hook 里 `Log.i("WETYPE_INJECT", "swap -> $className")` |
| 看设置页进程 | 页面左上角显示 `packageName` + `pid`，对比 `adb shell pidof com.tencent.wetype` |
| 看配置文件 | `adb shell run-as com.tencent.wetype ls -l files/haostoo/config`（外部目录：`adb shell ls /sdcard/Android/data/com.tencent.wetype/files/haostoo/config`） |
| 看日志文件 | `adb shell run-as ... cat files/haostoo/logs/vibrate.log` |
| 强制重注入 | `adb shell am force-stop com.tencent.wetype` 后重新切出键盘 |
| 复现配置热更新 | 改 `settings.json` 的 `down_strength` → 立刻按键听感变化 |
| 构建 | 见 §6 第 2 步 |

日志 tag 约定（新增两个，其余沿用）：`WETYPE_INJECT`（注入相关）、`WETYPE_HOSTCFG`（配置读写/迁移）。

---

## 12. 交付物

1. 代码：`dev` 分支上的 P0–P5 提交。
2. 报告：仓库根目录的 `MIGRATION_REPORT.md`，包含
   - 两个宿主上 stub 的最终选择与 `launchMode` 实测值；
   - P0–P5 各闸门的通过情况与证据（日志片段/截图可选）；
   - 与本文档的偏差清单（哪里没按方案做、为什么）；
   - 已知问题与后续建议。
3. 文档回写：`DEVELOPMENT_GUIDE.md` 更新为新架构；README 更新更新日志与"入口只在输入法设置页"的说明。

---

## 附录 A：常用路径速查

```
配置：<host>/files/haostoo/config/settings.json
波形：<host>/files/haostoo/config/haptic.json
UI：  <host>/files/haostoo/config/ui.json
日志：<host>/files/haostoo/logs/<category>.log
外部目录根：/sdcard/Android/data/<host>/files/haostoo/
```

## 附录 B：分支与提交

```bash
git checkout dev
# 每阶段一次提交，建议 message 见各阶段末尾
git log --oneline -6
# 完成后由用户批准再合并：git checkout master && git merge --no-ff dev
```
