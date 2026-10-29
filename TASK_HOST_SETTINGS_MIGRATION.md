# 开发任务书：设置页运行于宿主进程（YukiHook 底座）

> **文档性质**：可直接执行的施工文档，面向对本项目 0 了解的执行代理。
> **当前版本**：`1.3.0 (1300)`　·　**分支**：`dev`
> **技术底座**：YukiHook（`yukihook-core` + `yukihook-runtime-libxposed` + `yukihook-compiler`）跑在 LibXposed API 102 之上
> **配套文档**：`DEVELOPMENT_GUIDE.md`（架构与规范参考）。两者冲突时以本文为准。

> **实施进度（2026-10-29）**
> - 已完成：P0 底座切换（`82dd316`）、设置页顶栏与宿主内页面（`6a9379a`）、launcher 与壳层移除、配置落宿主目录。
> - **待做**：P1.5 存储收敛为单快照（当前仍是 `settings.json` / `haptic.json` / `ui.json` 三文件）；P3 模块设置与调试页；P4 文案资源化（仅有 11 条资源，源码中仍有约 90 处中文字面量）；P5 残留清理与回归。

---

## 0. 使用说明

### 0.1 阅读顺序

| 顺序 | 文件 | 读什么 |
| --- | --- | --- |
| 1 | 本文件 §1–§7 | 目标形态、依赖、入口、Activity、配置、UI、文案 |
| 2 | 本文件 §8–§12 | 阶段与闸门、文件清单、验收、禁止事项、调试 |
| 3 | `DEVELOPMENT_GUIDE.md` | miuix 组件规范（§6）、构建与排查（§7）、API 102 能力（§5） |

### 0.2 完成定义（DoD）

1. 模块没有任何 launcher Activity；安装后桌面无图标，入口只在输入法设置页的 FAB。
2. FAB 打开的设置页**运行在宿主进程内**（`packageName`、`pid`、`filesDir` 均为宿主）。
3. 设置页顶栏：主标题为模块名，副标题为宿主名；点击标题区可切换目标宿主。
4. 所有配置读写落在 **宿主自己的 `files` 目录**，改完即时生效（Hook 通过文件监听热更新）。
5. 文案全部在 `res/values/strings.xml` 与 `res/values-en/strings.xml`，Compose 侧无中文字面量。
6. `assembleRelease` 通过；两个宿主真机手测通过 §11。
7. 提交在 `dev` 分支；**不得**合并到 `master`（需用户明确批准）。

### 0.3 工作方式

- 按 §8 的阶段推进，**每个阶段结束必须过闸门**，不过就停下写报告，不要带病进入下一阶段。
- 每个阶段单独 commit，message 用阶段末尾给出的建议值。
- 遇到宿主差异（Activity 名、launchMode）记到仓库根目录 `HOST_NOTES.md`。
- 不确定的 API 名称以 IDE 补全与 1.5.0 的 KDoc 为准，不要照文档版本号臆造。

---

## 1. 目标形态

模块不再是"一个 App + 一个模块"，而是**只作为 Xposed 模块存在**：没有桌面入口，设置界面寄生在输入法进程里，由输入法设置页的浮动按钮唤起。

```
用户路径：
输入法设置页 → 点击侧边 FAB
            → 宿主进程内启动 SettingsActivity（模块类，运行在宿主）
            → 顶栏显示「模块名 / 当前宿主名」，点标题区可切换宿主
            → 改动即时写宿主配置目录，输入法立刻生效
```

需求对照：

| 编号 | 需求 | 落点 |
| --- | --- | --- |
| R1 | 移除设置应用（模块无 launcher Activity） | §3 入口只有 `HookEntry`；Manifest 无 `<activity>`、无 LAUNCHER |
| R2 | 设置页改为在宿主内注册 Activity | §4 `registerModuleActivities()` + `ModuleActivity` |
| R3 | 配置路径回到宿主数据目录 | §5 `HostPaths` + `SettingsStore` |
| R4 | 复用现有设置 UI；标题下方显示宿主名；标题可点切换宿主 | §6 `SettingsContent` + `SettingsTopBar` |
| R5 | 所有文案集中映射，便于统一修改与多语言 | §7 `strings.xml` + `values-en` + `string-array` |

---

## 2. 目标架构

```
┌─────────────── 宿主进程（com.tencent.wetype / com.bytedance.android.doubaoime）───────────────┐
│                                                                                              │
│  HookEntry（@YukiHookLibXposedEntry，KSP 生成 META-INF/xposed）                                │
│    └─ encase { loadApp(<host>) {                                                             │
│           installTouchHooks()                  # 触摸 → 震动                                   │
│           SettingsStore.attach(host)           # 读宿主配置 + FileObserver 热更新              │
│           withApplicationReady { app ->                                                      │
│               app.registerModuleActivities(<proxy>)   # 让模块 Activity 可在宿主启动           │
│           }                                                                                  │
│           HostFabInjector.install(this)        # 设置页注入 FAB                               │
│       } }                                                                                    │
│                                                                                              │
│  SettingsActivity : BaseHostActivity（AppCompatActivity + ModuleActivity）                     │
│    └─ Compose：SettingsContent(host)                                                          │
│         ├─ SettingsTopBar（标题 + 宿主副标题，点击切换宿主）                                     │
│         ├─ 注入状态卡（进程内状态）                                                             │
│         ├─ 按下 / 抬起震动卡、波形编辑器（复用现有组件）                                          │
│         └─ 二级页：模块设置 / 调试与日志 / 帮助 / 关于                                           │
│                                                                                              │
│  数据：<host>/files/haostoo/{config/state.json, config/legacy/, logs/}                        │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

进程内只有一套状态：UI 与 Hook 共享同一批文件与同一个内存缓存，没有 IPC、没有跨进程配置通道、没有启动凭证。

---

## 3. 依赖与入口

### 3.1 依赖

```kotlin
// 框架 API：只在宿主进程存在，绝不能打包
compileOnly("io.github.libxposed:api:102.0.0")

// YukiHook：core + libxposed 底座 + 编译器
implementation("com.highcapable.yukihookapi:yukihook-core:1.5.0")
implementation("com.highcapable.yukihookapi:yukihook-runtime-libxposed:1.5.0")
ksp("com.highcapable.yukihookapi:yukihook-compiler:1.5.0")

// ModuleActivity 需要 AppCompat（core 已传递，显式声明锁定版本）
implementation("androidx.appcompat:appcompat:1.7.1")

// 配置序列化
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")   // 以当前最新稳定版为准
```

- 应用两个编译插件（根项目 `plugins { … apply false }`）：
  - `com.google.devtools.ksp`（YukiHook 编译器需要）
  - `org.jetbrains.kotlin.plugin.serialization`（版本与 Kotlin 一致，即 `2.4.10`）
- 两者互不冲突：KSP 是符号处理，serialization 是编译器插件。
- 移除不再使用的依赖：`io.github.libxposed:service`、`io.github.libxposed:interface`、`miuix-blur`、`navigation-compose`、`navigationevent-compose`、`coil-compose`（逐个 `grep` 确认零引用后再删）。
- 保留：`miuix-ui`、`miuix-preference`、`miuix-icons`、`compose-bom`、`material3`、`compose-markdown`。
- 传递依赖会带来 `kotlin-stdlib 2.4.21`，与本项目 Kotlin 2.4.10 同属 2.4 线，Gradle 取高版本即可；若编译报元数据版本错误，先升 Kotlin 到 2.4.21 再排查。

### 3.2 资源包 ID（必须改）

模块资源要注入宿主，必须与宿主资源 ID 隔离：

```kotlin
android {
    androidResources.additionalParameters +=
        listOf("--allow-reserved-package-id", "--package-id", "0x64")
}
```

- 不要用 `0x7f`（宿主默认段）。
- 若同一宿主已装其他模块占用 `0x64`，改 `0x65`–`0x7e` 中未占用值；改完在两个宿主上重新验证资源显示。

### 3.3 入口类

```kotlin
@YukiHookLibXposedEntry(
    targetApiVersion = 102,
    scope = ["com.tencent.wetype", "com.bytedance.android.doubaoime"],
    staticScope = true,
    hotReload = YukiHookLibXposedEntry.HotReload.NONE,
)
object HookEntry : YukiHookXposedModule {

    override fun onHook() = encase {
        HostCatalog.ALL.forEach { host ->
            loadApp(host) {
                installTouchHooks()
                SettingsStore.attach(appContext, host)
                withApplicationReady { app ->
                    app.registerModuleActivities(HostCatalog.proxyFor(host))
                }
                HostFabInjector.install(this)          // fun PackageParam.install()
            }
        }
    }
}
```

要点：

- `encase` / `loadApp` / `withApplicationReady` 由 `yukihook-core` 提供（`com.highcapable.yukihookapi.hook.factory.*`、`hook.param.PackageParam`）。
- **必须删除** `app/src/main/resources/META-INF/xposed/` 下三个手写文件（`module.prop`、`java_init.list`、`scope.list`）；编译器会根据注解生成它们。源码里同时存在手写文件会直接编译失败。
- 模块名称/描述改由 Manifest 提供：`<application android:label="@string/app_name" android:description="@string/module_description">`。
- `hotReload = NONE`：设置页常驻时热重载会被拒（框架要求模块 Activity 先销毁），本项目不启用。
- 触摸 Hook 用 YukiHook DSL 重写：

```kotlin
private fun PackageParam.installTouchHooks() {
    val host = packageName
    val (className, methodName) = if (host == HostCatalog.WETYPE)
        "com.tencent.wetype.plugin.hld.view.ImeRootView" to "dispatchTouchEvent"
    else
        "com.bytedance.android.input.keyboard.KeyboardView" to "onTouchEvent"

    runCatching {
        className.toClass()
            .method { name = methodName; param(MotionEvent::class) }
            .hook {
                before {
                    val view = instance as? View ?: return@before
                    val event = args[0] as? MotionEvent ?: return@before
                    HapticEngine.dispatch(view, event)
                }
            }
    }.onFailure { Log.w("WETYPE_HOOK", "hook install failed: $host", it) }
}
```

- 具体 DSL 名称（`toClass` / `method` / `hook` / `before`）以 1.5.0 实际 API 为准；若与示例不符，用 IDE 补全修正，**不要**改回框架原生 `hook()`。
- Hook 内容仍然只读内存缓存、只做副作用，不改参数、不吞结果。

---

## 4. 宿主内设置 Activity

### 4.1 注册

```kotlin
withApplicationReady { application ->
    application.registerModuleActivities(HostCatalog.proxyFor(host))
}
```

- `registerModuleActivities(proxyClassName)` 的作用：让**未在宿主 Manifest 声明**的模块 Activity 可以在宿主进程里被启动。
- 框架负责 Intent 转换与启动期替换：调用方只需 `startActivity`，不感知 proxy 的存在，也不需要额外的 flag 或约定参数。
- 必须在宿主 `Application` 就绪之后调用（`withApplicationReady`）。

### 4.2 proxy 选择

`proxy` 必须是**宿主 Manifest 里真实存在**的 Activity，建议直接用两个设置页：

| 宿主 | proxy 候选 |
| --- | --- |
| `com.tencent.wetype` | `com.tencent.wetype.plugin.hld.reactnative.activity.ImeMainSettingActivity` |
| `com.bytedance.android.doubaoime` | `com.bytedance.android.doubaoime.activity.SettingsActivityNext` |

选择标准（P0 必须真机确认）：

- `launchMode` 为 `standard`（若是 `singleTask`/`singleInstance`，二次打开可能只触发 `onNewIntent`）；
- 主题无 ActionBar、非透明；
- 启动本身无副作用。

查询命令：

```bash
adb shell dumpsys package com.tencent.wetype | sed -n '/Activities:/,/Providers:/p'
adb shell dumpsys package com.bytedance.android.doubaoime | sed -n '/Activities:/,/Providers:/p'
```

候选不满足时，在 `HostCatalog` 里换成其它 `standard` 的宿主 Activity，并在 `HOST_NOTES.md` 记录版本与实测结果。

### 4.3 Activity 基类

```kotlin
abstract class BaseHostActivity : AppCompatActivity(), ModuleActivity {

    override val moduleTheme get() = R.style.Theme_WeTypeHookR

    override fun getClassLoader() = delegate.getClassLoader()

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.onCreate(savedInstanceState)
        super.onCreate(savedInstanceState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        delegate.onConfigurationChanged(newConfig)
        super.onConfigurationChanged(newConfig)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        delegate.onRestoreInstanceState(savedInstanceState)
        super.onRestoreInstanceState(savedInstanceState)
    }
}

class SettingsActivity : BaseHostActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SettingsApp() }
    }
}
```

约定：

- 三个生命周期方法与 `getClassLoader()` 的委托**不能省**：它们负责主题、资源配置变化、`savedInstanceState` 的类加载器。
- `moduleTheme` 必须是 AppCompat 主题（见 §4.4）；返回 `-1` 会退回宿主主题，导致 AppCompat 组件异常。
- 宿主的 `packageName` 就是目标宿主：`val host = packageName`，不要从 Intent extra 取。
- `finish()` 即返回输入法设置页，不要自定义转场动画。

### 4.4 主题

`res/values/themes.xml`：

```xml
<style name="Theme.WeTypeHookR" parent="Theme.AppCompat.DayNight.NoActionBar">
    <item name="android:windowBackground">@android:color/transparent</item>
</style>
```

- 页面视觉仍由 `MiuixTheme(colors = ...)` 决定，XML 主题只提供窗口基础（无 ActionBar、无标题）。
- 深浅色由 `state.json` 的 `ui.color_mode` 驱动 Compose 配色，不依赖 `DayNight`。

### 4.5 启动

```kotlin
// HostFabInjector 的点击回调
context.startActivity(Intent(context, SettingsActivity::class.java))
```

- 不需要 `FLAG_ACTIVITY_NEW_TASK`（从 Activity 启动）。
- 目标类必须是 `ModuleActivity` 子类，否则不会被注册机制接管。
- 启动失败（异常或 500ms 内 `SettingsActivity.onCreate` 未置位）时 Toast 提示，文案见 §7 `toast_open_settings_failed`。

---

## 5. 配置与日志

### 5.1 路径

```kotlin
object HostPaths {
    private const val ROOT = "haostoo"
    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, ROOT).apply { mkdirs() }
    fun configDir(ctx: Context) = File(root(ctx), "config").apply { mkdirs() }
    fun state(ctx: Context) = File(configDir(ctx), "state.json")   // 唯一配置快照
    fun legacyDir(ctx: Context) = File(configDir(ctx), "legacy").apply { mkdirs() }
    fun logs(ctx: Context) = File(root(ctx), "logs").apply { mkdirs() }
}
```

最终位置：`/sdcard/Android/data/<host>/files/haostoo/config/state.json`；外部存储不可用时回退 `filesDir`。

### 5.2 单快照模型

设置、波形、UI 偏好合并在**一个文件**里，一次原子写即一次完整提交，不存在跨文件半提交。

```kotlin
@Serializable
data class HostState(
    val schema: Int = SettingsModel.SCHEMA_VERSION,          // 3
    @SerialName("updated_at") val updatedAt: Long = 0L,
    val settings: SettingsState = SettingsState(),
    val waveform: CustomHapticConfig? = null,
    val ui: UiState = UiState(),
)

@Serializable
data class SettingsState(
    @SerialName("down_mode") val downIndex: Int = 1,
    @SerialName("up_mode") val upIndex: Int = 1,
    @SerialName("down_duration") val downDuration: Float = 50f,
    @SerialName("down_strength") val downStrength: Float = 0.5f,
    @SerialName("up_duration") val upDuration: Float = 50f,
    @SerialName("up_strength") val upStrength: Float = 0.5f,
    @SerialName("down_system") val downSystemIndex: Int = 0,
    @SerialName("up_system") val upSystemIndex: Int = 3,
    @SerialName("theme_mode") val themeMode: Int = 2,
    @SerialName("debug_log") val debugLog: Boolean = false,
)

@Serializable
data class CustomHapticConfig(
    val down: WaveformHaptic? = null,
    val up: WaveformHaptic? = null,
)

@Serializable
data class WaveformHaptic(
    val timings: List<Long>,          // 由 LongArray 改为 List，便于序列化
    val amplitudes: List<Int>,
)

@Serializable
data class UiState(                   // 从 SettingsStore 内迁到模型
    @SerialName("fab_x") val fabX: Float = 0f,
    @SerialName("fab_y") val fabY: Float = 0.62f,
    @SerialName("color_mode") val colorMode: Int = 2,
)
```

命名沿用现有代码（`SettingsState` / `CustomHapticConfig` / `WaveformHaptic`），只新增 `HostState` 与 `UiState` 的位置调整，避免全项目改名。`WaveformHaptic` 的两组值改为 `List`，震动调用处用 `toLongArray()` / `toIntArray()` 还原。

文件示例：

```json
{
  "schema": 3,
  "updated_at": 1730000000000,
  "settings": {
    "down_mode": 1, "up_mode": 1, "down_system": 0, "up_system": 3,
    "down_duration": 50.0, "down_strength": 0.5,
    "up_duration": 50.0, "up_strength": 0.5,
    "theme_mode": 2, "debug_log": false
  },
  "waveform": {
    "down": {"timings": [0, 40, 10, 10], "amplitudes": [0, 250, 0, 100]},
    "up":   {"timings": [0, 40, 10, 10], "amplitudes": [0, 180, 0, 90]}
  },
  "ui": {"fab_x": 0.0, "fab_y": 0.62, "color_mode": 2}
}
```

约定：

- JSON 字段统一 **snake_case**（用 `@SerialName` 固定），Kotlin 属性用 camelCase。这样旧文件、导出文件、手改调试的键名保持一致。
- 每个字段都有默认值 → 缺字段可读；`Json { ignoreUnknownKeys = true; encodeDefaults = true }` → 多字段可忽略、写盘时保留完整结构。
- `schema` 标识结构版本；结构变更时自增并写迁移分支。
- `updatedAt` 仅用于"文件变了没有"的判等与日志，**不再承担版本冲突语义**（单进程、单写者，无并发写）。

### 5.3 写入

```kotlin
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Synchronized
fun update(context: Context, host: String, transform: (HostState) -> HostState): Boolean {
    val next = transform(_state.value).copy(updatedAt = System.currentTimeMillis())
    val ok = writeState(context, next)        // AtomicFile.startWrite / finishWrite
    if (ok) apply(next)                        // 回填 StateFlow + HapticEngine 缓存
    return ok
}

/** 主线程调用点（FAB 落位、导入）走这个，避免在主线程写盘。 */
fun updateAsync(context: Context, host: String, transform: (HostState) -> HostState) {
    ioExecutor.execute { update(context.applicationContext, host, transform) }
}
```

- 用 `androidx.core.util.AtomicFile`（`core-ktx` 已引入）：内部走临时文件 + `fsync` + `rename`，并维护备份，写入中断不会留下半截文件。
- 所有写操作经 `SettingsStore.update`，外部不得直接 `File.writeText`；`@Synchronized` 串行化写入。
- 不做 `suspend` 改造：整份文件约 1KB，写路径本就短，UI 侧已在 `Dispatchers.IO` 内调用，引入协程 API 只会让 Hook 启动路径和 `onDispose` 补写变复杂。
- UI 侧滑杆仍是 200ms 防抖后调 `update`，避免逐帧写盘。

### 5.4 读取与热更新

- 启动：`attach(context, host)` 读一次 → 解析 → 得到 `HostState` → 填充 `HapticEngine.cachedSettings` / `cachedCustomHaptic`。
- 向 UI 暴露 `StateFlow<HostState>`，Compose 直接 `collectAsState()`，不再自己轮询文件。
- `readExternal(host)`：只读地读一次另一个宿主的 `state.json`（拼 `Environment.getExternalStorageDirectory()/Android/data/<host>/files/haostoo/config/state.json`，失败返回 null），供宿主切换时查看。
- Hook 侧监听 `FileObserver(configDir, CLOSE_WRITE or MOVED_TO)`，只处理 `state.json`：
  - 200ms 去抖；
  - `updatedAt` 与内存中的值相同则跳过（自写回环）；
  - 重读成功后原子替换缓存；
  - 解析失败保留上一份有效缓存，`Log.w("WETYPE_CFG", …)`；
  - `FileObserver` 用字段强引用持有。

### 5.5 损坏与恢复

解析失败（截断、手改成非法 JSON、磁盘异常）时：

1. 把当前文件改名 `state.bad.<时间戳>.json` 留证，不直接覆盖；
2. 尝试默认值继续运行；
3. 写一条 `WETYPE_CFG` 日志说明已回退。

`AtomicFile` 已覆盖"写一半"的场景（崩溃后仍是上一份完整内容）；本节的兜底只针对"内容本身非法"。

### 5.6 首次迁移

`state.json` 不存在时，按顺序取一次初始值并落盘：

1. 旧三件套 `config/{settings,haptic,ui}.json`（若存在）→ 合并读入；
2. 旧 `config/config.xml`（若存在）→ `LegacyConfigImporter` 解析；
3. 默认值（`HostState()`）。

迁移成功后把旧文件移入 `config/legacy/`（保留一份，便于回滚），之后只读 `state.json`。

### 5.7 导入导出

复用 `HostState` 的序列化器，外层加信封：

```json
{"format": 1, "host": "com.tencent.wetype", "state": { … }}
```

- 导出即把当前 `HostState` 包进信封写出（SAF `CreateDocument`）；
- 导入先校验 `format` 与 `schema`，再经 `SettingsStore.update` 落盘，不直接写文件。

### 5.8 日志

同进程，直接写文件：

```
<host>/files/haostoo/logs/{hardware,vibrate,ui}.log
```

- `debug_log=false` 时不写；开启后每次震动写一行（模式、时长、强度）。
- 单文件超过 512KB 时轮转为 `.1`，最多保留两份。
- 写盘放单线程队列，异常只 `Log.w`，绝不影响输入法。

---

## 6. 页面结构

### 6.1 骨架

```kotlin
@Composable
fun SettingsApp() {
    val context = LocalContext.current
    val host = context.packageName                    // 运行在宿主进程，天然是宿主
    var targetHost by remember { mutableStateOf(host) }
    var showHostPicker by remember { mutableStateOf(false) }

    // 当前宿主：订阅快照；其它宿主：一次性只读读取（可能失败）
    val primary by SettingsStore.state.collectAsState()
    val state = if (targetHost == host) primary else SettingsStore.readExternal(targetHost)
    val readOnly = targetHost != host

    val dark = when (state?.ui?.colorMode ?: 2) {     // 0 浅 1 深 2 跟随
        0 -> false
        1 -> true
        else -> isSystemInDarkTheme()
    }

    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
        Scaffold(topBar = {
            SettingsTopBar(
                hostName = HostCatalog.displayName(targetHost),
                onBack = null,
                onSwitchHost = { showHostPicker = true },
            )
        }) { padding ->
            SettingsContent(
                host = targetHost,
                state = state,
                readOnly = readOnly,
                modifier = Modifier.padding(top = padding.calculateTopPadding()),
            )
        }
        if (showHostPicker) HostPickerDialog(
            current = targetHost,
            onDismiss = { showHostPicker = false },
            onSelect = { targetHost = it; showHostPicker = false },
        )
    }
}
```

### 6.2 顶栏（标题 + 宿主副标题 + 可点击）

miuix 的 `TopAppBar` 有 `subtitle` 参数，但 `title` 是 `String`，挂不了点击；因此自写 `SettingsTopBar`：

```kotlin
@Composable
fun SettingsTopBar(hostName: String, onBack: (() -> Unit)?, onSwitchHost: () -> Unit) {
    Surface(color = MiuixTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.action_back))
                }
            }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { onSwitchHost() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MiuixTheme.textStyles.headline2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        hostName,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        MiuixIcons.ArrowUpDown,
                        stringResource(R.string.action_switch_host),
                        modifier = Modifier.padding(start = 2.dp).size(12.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
            }
        }
    }
}
```

- `MiuixIcons.ArrowUpDown` 若不存在，改用 `MiuixIcons.Tune`（以 IDE 补全为准）。
- 二级页（波形编辑、模块设置）传 `onBack`，一级页传 `null` 并补 `padding(start = 16.dp)`。
- 顶栏高度 56dp，内容区必须消费 `padding.calculateTopPadding()`。

### 6.3 宿主切换

- 点标题区 → `OverlayDialog` 列出两个宿主，当前项打钩。
- 目标宿主为**当前进程所属宿主**时：正常读写（订阅 `StateFlow`，保存走 `update`）。
- 目标宿主为**另一个宿主**时：`SettingsStore.readExternal(host)` 做一次性只读读取；
  - 读到了 → 展示，但所有编辑控件禁用（只读）；
  - 读不到（不同应用数据目录不可互访 / 未安装 / 文件不存在）→ 显示 `host_no_access_hint` 与空态；
- 切换只是查看，**不写对方数据目录、不复制、不迁移**。自动识别本身基于 `packageName`，正常不会错，切换入口是给异常情况的兜底。

### 6.4 内容

沿用现有卡片结构，只换数据源：

1. 注入状态卡（进程内状态：Hook 是否装好、模块版本、配置更新时间、最近心跳时间）；
2. 按下震动卡：模式下拉 → 系统风格下拉 / 时长强度滑杆 / 曲线预览 + 「设置波形」 → 测试；
3. 抬起震动卡：同上；
4. 其他选项：恢复默认、重载输入法；
5. 二级页：波形编辑器、模块设置（颜色模式、导入导出）、调试与日志、帮助、关于。

### 6.5 miuix 约束（不可违反）

- 一个页面一个 `Scaffold`；所有 `Overlay*` 必须在其子树内，否则弹层不出。
- 弹层只用 miuix `OverlayDialog` / `OverlayBottomSheet`，禁止 `androidx.compose.ui.window.Dialog`。
- 设置项用 `ArrowPreference` / `SwitchPreference` / `OverlayDropdownPreference`，不要裸 `Button`。
- 图标用 `MiuixIcons.*`，颜色用 `MiuixTheme.colorScheme.*`，文本用 `MiuixTheme.textStyles.*`。
- 内容底部留白避让导航栏；`enableEdgeToEdge()` 已在 Activity 中调用。

---

## 7. 文案（资源化）

### 7.1 规则

- 全部字符串放 `res/values/strings.xml`（中文）与 `res/values-en/strings.xml`（英文）。
- 列表放 `res/values/arrays.xml` 的 `<string-array>`。
- Compose 用 `stringResource(R.string.xxx)`；带参用 `stringResource(R.string.xxx, arg)`，占位符写 `%1$s` / `%1$d`。
- 源码（`.kt`）中不得出现中文字面量（注释、日志 tag 除外）。
- 命名：`动作_对象`（`action_back`）、`标题_页面`（`title_injection`）、`toast_结果`（`toast_saved`）、`error_原因`（`error_range`）。

### 7.2 必建资源清单

| 资源名 | 中文 | EN |
| --- | --- | --- |
| `app_name` | 震动设置 | Vibration |
| `module_description` | 为微信输入法 / 豆包输入法提供可自定义的双段触感反馈 | Custom two-stage haptic feedback for WeType and Doubao IME |
| `action_back` | 返回 | Back |
| `action_cancel` | 取消 | Cancel |
| `action_confirm` | 确认 | OK |
| `action_save` | 保存 | Save |
| `action_close` | 关闭 | Close |
| `action_switch_host` | 切换宿主 | Switch host |
| `action_reset_default` | 恢复默认配置 | Restore defaults |
| `action_reload_ime` | 重载输入法 | Reload IME |
| `action_export_config` | 导出配置 | Export config |
| `action_import_config` | 导入配置 | Import config |
| `action_export_logs` | 导出日志 | Export logs |
| `action_clear_logs` | 清除日志 | Clear logs |
| `action_save_image` | 保存图片 | Save image |
| `state_loading` | 加载中… | Loading… |
| `state_unknown` | 未知 | Unknown |
| `host_name_wetype` | 微信输入法 | WeType |
| `host_name_doubao` | 豆包输入法 | Doubao IME |
| `host_picker_title` | 选择宿主 | Choose host |
| `host_no_access_hint` | 无法读取该宿主配置（不同应用的数据目录不可互访），当前仅供查看 | Cannot read that host's config (separate app data) |
| `title_injection` | 注入状态 | Injection status |
| `injection_none` | 未观测到注入 | Injection not observed |
| `injection_none_hint` | 打开输入法并切换一次键盘后再回来查看 | Open the IME and switch the keyboard once |
| `injection_hook_missing` | 模块已加载，Hook 未安装 | Module loaded, hook not installed |
| `injection_hook_missing_hint` | 输入法版本可能已变更，可通过日志反馈 | The IME version may have changed; report with logs |
| `injection_ok` | Hook 已注入 | Hook injected |
| `injection_ok_hint` | 配置更新于 %1$s | Config updated %1$s |
| `injection_heartbeat` | 最近心跳 %1$s · 模块 %2$s | Last heartbeat %1$s · module %2$s |
| `title_down_vibration` | 按下震动 | Press vibration |
| `title_up_vibration` | 抬起震动 | Release vibration |
| `title_system_haptic_style` | 系统震动风格 | System haptic style |
| `label_down` | 按下 | Press |
| `label_up` | 抬起 | Release |
| `label_curve_preview` | 曲线预览 | Curve preview |
| `curve_summary` | 强度 0~255 · 时间累计 %1$d ms（曲线为预览，实际按离散数值执行） | Amplitude 0–255 · total %1$d ms (preview only) |
| `action_setup_waveform` | 设置波形 | Edit waveform |
| `waveform_not_set` | 未配置 · 将载入默认四段波形 | Not set · default 4-segment waveform will load |
| `waveform_set` | 已配置 · %1$d 段 | Configured · %1$d segments |
| `duration_label` | %1$s震动时长：%2$d ms | %1$s duration: %2$d ms |
| `strength_label` | %1$s震动强度：%2$d%% | %1$s strength: %2$d%% |
| `action_test` | 测试%1$s震动 | Test %1$s vibration |
| `reset_confirm_message` | 将当前宿主的震动设置与自定义波形恢复为默认值？ | Restore this host's vibration settings and waveform to defaults? |
| `reset_done` | 已恢复默认配置 | Defaults restored |
| `reload_summary` | 通过 Root 快速重启目标输入法进程 | Restart the target IME process via root |
| `reload_ok` | 成功：已重启 %1$s | Done: %1$s restarted |
| `reload_no_root` | 失败：没有 root 权限 | Failed: no root |
| `reload_no_su` | 失败：找不到 su | Failed: su not found |
| `reload_failed` | 失败：%1$s | Failed: %1$s |
| `waveform_save_failed` | 波形保存失败 | Failed to save waveform |
| `waveform_saved` | %1$s波形已保存 | %1$s waveform saved |
| `waveform_invalid` | 波形数据不合法 | Invalid waveform data |
| `waveform_legacy_warn` | 检测到历史波形数据长度或数组不一致，已载入默认波形草稿；保存后将按当前数量规范化。 | Legacy waveform data is inconsistent; a default draft was loaded |
| `read_config_failed` | 读取当前配置失败 | Failed to read config |
| `title_down_waveform` | 按下波形 | Press waveform |
| `title_up_waveform` | 抬起波形 | Release waveform |
| `segment_count_label` | 有效波段数（%1$d~%2$d） | Segments (%1$d–%2$d) |
| `segment_index` | 波段 %1$d | Segment %1$d |
| `segment_first_fixed` | 0（固定） | 0 (fixed) |
| `label_timing_ms` | 时长(ms) | Duration (ms) |
| `label_amplitude` | 强度(0-255) | Amplitude (0–255) |
| `label_timing_range` | 时长(ms，0~%1$d) | Duration (ms, 0–%1$d) |
| `label_timing_fixed_hint` | 时长：0 ms（首段固定，不可编辑） | Duration: 0 ms (first segment is fixed) |
| `label_amplitude_range` | 强度(0~%1$d) | Amplitude (0–%1$d) |
| `waveform_sync_hint` | 调整会立即同步到曲线与下方输入行。 | Changes sync to the curve and rows below |
| `waveform_down_desc` | 手指按下时的自定义波形；曲线为预览，实际按离散数值执行。 | Custom waveform on press; the curve is a preview |
| `waveform_up_desc` | 手指抬起时的自定义波形；曲线为预览，实际按离散数值执行。 | Custom waveform on release; the curve is a preview |
| `waveform_editor_summary` | 强度 0~255 · 时间累计 %1$d ms · 选中：波段 %2$d（时长 %3$dms，强度 %4$d） | Amplitude 0–255 · total %1$d ms · selected: segment %2$d (%3$d ms, %4$d) |
| `waveform_curve_a11y` | 波形曲线预览，点击节点可编辑对应波段；下方输入行同样可编辑 | Waveform preview; tap a node to edit that segment |
| `input_integer_required` | 请输入整数 | Enter an integer |
| `input_range` | 范围 %1$d~%2$d | Range %1$d–%2$d |
| `error_segment_count` | 有效波段数需在 %1$d~%2$d 之间 | Segment count must be %1$d–%2$d |
| `error_segment_incomplete` | 波段数据不完整 | Incomplete segment data |
| `error_first_timing_zero` | 首段时长必须为 0 | First segment duration must be 0 |
| `error_timing_negative` | 波段 %1$d 时长不能为负 | Segment %1$d duration cannot be negative |
| `error_timing_too_long` | 波段 %1$d 时长不能超过 %2$d ms | Segment %1$d duration cannot exceed %2$d ms |
| `error_amplitude_range` | 波段 %1$d 强度需在 0~%2$d 之间 | Segment %1$d amplitude must be 0–%2$d |
| `error_no_positive_timing` | 至少需要一段大于 0 ms 的波段 | At least one segment must exceed 0 ms |
| `title_module_settings` | 模块设置 | Module settings |
| `title_color_mode` | 颜色模式 | Color mode |
| `title_debug_logs` | 调试与日志 | Debug & logs |
| `summary_debug_logs` | 开启后按分类写入宿主配置目录（重启输入法后生效） | Write categorized logs to the host config dir |
| `summary_clear_logs` | 删除当前宿主全部日志 | Delete this host's logs |
| `summary_export_logs` | 打包当前宿主日志为 zip | Zip this host's logs |
| `toast_logs_cleared` | 日志已清除 | Logs cleared |
| `toast_logs_exported` | 日志已导出 | Logs exported |
| `toast_logs_empty` | 没有可导出的日志 | No logs to export |
| `toast_export_failed` | 导出失败：%1$s | Export failed: %1$s |
| `toast_config_exported` | 配置已导出 | Config exported |
| `toast_config_imported` | 配置已导入 | Config imported |
| `toast_read_file_failed` | 读取文件失败 | Failed to read file |
| `error_import_format` | 导入失败：文件格式不正确 | Import failed: bad file format |
| `summary_export_config` | 导出当前宿主设置与波形（JSON） | Export this host's settings and waveform (JSON) |
| `summary_import_config` | 从 JSON 恢复当前宿主设置与波形 | Restore this host's settings from JSON |
| `title_help` | 帮助文档 | Help |
| `summary_help` | 使用说明与波形格式 | Usage and waveform format |
| `help_load_failed` | 加载失败，请检查网络，或到仓库查看 README。 | Failed to load. Check network or read the README |
| `title_about` | 关于作者 | About |
| `title_repo` | 仓库 | Repository |
| `title_coolapk` | 酷安主页 | Coolapk |
| `title_channel` | 频道 | Channel |
| `title_sponsor` | 赞助 | Sponsor |
| `summary_sponsor` | 扫码支持作者，也可以先保存图片再识别 | Scan to support the author, or save the image first |
| `sponsor_qr_a11y` | 赞助二维码 | Sponsor QR code |
| `toast_saved_to_gallery` | 已保存到相册：Pictures/WeDouVibrator | Saved to Pictures/WeDouVibrator |
| `toast_save_failed` | 保存失败 | Save failed |
| `link_unavailable` | 链接待补充 | Link not available |
| `toast_open_link_failed` | 无法打开链接 | Cannot open link |
| `toast_open_settings_failed` | 无法在输入法内打开设置页，请反馈输入法版本 | Cannot open settings inside the IME |

`arrays.xml`：

| 资源名 | 中文项 | EN 项 |
| --- | --- | --- |
| `vibration_modes` | 无震动 / 系统预设风格 / 简单自定义 / 自定义波形 | Off / System preset / Simple custom / Custom waveform |
| `system_haptics` | Virtual Key … Toggle Off（20 项，见现有 `SYSTEM_HAPTICS`） | 同左 |
| `color_modes` | 浅色 / 深色 / 跟随系统 | Light / Dark / Follow system |

---

## 8. 阶段与闸门

> 顺序约定：P1.5（存储收敛）应在 P3 之前完成，避免后续页面改动两次；文案资源化（P4）要求 `.kt` 中不再有中文字面量，因此被取代的旧页面必须在 P2 就删除。

### P0 — 底座切换与宿主内 Activity

**改动**

1. `app/build.gradle.kts`：加 YukiHook 三件套 + KSP + appcompat；设 `--package-id 0x64`；删 `service`/`interface` 依赖。
2. 删除 `app/src/main/resources/META-INF/xposed/` 三个文件。
3. 新增 `HookEntry.kt`（注解 + `encase` + 触摸 Hook）+ `HostCatalog.kt`。
4. 新增 `BaseHostActivity.kt` + `SettingsActivity.kt`（先用 `TextView` 显示 `packageName` / `pid` / `filesDir`）。
5. 新增 `themes.xml` 的 `Theme.WeTypeHookR`、`strings.xml` 的 `app_name` / `module_description`。
6. `HostFabInjector` 的点击改为 `startActivity(Intent(ctx, SettingsActivity::class.java))`。
7. Manifest：加 `android:description`。

**闸门**

- [ ] `assembleRelease` 通过，APK 内 `META-INF/xposed/` 由编译器生成且内容正确（`java_init.list` 指向编译生成的入口类）。
- [ ] 两个宿主上点 FAB 都能起页面，页面上的 `packageName` / `pid` 与宿主一致。
- [ ] 打字仍有震动（触摸 Hook 迁移后行为不变）。
- [ ] 返回键回到输入法设置页，输入法不崩。

建议 commit：`feat(base): 迁移到 YukiHook 底座并在宿主内启动设置页`

### P1 — 配置落到宿主目录（已完成，三文件版）

当前实现：`HostPaths`（`settings.json` / `haptic.json` / `ui.json`）、`SettingsStore`（`Snapshot` + 手写 `atomicWrite` + `FileObserver`）、`SettingsModel`（`JSONObject` 编解码）、`HookEntry` 的 `attach`、FAB 位置落 `ui.json`。

下一节把它们收敛为单快照；收敛完成前不要在此之上继续加字段。

### P1.5 — 存储收敛为单快照（待做）

现状是 `settings.json` / `haptic.json` / `ui.json` 三个文件、各自原子写、跨文件不原子；本节把它们合并为 §5 描述的 `state.json`。

1. `HostPaths`：`settings()/haptic()/ui()` → `state()`，新增 `legacyDir()`。
2. `SettingsModel`：新增 `@Serializable` 的 `HostState` / `SettingsState` / `CustomHapticConfig` / `WaveformHaptic` / `UiState`；`WaveformHaptic` 的两组值改为 `List`；保留波形校验（改为 `isValidWaveform` / `sanitizeWaveform`）；手写 `JSONObject` 编解码只留作旧文件迁移。
3. `SettingsStore`：
   - `Snapshot` → `HostState`，对外暴露 `StateFlow<HostState>`；
   - `saveSettings` / `saveHaptic` / `saveUi` 三个入口合并为 `update(transform)`；
   - 手写 `atomicWrite` 换成 `AtomicFile` + `@Synchronized` 串行化，另加 `updateAsync`（内部单线程池）给主线程调用点；
   - `load()` 增加"旧三文件合并"迁移分支，迁移后移入 `config/legacy/`；
   - 新增 `readExternal(host)`（只读一次性读取，供宿主切换）；
   - 删除 `Snapshot.revision`；`HookRuntime.configRevision` / `SettingsModel.HookStatus.configRevision` 改为 `configUpdatedAt`，注入卡文案改为「配置更新于 %s」。
4. 调用点同步：`SettingsScreen`（`SettingsStore.current` → `state.collectAsState()`，保存走 `update`）、`HostFabInjector`（类型换 `SettingsModel.UiState`，落位走 `updateAsync`）、`ConfigTransfer`（信封格式 `{format, host, state}`，兼容旧导出格式）、`HookEntry`（`SettingsStore.current.ui` → `state.value.ui`）、`HapticEngine`（波形转 `toLongArray()` / `toIntArray()`）。
5. 依赖：加 `org.jetbrains.kotlin.plugin.serialization` 与 `kotlinx-serialization-json`。
6. `schema` 升到 3；旧 `settings.json` 的 `schema = 2` 在迁移时读入即可，不需要额外分支。
7. 删除 `ConfigRepository`（其 `snapshots` / `hookStatus` 职责并入 `SettingsStore` 与 `HookRuntime`）。

**闸门**

- [ ] 旧三文件存在时首次启动能合并成 `state.json`，值不丢，旧文件进 `legacy/`。
- [ ] 手工改 `state.json` 的 `settings` 与 `waveform`，1 秒内生效。
- [ ] 写入过程中 `am force-stop` 宿主，重启后配置完整（无半截文件）。
- [ ] 把 `state.json` 改成非法 JSON：沿用旧缓存，坏文件被改名为 `state.bad.<ts>.json`。
- [ ] 导出/导入 JSON 与 `HostState` 结构一致。

建议 commit：`refactor(config): 三文件合并为单快照 state.json`

### P2 — 设置页 UI（含移除 launcher）（已完成，存储接入待 P1.5）

1. `SettingsScreen.kt` → `SettingsContent(host, modifier, onOpenModuleSettings)`；数据源换 `SettingsStore`；删除同步状态提示；注入卡读进程内状态；删「复制到其他宿主」。
2. 新增 `SettingsTopBar`、宿主选择弹层、非本宿主的只读态。
3. `SettingsActivity` 从探针换成真实页面。
4. **删除** `MainActivity.kt`、`App.kt`、`MainScreen.kt`、`HostSettingsActivity.kt`、`HostGrant.kt`；Manifest 删两个 `<activity>`。
5. 模块级设置（颜色模式 / 导入导出 / 日志 / 帮助 / 关于）此阶段暂缺，P3 补回。

**闸门**

- [ ] 桌面无图标；`resolve-activity` 查不到 launcher。
- [ ] 四种震动模式、波形编辑、测试、恢复默认、重载输入法全部可用且即时生效。
- [ ] 顶栏两行文本正确；点击可切换宿主（只读查看）；不可读时给出提示。
- [ ] 返回后再进入，配置一致、无重复保存。

建议 commit：`feat(ui): 设置页在宿主内复用并移除独立入口`

### P3 — 模块设置与调试页（待做）

1. `ModuleSettingsPage.kt`：颜色模式、配置导入导出（当前宿主）、帮助、关于/赞助。
2. 调试与日志：开关、清除、导出；`DebugLog` 直写文件；`DebugScreen` 接入。
3. 「导入旧配置」入口接 `LegacyConfigImporter`。

**闸门**

- [ ] 颜色模式切换即时生效并持久化。
- [ ] 导出/导入 JSON 正确；日志开关、清除、导出可用。

建议 commit：`feat(ui): 模块设置与调试页并入宿主内设置页`

### P4 — 文案资源化（待做：现仅 11 条资源）

按 §7 建 `strings.xml` / `values-en/strings.xml` / `arrays.xml`，替换全部字面量。

**闸门**

- [ ] `grep -nP '"[^"]*[\x{4e00}-\x{9fff}]' app/src/main/java` 无结果（注释除外）。
- [ ] 系统语言切英文后页面无中文、无截断。

建议 commit：`refactor(i18n): 文案迁移到字符串资源`

### P5 — 清理与回归（待做：`ConfigRepository` 等仍在）

1. 删除 `RemoteService`、`LogService`、`ModulePrefs`、`ConfigRepository`、`ConfigStore`、`LiquidGlassBar` + `liquid/` + `miuixanim/`、`ui/theme/`、`WeTypeHookRApp.kt`。
2. Manifest：去掉 `<service>` 与 `android:name`，只留 `<application>`（label/description）+ `queries`。
3. 依赖瘦身（miuix-blur / navigation / coil 等确认无引用后删除）。
4. 全量回归 + 回写 `DEVELOPMENT_GUIDE.md`。

**闸门**

- [ ] `assembleRelease` 通过。
- [ ] §11 验收清单全绿。

建议 commit：`refactor!: 移除残留通道与依赖，完成宿主内设置页改造`

## 9. 文件清单

> 下表按目标态列出。已完成的文件仅作对照；P1.5 的实际改动面见 §8 P1.5 第 1–5 条。

**新增**

| 文件 | 职责 |
| --- | --- |
| `HookEntry.kt` | `@YukiHookLibXposedEntry` 入口，`encase` / `loadApp` 装配 |
| `HostCatalog.kt` | 宿主包名、显示名、proxy 类名、版本查询 |
| `BaseHostActivity.kt` | `AppCompatActivity` + `ModuleActivity` 委托 |
| `SettingsActivity.kt` | 宿主内设置页宿主容器（edge-to-edge、Compose） |
| `SettingsTopBar.kt` | 标题 + 宿主副标题 + 点击切换 |
| `HostPaths.kt` | 宿主目录路径 |
| `SettingsStore.kt` | 单快照读写（`AtomicFile` + 单锁）/ `StateFlow` / `FileObserver` / `readExternal` 只读读取 / 迁移与损坏恢复 |
| `HookRuntime.kt` | 进程内注入状态 |
| `ModuleSettingsPage.kt` | 模块设置 / 调试 / 帮助 / 关于（可拆多文件） |
| `SettingsModel.kt` | `@Serializable` 单快照模型（`HostState` / `SettingsState` / `CustomHapticConfig` / `WaveformHaptic` / `UiState`），含波形校验 |

**改造**

| 文件 | 改动 |
| --- | --- |
| `app/build.gradle.kts` | 依赖、KSP、资源包 ID |
| `AndroidManifest.xml` | label/description、移除组件（P2 删 Activity，P5 删 Service） |
| `HapticEngine.kt` | 抽出 `dispatch(view, event)` |
| `SettingsScreen.kt` | 数据源与顶栏改造 |
| `WaveformEditor.kt` / `WaveformCurve.kt` | 仅替换文案引用 |
| `HostFabInjector.kt` | 点击改跳转；位置经 `SettingsStore` 写入 `HostState.ui` |
| `DebugLog.kt` | 直写文件 |
| `LogStore.kt` | 路径改宿主目录 |
| `ConfigTransfer.kt` | 单宿主导入导出 |
| `DebugScreen.kt` | 接入二级页 |
| `LegacyConfigImporter.kt` | 接入迁移入口 |
| `proguard-rules.pro` | 确认 `HookEntry`、`SettingsActivity`、`BaseHostActivity` 保留 |

**删除**

`MainActivity.kt`、`App.kt`、`MainScreen.kt`、`HostSettingsActivity.kt`、`HostGrant.kt`、`RemoteService.kt`、`LogService.kt`、`ModulePrefs.kt`、`ConfigRepository.kt`、`ConfigStore.kt`、`LiquidGlassBar.kt`、`liquid/`、`miuixanim/`、`ui/theme/`、`resources/META-INF/xposed/`、`WeTypeHookRApp.kt`。

---

## 10. 禁止事项

1. **不要**手写 `META-INF/xposed/*`（与编译器生成冲突，编译失败）。
2. **不要**把资源包 ID 设为 `0x7f`。
3. **不要**让非 `ModuleActivity` 子类作为设置页（注册机制不接管）。
4. **不要**在宿主进程里用 `createPackageContext(模块包)` 取资源（包可见性会拒），模块资源已由框架注入。
5. **不要**给配置引入第二个权威源（SharedPreferences / 云端 / 任何跨进程通道）；`state.json` 是唯一来源，写入必须经 `SettingsStore.update`（`AtomicFile` + 单锁）。
6. **不要**在 Hook 的触摸回调里做文件 IO、加锁或反射查找。
7. **不要**用 `androidx.compose.ui.window.Dialog` 等自绘弹层替代 miuix `Overlay*`。
8. **不要**开启热重载（设置页存活时会被框架拒绝）。
9. **不要**恢复 launcher Activity。
10. **不要**未经批准合并到 `master`，也不要 `push --force`。
11. 设备操作边界：不擅自重启手机、不改 LSPosed 数据库、不 kill 系统进程；`am force-stop <宿主>` 仅用于让 Hook 重新注入。

---

## 11. 验收清单

**形态**
- [ ] 桌面无图标；Manifest 无 Activity/Service；LSPosed 能识别模块与两个作用域
- [ ] APK 内 `META-INF/xposed/` 由编译器生成，`java_init.list` 指向正确入口

**入口**
- [ ] 两个宿主的设置页有且只有一个 FAB；其它页面没有
- [ ] FAB 打开设置页，`packageName` / `pid` 为宿主；返回后输入法正常

**页面**
- [ ] 顶栏两行（模块名 / 宿主名）；点击弹出宿主选择
- [ ] 切换宿主：只读查看，不可读时给出提示；无跨宿主写入或复制
- [ ] 四种模式、波形编辑、曲线交互、测试、恢复默认、重载输入法均可用
- [ ] 模块设置 / 日志 / 帮助 / 关于可用
- [ ] 深浅色切换正常，英文下无中文残留与文字溢出

**数据**
- [ ] 配置写入 `<host>/files/haostoo/config/`；改文件后 1 秒内生效
- [ ] 两个宿主配置互不影响；旧 `config.xml` 能迁移

**工程**
- [ ] `assembleRelease` 通过；`dev` 分支提交；`master` 未动
- [ ] `DEVELOPMENT_GUIDE.md` 已按实现结果回写

---

## 12. 调试

| 场景 | 方法 |
| --- | --- |
| 构建 | `gradle.bat -p <proj> :app:assembleRelease --console=plain`（仓库无 `gradlew`，用本地 9.5.0 发行版） |
| 看入口是否加载 | `adb logcat -s YukiHook WETYPE_HOOK` |
| 看 Activity 是否被接管 | 在 `SettingsActivity.onCreate` 打 `WETYPE_UI` 日志，含 `packageName` / `pid` |
| 看配置 | `adb shell run-as <host> ls -l files/haostoo/config` 或 `adb shell ls /sdcard/Android/data/<host>/files/haostoo/config` |
| 强制重注入 | `adb shell am force-stop <host>` 后切一次键盘 |
| 验证热更新 | 改 `state.json` 的 `settings.down_strength`，立刻按键 |

日志 tag：`WETYPE_HOOK`（钩子）、`WETYPE_UI`（设置页）、`WETYPE_CFG`（配置读写/迁移）、`WETYPE_FAB`（入口按钮）。

---

## 附录：常用命令

```bash
# 构建
/c/Users/huang/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat \
  -p 'D:/Users/huang/IdeaProjects/WeTypeHookR' :app:assembleRelease --console=plain

# 安装
adb install -r app/build/outputs/apk/release/app-universal-release.apk

# 宿主 Activity 清单（选 proxy 用）
adb shell dumpsys package com.tencent.wetype | sed -n '/Activities:/,/Providers:/p'
adb shell dumpsys package com.bytedance.android.doubaoime | sed -n '/Activities:/,/Providers:/p'

# 看配置快照
adb shell ls /sdcard/Android/data/com.tencent.wetype/files/haostoo/config
adb shell cat /sdcard/Android/data/com.tencent.wetype/files/haostoo/config/state.json
```
