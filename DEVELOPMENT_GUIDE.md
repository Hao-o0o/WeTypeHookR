# WeTypeHookR（WeDouVibrator）开发文档

> 适用版本：`1.3.0 (1300)`　·　对应分支：`dev`
> 基线：LibXposed API 102（`102.0.0`）+ Miuix `0.9.3`
> 本文所有结论均以当前仓库实际代码为准；与 `HANDOFF.md`、`API102_MIGRATION_GUIDE.md` 等历史文档冲突时，**以本文与代码为准**。

---

## 0. 文档定位与阅读方式

| 文档 | 定位 | 时效 |
| --- | --- | --- |
| **本文 `DEVELOPMENT_GUIDE.md`** | 开发总纲：架构、钩子、配置契约、miuix 规范、构建调试 | 与 1.3.0 代码同步 |
| `TASK_HOST_SETTINGS_MIGRATION.md` | **待执行的重构任务书**：设置页迁入宿主进程、配置回归宿主目录、文案集中映射 | 执行后其结论将覆盖本文的 Remote/独立 App 章节 |
| `UI_AND_WAVEFORM_DESIGN_GUIDE.md` | 界面/波形编辑器的设计规格（为什么这么设计） | 已实现，仍有效 |
| `HOST_INJECTED_SETTINGS_ACTIVITY_GUIDE.md` | 宿主内设置入口（FAB + 一次性凭证）的规格 | 已实现，仍有效 |
| `API102_MIGRATION_GUIDE.md` | API 101 → 102 的迁移规格 | 已完成，作历史参考 |
| `NONROOT_FRAMEWORK_SUPPORT_GUIDE.md` | 免 Root（LSPatch/NPatch）适配方案 | **整体作废**，代码已删除相关支持 |
| `HANDOFF.md` | 上一轮会话交接记录 | 部分章节已过时，仅作背景 |

快速上手路径：§1 功能 → §2 结构与依赖 → §4 钩子 → §3 配置契约 → §6 miuix → §7 构建调试。

---

## 1. 模块功能概述

### 1.1 定位

一个基于 **LibXposed API 102** 的 Xposed 模块，为第三方输入法补齐「按下 / 抬起」两段式触感反馈，并把配置界面做成独立的 miuix 风格 App。

| 项 | 值 |
| --- | --- |
| 应用名 / 模块名 | WeDouVibrator |
| 包名 | `com.haostoo.wetypehookr` |
| 目标宿主 | `com.tencent.wetype`（微信输入法）、`com.bytedance.android.doubaoime`（豆包输入法） |
| 框架要求 | LibXposed API 102（LSPosed 1.9.3+），需具备 `PROP_CAP_REMOTE` |
| 系统要求 | Android 13（API 33）及以上 |
| 仓库 | https://github.com/Hao-o0o/WeTypeHookR |

### 1.2 功能清单（与代码一一对应）

**A. 宿主进程内（Hook 侧）**

1. Hook 输入法根 View 的触摸事件，在 `ACTION_DOWN / ACTION_UP / ACTION_POINTER_DOWN / ACTION_POINTER_UP` 触发震动（`MainModule.ImeRootViewHooker`）。
2. 四种震动模式（`RemoteConfig.SettingsState.downIndex/upIndex`）：
   - `0` 无震动
   - `1` 系统预设风格：映射 20 个 `HapticFeedbackConstants`（`HapticUtils.kt`）
   - `2` 简单自定义：时长 10–200ms + 强度 0–100%（`VibrationEffect.createOneShot`）
   - `3` 自定义波形：读取 Remote File 的 `timings/amplitudes`（`VibrationEffect.createWaveform`）
3. 配置热更新：监听 Remote Preferences 变化，直接替换进程内缓存，**不需要重启输入法**。
4. 注入心跳：向 Remote File 写入 `hook_status_<host>.json`（是否装好 Hook、模块版本、框架名、生效 revision、心跳时间）。
5. 宿主设置页 FAB：Hook `Activity.onResume`，命中白名单设置页时注入可拖动圆形按钮，点击打开模块的设置页。
6. 调试日志：经受限绑定 `LogService` 上报到模块私有目录（校验调用者 UID）。

**B. 模块 App 进程（UI 侧）**

1. `MainActivity` → 根壳层三页：**概览 / 宿主 / 设置**（`HorizontalPager` + 液态玻璃底栏；≥600dp 宽屏改 `NavigationRail`）。
2. `host/{packageName}` 二级页：宿主身份卡、注入状态卡、按下震动卡、抬起震动卡、其他选项（恢复默认 / 重载输入法 / 复制到其他宿主）。
3. 图形化波形编辑器：有效波段数 2–8、PCHIP 平滑曲线预览（点击节点弹出底部面板）、逐段时长/强度输入。
4. 模块级设置：颜色模式（浅色/深色/跟随系统）、全局配置 JSON 导入导出、调试开关、日志导出/清除、帮助文档（在线 README）、关于作者/赞助。
5. `HostSettingsActivity`：只接受带一次性凭证（`HostGrant`）的显式启动，供宿主内 FAB 直达当前宿主的设置页。

**C. 配置流通**

- 权威源：框架的 **Remote Preferences**（键值） + **Remote Files**（波形 JSON）。
- 模块私有目录只做**离线快照（cache）**与**待提交队列（outbox）**，不是第二权威源。

### 1.3 需求边界（不要擅自扩大）

- 只支持上面两个宿主，静态 scope 固定两个包（`scope.list`）。
- 不再支持 LSPatch / NPatch 等免 Root 修补（AIDL、IPC 后端、兼容模式开关均已删除）。
- 宿主侧 Remote 为**只读**，因此宿主进程无法写配置，也无法读 outbox；离线修改只在模块 App 内可见（见 §8.3）。
- 弹层一律使用 miuix 的 `Overlay*` 组件，禁止自绘 `Dialog` / 悬浮窗（用户明确要求）。

---

## 2. 项目结构与关键文件

### 2.1 目录树（只列有意义的节点）

```
WeTypeHookR/
├── app/
│   ├── build.gradle.kts              # 模块构建、签名、ABI 分包、依赖
│   ├── proguard-rules.pro            # R8 规则（入口类与 java_init.list 同步）
│   └── src/main/
│       ├── AndroidManifest.xml       # 权限、queries、两个 Activity、LogService
│       ├── assets/icon.png           # 模块图标（框架列表用）
│       ├── java/com/haostoo/wetypehookr/
│       │   ├── MainModule.kt         # ★ Hook 入口（宿主进程）
│       │   ├── HapticEngine.kt       # ★ 无 Xposed 依赖的震动引擎（双进程共用）
│       │   ├── HapticUtils.kt        # 20 种 HapticFeedbackConstants 映射
│       │   ├── RemoteConfig.kt       # ★ 常量 / SettingsState / 波形编解码
│       │   ├── RemoteService.kt      # ★ App 侧 XposedService 管理
│       │   ├── ConfigRepository.kt   # ★ 配置仓库（快照 + outbox）
│       │   ├── ConfigStore.kt        # 本地 cache/outbox 原子写
│       │   ├── ConfigTransfer.kt     # 全局 JSON 导入导出
│       │   ├── ModulePrefs.kt        # 模块级偏好（颜色模式）
│       │   ├── MainActivity.kt       # launcher Activity
│       │   ├── HostSettingsActivity.kt # 宿主注入入口 Activity
│       │   ├── App.kt                # NavHost（main / host/{pkg}）
│       │   ├── MainScreen.kt         # 概览/宿主/设置三页
│       │   ├── SettingsScreen.kt     # 宿主详情页（按下/抬起卡）
│       │   ├── WaveformEditor.kt     # 波形编辑器 + WaveDraft
│       │   ├── WaveformCurve.kt      # PCHIP 曲线绘制与节点命中
│       │   ├── LiquidGlassBar.kt     # 液态玻璃底栏
│       │   ├── liquid/               # CombinedBackdrop / InnerShadow / Lens / Vibrancy
│       │   ├── miuixanim/            # DampedDragAnimation / InteractiveHighlight / 手势
│       │   ├── HostFabInjector.kt    # 宿主设置页 FAB 注入
│       │   ├── HostGrant.kt          # PendingIntent 一次性启动凭证
│       │   ├── HostInfo.kt           # 宿主版本/安装状态查询
│       │   ├── DebugLog.kt           # 宿主侧日志客户端
│       │   ├── LogService.kt         # 日志接收 Service（UID 校验）
│       │   ├── LogStore.kt           # 日志查看/清理/打包
│       │   ├── DebugScreen.kt        # ⚠ 未接入（旧调试弹层）
│       │   ├── LegacyConfigImporter.kt # ⚠ 未接入（旧 config.xml 解析）
│       │   └── ui/theme/             # ⚠ Material3 模板残留（未被引用）
│       └── resources/META-INF/xposed/
│           ├── module.prop           # API 版本 / 静态 scope / 热重载开关
│           ├── java_init.list        # 模块入口类名（唯一）
│           └── scope.list            # 静态作用域：两个宿主包
├── SCOPE / SUMMARY / SOURCE_URL      # LSPosed 仓库列表用的元数据（非构建输入）
├── build.gradle.kts                  # 插件版本（AGP 9.3.2 / Compose 插件 2.4.10）
├── settings.gradle.kts               # 仓库源（google / mavenCentral / jitpack）
├── gradle.properties                 # ⚠ 已被 .gitignore 忽略（含签名与 AndroidX 开关）
├── release.jks                       # ⚠ 已被忽略的签名文件
└── gradle/wrapper/gradle-wrapper.properties  # Gradle 9.5.0（仓库无 gradlew）
```

### 2.2 构建与依赖

**工具链**

| 项 | 值 |
| --- | --- |
| AGP | `9.3.2` |
| Kotlin / Compose 编译器插件 | `2.4.10` |
| Gradle | `9.5.0`（仅 `gradle-wrapper.properties`，仓库**不含** `gradlew`） |
| JDK | 21（`compileOptions` + `java.toolchain`） |
| compileSdk / targetSdk / minSdk | `37` / `35` / `33` |
| versionCode / versionName | `1300` / `1.3.0` |
| ABI | `armeabi-v7a`、`arm64-v8a`，并生成 universal APK |

**依赖（`app/build.gradle.kts`）**

```kotlin
compileOnly("io.github.libxposed:api:102.0.0")        // ★ 不打进 APK，仅编译期
implementation("io.github.libxposed:service:102.0.0") // App 侧 XposedService
implementation("io.github.libxposed:interface:102.0.0")

implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")   // 决定 minSdk 33

implementation(platform("androidx.compose:compose-bom:2026.04.01"))
implementation("androidx.compose.material3:material3")             // 仅少量基础能力
implementation("androidx.navigation:navigation-compose:2.9.8")
implementation("androidx.navigationevent:navigationevent-compose:1.1.0")
implementation("io.coil-kt:coil-compose:2.6.0")
implementation("com.github.jeziellago:compose-markdown:0.5.4")     // 帮助文档渲染
```

> **`compileOnly` 是本项目最容易踩的坑**：`io.github.libxposed.api.XposedModule` 不会进入 APK，App 进程里任何对 `MainModule` 的静态引用都会 `NoClassDefFoundError`。详见 §4.9。

**签名**：`signingConfigs.release` 从 `gradle.properties` 读 `MY_KEYSTORE_FILE / MY_KEYSTORE_PASSWORD / MY_KEY_ALIAS / MY_KEY_PASSWORD`，指向根目录 `release.jks`。

**混淆**：`release` 开启 `isMinifyEnabled` + `isShrinkResources`，但 `proguard-rules.pro` 里 `-dontobfuscate`、`-dontoptimize`，并保留：

```proguard
-adaptresourcefilecontents META-INF/xposed/java_init.list   # 资源里的类名随 R8 重写同步
-keep class com.haostoo.wetypehookr.** { *; }
-keepclassmembers class com.haostoo.wetypehookr.MainModule { public <init>(); }
```

### 2.3 模块元数据三件套

| 文件 | 当前内容 | 说明 |
| --- | --- | --- |
| `META-INF/xposed/module.prop` | `minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`、`autoHotReload=false` | API 102 只认这四个字段；模块名/版本取自 APK |
| `META-INF/xposed/java_init.list` | `com.haostoo.wetypehookr.MainModule` | 唯一入口类，必须有无参构造 |
| `META-INF/xposed/scope.list` | `com.tencent.wetype`、`com.bytedance.android.doubaoime` | 静态作用域 |

注意：根目录的 `SCOPE`（只有 `["com.tencent.wetype"]`）是 LSPosed **模块仓库展示**用的旧元数据，与运行期 `scope.list` 不一致，改作用域时以 `scope.list` 为准，`SCOPE` 可择机同步。

`AndroidManifest.xml` 中的 `<queries>` 必须包含两个宿主包，否则 `HostInfo.versionText()` 永远返回 null（版本显示“未知”）。

### 2.4 进程模型与数据流

```
┌──────────────── 模块 App 进程 com.haostoo.wetypehookr ────────────────┐
│ MainActivity ──► App() ──► MainScreen（概览/宿主/设置）                │
│                        └─► HostSettingsScreen（host/{pkg}）           │
│ WeTypeHookRApp.onCreate ──► RemoteService.init()                      │
│                               │ XposedServiceHelper.registerListener   │
│                               ▼                                        │
│                          XposedService ──── getRemotePreferences(group)│
│                               │              openRemoteFile(name) 读写 │
│                               ▼                                        │
│ ConfigRepository（快照 + 缓存 + outbox）◄── ConfigStore(filesDir)      │
│ LogService（Messenger，UID 校验）──► LogStore(filesDir/haostoo/logs)   │
└──────────────────────────────────────────────────────────────────────┘
        ▲ Remote Preferences（宿主只读 / App 可写）
        │ Remote Files（同上）
┌───────┴──────── 宿主进程 com.tencent.wetype / com.bytedance... ───────┐
│ MainModule : XposedModule（java_init.list 加载）                       │
│   onPackageReady ──► 装 Hook ──► 读 Remote 配置进内存缓存              │
│                        │            ▲ prefs change listener 热更新     │
│                        ▼            │                                  │
│              ImeRootViewHooker ──► HapticEngine（纯 Android，无 Xposed）│
│   Activity.onResume hook ──► HostFabInjector（白名单设置页注入 FAB）    │
│   DebugLog ──► bindService(LogService) ──► 模块私有目录日志            │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.5 关键文件职责表

**Hook 侧（会被注入宿主进程）**

| 文件 | 职责 | 要点 |
| --- | --- | --- |
| `MainModule.kt` | 入口、Hook 安装、配置缓存装配、心跳 | 只做装配，业务逻辑下沉 |
| `HapticEngine.kt` | 震动执行 + 进程内缓存 + `su` 重载宿主 | **不得引用任何 `io.github.libxposed.*` 类型** |
| `HapticUtils.kt` | `HAPTIC_CONSTANTS` 列表与下标映射 | 按 SDK 版本降级 |
| `DebugLog.kt` | 日志上报客户端 | 200 条有界队列、断线重绑 |
| `HostFabInjector.kt` | FAB 注入、拖动、贴边、位置记忆 | 纯 Canvas 绘制，不依赖宿主主题 |
| `HostGrant.kt` | 一次性启动凭证 | `PendingIntent.creatorUid` + 60s 过期 |

**App 侧（配置与 UI）**

| 文件 | 职责 | 要点 |
| --- | --- | --- |
| `RemoteService.kt` | `XposedService` 生命周期与读写封装 | 只走标准 API，无第三方 SDK |
| `ConfigRepository.kt` | 单一配置入口 | `StateFlow<Map<pkg, ConfigSnapshot>>` |
| `ConfigStore.kt` | cache/outbox 原子写 | 临时文件 → `fsync` → `rename` |
| `RemoteConfig.kt` | 全部常量、键名、文件名、编解码 | 禁止在别处拼接 group/file 名 |
| `MainScreen.kt` | 根壳层三页 + 模块设置 | 底栏必须在 `Scaffold` 内容区内 |
| `SettingsScreen.kt` | 宿主详情页 | 防抖保存 250ms，页面组合单一 `Scaffold` |
| `WaveformEditor.kt` | `WaveDraft` + 逐段编辑 + 节点面板 | 校验规则集中在 `validate()` |
| `WaveformCurve.kt` | PCHIP 采样与绘制 | 曲线仅预览，不参与执行 |

**当前未接入的代码（改动时注意）**

| 文件 | 状态 |
| --- | --- |
| `DebugScreen.kt` | `DebugDialog` 无调用方；调试入口已在 `MainScreen` 的「设置」页用 `SwitchPreference` 实现 |
| `LegacyConfigImporter.kt` | 无调用方；README 提到的「导入旧 config.xml」目前**没有 UI 入口** |
| `ui/theme/*.kt` | Material3 模板残留（`WeTypeHookRTheme` 未被引用），实际主题走 `MiuixTheme` |

---

## 3. 配置与数据契约

### 3.1 通道清单（`RemoteConfig.kt` 是唯一来源）

| 类型 | 名称 | 宿主 |
| --- | --- | --- |
| Remote Preferences group | `wetype_settings` | 微信输入法 |
| Remote Preferences group | `doubao_settings` | 豆包输入法 |
| Remote File | `customhaptic_wetype.json` / `customhaptic_doubao.json` | 波形 |
| Remote File | `hook_status_wetype.json` / `hook_status_doubao.json` | 注入心跳（Host 写、App 读） |

### 3.2 Preferences 键

```
down_mode  up_mode            # 模式下标 0..3
down_system  up_system        # 系统预设风格下标 0..19
down_duration  down_strength  # ms / 0..1
up_duration  up_strength
theme_mode                    # 0 浅色 1 深色 2 跟随系统（历史字段，当前 UI 用 ModulePrefs 的模块级值）
debug_log                     # 调试日志开关
customhaptic_revision         # 波形文件版本号
settings_revision             # 设置提交版本号
schema_version                # 当前 1
updated_at                    # 提交时间戳
```

**revision 的语义**：`max(settings_revision, customhaptic_revision, updated_at)` 即一次逻辑提交的版本。App 与 Hook 两侧都用这个函数计算，比较时必须用同一套规则。

### 3.3 写入顺序（关键约定）

```
1. 写波形文件：openRemoteFile → Os.ftruncate(fd,0) → 写入 → flush → close
2. 再提交 prefs：customhaptic_revision = now, updated_at = now（commit）
```

**先文件后 revision**。Hook 侧只在 revision 变化时重读文件（`appliedWaveRevision` 缓存），顺序反了会读到半截内容或旧内容。

### 3.4 `SettingsState`

```kotlin
data class SettingsState(
    val downIndex: Int, val upIndex: Int,
    val downDuration: Float, val downStrength: Float,
    val upDuration: Float, val upStrength: Float,
    var downSystemIndex: Int, var upSystemIndex: Int,
    var themeMode: Int = 2,
    var debugLog: Boolean = false,
)
```

默认值：按下/抬起都取 `1`（系统预设），`down.system = 0`（Virtual Key），`up.system = 3`（Keyboard Press），时长 50ms，强度 0.5。

### 3.5 波形格式与合规规则

```json
{"down":{"timings":[0,40,10,10],"amplitudes":[0,250,0,100]},
 "up":  {"timings":[0,40,10,10],"amplitudes":[0,180,0,90]}}
```

- 段数 2–8；`timings.length == amplitudes.length`
- `timings[0]` 必须为 `0`（编辑器灰显只读）
- 时长 0–500ms，强度 0–255
- 至少一段时长 > 0
- 畸形 JSON → `parseCustomHaptic` 返回 `null` → **保留上一个有效缓存**，不写坏数据

`WaveDraft` 用「完整缓冲 + 可见数量」模型：缩小波段数保留隐藏值，再放大可恢复；超出缓冲时按当前曲线 PCHIP 线性插值补非零初值。

### 3.6 `ConfigRepository` 状态机

| `SyncState` | 含义 | UI 提示 |
| --- | --- | --- |
| `SYNCED` | 已与 Remote 一致 | 无 |
| `PENDING_SYNC` | 有本地修改待写回 | 「已保存，等待框架同步」 |
| `OFFLINE_CACHE` | 框架不可用，展示最后快照 | 「框架未连接…」 |
| `DEFAULT` | 从未有过配置 | 同上 |

- Remote 可用：直接读写 Remote，并刷新本地 cache。
- Remote 不可用：修改进 `outbox`（带 `baseRevision`），恢复后按 revision 校验补写；`baseRevision != 远端 revision` 时标记 `conflict = true`，保留本地版本并提示，**当前没有选择 UI**。
- `flushing` 标志用于忽略自己触发的偏好回调，避免刷新风暴。

### 3.7 本地文件布局

```
/files/haostoo/config/<host>/cache.json     # 最后确认生效的配置（离线展示）
/files/haostoo/config/<host>/outbox.json    # 待提交修改 + base_revision
/files/haostoo/logs/<host>/<category>.log   # 日志（hardware / vibrate / ui）
```

写入一律「临时文件 → `fd.sync()` → `rename`」，避免读到半写入内容。

---

## 4. Xposed 入口与钩子实现要点

### 4.1 加载链路

```
框架读取 APK 内 META-INF/xposed/java_init.list
  → 反射实例化 com.haostoo.wetypehookr.MainModule（无参构造）
  → 按 module.prop 校验 minApiVersion / targetApiVersion = 102
  → 按 scope.list（staticScope=true）在目标包进程注入
  → 回调 onModuleLoaded → onPackageLoaded → onPackageReady
```

本项目**只实现 `onPackageReady`**，其余回调使用默认实现（API 102 下默认实现即空操作）。`onModuleLoaded` 若将来需要用，注意 `processName` 在 `ModuleLoadedParam` 上，`packageName` 在 `PackageLoadedParam` 上。

### 4.2 `onPackageReady` 的四步顺序（不要改）

```kotlin
override fun onPackageReady(param: PackageReadyParam) {
    if (param.packageName != TARGET_PKG && param.packageName != DOUBAO_PKG) return

    val remoteAvailable = runCatching {
        (getFrameworkProperties() and XposedInterface.PROP_CAP_REMOTE) != 0L
    }.getOrDefault(false)

    hookInstalled = installHooks(param, param.packageName == DOUBAO_PKG)   // 1) 先装 Hook
    // 2) 再把配置灌进内存缓存（Remote 不可用则走默认值）
    // 3) remoteAvailable 时装 FAB Hook
    // 4) reportStatus(force = true)
}
```

为什么先装 Hook：Hook 回调只读内存缓存，**绝不能在输入事件路径上等待 IPC 或文件 IO**。配置晚到几十毫秒只是短暂使用默认值，反过来则会造成按键卡顿。

### 4.3 目标 Hook 点

| 宿主 | 类 | 方法 | 说明 |
| --- | --- | --- | --- |
| 微信输入法 | `com.tencent.wetype.plugin.hld.view.ImeRootView` | `dispatchTouchEvent(MotionEvent)` | 键盘根 View，稳定 |
| 豆包输入法 | `com.bytedance.android.input.keyboard.KeyboardView` | `onTouchEvent(MotionEvent)` | 键盘 View 自身 |

`collectClassLoaders(param.classLoader)` 会沿 `parent` 链逐个尝试 `loadClass`，命中即 `break`——应对宿主把类放进独立 ClassLoader 的情况。找不到时只 `Log.w`，不影响宿主。

### 4.4 Hooker 实现约束

```kotlin
class ImeRootViewHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val view = chain.thisObject as? View ?: return chain.proceed()
        val event = chain.args.getOrNull(0) as? MotionEvent ?: return chain.proceed()
        val config = HapticEngine.cachedSettings          // 只读快照
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> { /* 按下 */ }
            MotionEvent.ACTION_UP,   MotionEvent.ACTION_POINTER_UP   -> { /* 抬起 */ }
        }
        return chain.proceed()                            // 永不拦截原逻辑
    }
}
```

约定：

- **永远 `chain.proceed()`**，不改参数、不吞事件；震动是纯副作用。
- 取参数用 `chain.args.getOrNull(0) as? MotionEvent`，类型不符就放行（宿主混淆/版本变化时安全降级）。
- 不在 Hooker 里做 IO、加锁、反射查找；所有重活放在 `onPackageReady` 或事件之外。
- 多指按下/抬起都触发，符合键盘多指输入直觉。

### 4.5 配置热更新链路

```
Remote prefs 变化
  → OnSharedPreferenceChangeListener（MainModule.prefsListener，强引用持有）
  → applyConfig(packageName, prefs)
      ├─ HapticEngine.cachedSettings = RemoteConfig.readSettings(prefs)   // 每次都读
      ├─ customhaptic_revision 变化时才读波形文件（appliedWaveRevision 去重）
      └─ reportStatus(force = false)                                       // 5s 节流
```

- 监听器必须用字段强引用（框架底层可能弱引用），否则被 GC 后热更新静默失效。
- `appliedRevision` 取三个 revision 的最大值，只用于心跳与展示。

### 4.6 注入心跳

`reportStatus` 写 `hook_status_<host>.json`：

```json
{"hook_installed":true,"module_version":"1.3.0","framework":"LSPosed",
 "config_revision":1730000000000,"heartbeat_at":1730000000000}
```

- 只在 `remoteAvailable` 时写；失败仅 `Log.w`。
- 非强制上报有 `STATUS_MIN_INTERVAL_MS = 5000` 节流，避免连续拖动滑杆时反复写文件。

### 4.7 FAB 注入

```kotlin
val onResume = Activity::class.java.getDeclaredMethod("onResume")
hook(onResume).intercept { chain ->
    val result = chain.proceed()                 // 先让宿主完成 onResume
    val activity = chain.thisObject as? Activity
    if (activity != null && HostFabInjector.isTargetSettingsActivity(pkg, activity.javaClass.name))
        HostFabInjector.attach(activity, pkg, getRemotePreferences(group))
    result
}
```

- 白名单：微信 `...reactnative.activity.ImeMainSettingActivity`，豆包 `...activity.SettingsActivityNext`。**不要全局 Hook 所有 Activity**。
- 用 `decorView.findViewWithTag("haostoo_host_fab_v1")` 去重，Activity 重建不会叠加。
- 拖动与点击用 touchSlop（8dp）区分；松手吸附最近侧边（180ms `ValueAnimator`），位置按宿主归一化存 `fab_x/fab_y`。
- 点击走 `HostGrant.buildLaunchIntent()`，异常时 Toast 提示（避免静默失败）。

### 4.8 一次性启动凭证（`HostGrant`）

| 环节 | 做法 |
| --- | --- |
| 创建（宿主进程） | `PendingIntent.getActivity(ctx, code, innerIntent, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)` |
| 传递 | `Intent.putExtra(EXTRA_GRANT, pendingIntent)` + `EXTRA_TS`（毫秒时间戳） |
| 校验（模块 Activity） | `grant.creatorUid` → `PackageManager.getPackagesForUid` → 必须是受支持宿主；且 `now - ts <= 60_000` |

普通 App 无法伪造 `creatorUid`，因此不需要额外 AIDL 授权服务。校验失败即 Toast + `finish()`。

### 4.9 分层红线：`HapticEngine` 不得引用 Xposed 类型

`io.github.libxposed:api` 是 `compileOnly`，App 进程没有 `XposedModule` 类。历史 bug：设置页点「测试震动」直接崩 `NoClassDefFoundError`，根因是 App 侧代码路径触达了 `MainModule`。

规则：

1. App 与 Hook 共用逻辑只放 `HapticEngine`（普通 `object`，只用 Android API）。
2. App 侧禁止 `import` / 引用 `MainModule`（包括 `MainModule.Companion` 常量）。常量放 `RemoteConfig`。
3. 新增共享能力时先问：这段代码在**没有 libxposed api 的进程**里能跑吗？

### 4.10 日志通道（跨进程）

```
宿主进程 DebugLog.log(ctx, category, msg)
  → 若 messenger 为空：入队（上限 200）并 bindService(LogService.bindIntent())
  → Messenger.send(MSG_LOG, Bundle{category, message})
模块进程 LogService.handleLog(msg)
  → Binder.getCallingUid() → getPackagesForUid → 必须是受支持宿主
  → category ∈ {hardware, vibrate, ui}；消息截断 8192 字符
  → 单线程 Executor 追加写 /files/haostoo/logs/<host>/<category>.log
```

- **UID 校验必须在同步的 `handleMessage` 内完成**（`Binder.getCallingUid()` 在异步线程会失效）。
- 日志永远不能影响键盘：绑定失败 → 丢最旧一条并返回；发送异常 → 清空 messenger 下次重绑。
- `DebugLog.isEnabled()` 直接读 `HapticEngine.cachedSettings.debugLog`，开关变化随配置热更新即时生效。

---

## 5. LibXposed API 102 能力与开发事项

> 依据 `io.github.libxposed:api / service / interface : 102.0.0` 的实际接口（`javap` 校验）与官方 Javadoc。

### 5.1 依赖分工

| 依赖 | 作用域 | 用途 |
| --- | --- | --- |
| `io.github.libxposed:api` | **compileOnly** | `XposedModule`、`XposedInterface`、`XposedModuleInterface`（只在宿主进程存在） |
| `io.github.libxposed:service` | implementation | `XposedService`、`XposedServiceHelper`（App 进程连接框架） |
| `io.github.libxposed:interface` | implementation | 公共接口常量（service 的传递依赖，显式声明便于版本对齐） |

### 5.2 生命周期回调（`XposedModuleInterface`）

| 回调 | 触发时机 | 本项目 |
| --- | --- | --- |
| `onModuleLoaded(ModuleLoadedParam)` | 模块代被载入进程；热重载**不会**重放 | 未使用 |
| `onPackageLoaded(PackageLoadedParam)` | 包已加载、`AppComponentFactory` 尚未实例化 | 未使用 |
| `onPackageReady(PackageReadyParam)` | 类加载器就绪、即将创建 `Application` | **主入口** |
| `onSystemServerStarting(...)` | system_server 启动 | 不涉及（无 `PROP_CAP_SYSTEM` 需求） |
| `onHotReloading(HotReloadingParam): Boolean` | 热重载前（旧代码执行），返回 false 可拒绝 | 未实现（`autoHotReload=false`） |
| `onHotReloaded(HotReloadedParam)` | 热重载后（新代码执行），默认实现会卸掉全部旧 Hook | 未实现 |

`PackageReadyParam` 提供 `getClassLoader()` 与 `getAppComponentFactory()`；`packageName` / `applicationInfo` 继承自 `PackageLoadedParam`。

### 5.3 Hook API

```kotlin
// HookBuilder
hook(executable: Executable): HookBuilder
hookClassInitializer(clazz: Class<*>): HookBuilder

// HookBuilder 可链式配置
setPriority(int)                       // PRIORITY_HIGHEST / DEFAULT / LOWEST
setExceptionMode(ExceptionMode)        // 回调抛异常时的处理策略
setId(String)                          // API 102：同 id 可原子替换
intercept(Hooker): HookHandle

// HookHandle
getExecutable() / getId() / unhook() / replaceHook(Hooker)
```

```kotlin
// Chain（传给 Hooker.intercept）
getExecutable(): Executable
getThisObject(): Any?
getArgs(): List<Any?>
getArg(index: Int): Any?
proceed(): Any?
proceed(args: Array<Any?>): Any?
proceedWith(result: Any?): Any?
proceedWith(result: Any?, args: Array<Any?>): Any?
```

要点：

- 本项目一律 `hook(executable).intercept(hooker)`，不设 priority / id / exceptionMode（默认即可）。
- 想在升级模块后替换 Hook 而不重启进程时，用 `setId()` + `replaceHook()`，配合 `onHotReloaded`（目前未启用）。
- inline 导致回调不触发时，才考虑 `deoptimize(executable)`；不要默认全量去优化。
- `getInvoker` 用于绕过访问检查调用原方法，本项目未使用。

### 5.4 Remote 能力（模块 ↔ 框架）

| 侧 | API | 权限 |
| --- | --- | --- |
| Hook 侧 | `getRemotePreferences(group)` | **只读** |
| Hook 侧 | `openRemoteFile(name)` | **只读**（`FileNotFoundException` 表示未配置） |
| Hook 侧 | `listRemoteFiles()` | 列出模块共享目录 |
| App 侧 | `XposedService.getRemotePreferences(group)` | 读写 |
| App 侧 | `XposedService.openRemoteFile(name)` | 可写（配合 `Os.ftruncate`） |
| App 侧 | `XposedService.deleteRemotePreferences(group)` / `deleteRemoteFile(name)` | 清理 |

限制与坑：

- 文件名必须是**单层名**，不能含路径分隔符、`.`、`..`。所以波形/状态文件都带宿主后缀而不是子目录。
- 框架为 **embedded**（如某些免 Root 方案）时 Remote 直接抛 `UnsupportedOperationException`；本项目用 `PROP_CAP_REMOTE` 提前判定，绝不静默回退到私有 `SharedPreferences`。
- 能力检测要 `runCatching`：不同实现可能在 `getFrameworkProperties()` 之外抛出异常。

### 5.5 `XposedService` 可用方法（App 侧）

```java
int getApiVersion(); String getFrameworkName(); String getFrameworkVersion();
long getFrameworkVersionCode(); long getFrameworkProperties();
List<String> getScope();
void requestScope(List<String>, OnScopeEventListener); void removeScope(List<String>);
List<HookedTarget> getRunningTargets();
void hotReloadModule(HookedTarget, Bundle, HotReloadCallback);
SharedPreferences getRemotePreferences(String); void deleteRemotePreferences(String);
String[] listRemoteFiles(); ParcelFileDescriptor openRemoteFile(String); boolean deleteRemoteFile(String);
```

本项目只用了 `getFrameworkName/Version/Properties`、`getRemotePreferences`、`openRemoteFile`。`getScope/requestScope`（动态作用域）、`getRunningTargets` + `hotReloadModule`（改完模块立即生效）是后续可考虑的能力，但都会改变产品行为，需先确认。

`XposedServiceHelper.registerListener(object : OnServiceListener { onServiceBind / onServiceDied })` 在 `Application.onCreate` 注册一次，`onServiceDied` 里必须把引用置空（`RemoteService.service` 是 `StateFlow`，UI 靠它驱动状态卡）。

### 5.6 框架属性常量

| 常量 | 含义 |
| --- | --- |
| `PROP_CAP_REMOTE` | 支持 Remote Preferences/Files（**本项目硬依赖**） |
| `PROP_CAP_SYSTEM` | 可 Hook system_server 等系统进程 |
| `PROP_RT_API_PROTECTION` | 禁止通过反射/动态加载访问 Xposed API（`PROP_RT_` 前缀可能每次启动变化） |

### 5.7 API 102 行为变化（务必记住）

- **102 起禁止调用 `de.robv.android.xposed` 遗留 API**（`XposedBridge`、`XposedHelpers`、`XC_MethodHook`、`XSharedPreferences`）。本项目已无任何引用，新增代码也不许引入。
- 101 起模块不再注入 zygote，只在 scope 内进程加载。
- 102 新增热重载、`HookHandle.replaceHook`、`setId` 原子替换、模块入口可停止接收后续回调。

### 5.8 热重载：当前为什么关着

`module.prop` 里 `autoHotReload=false`。要打开必须补齐：

1. `onHotReloading` 返回 true 前：停掉模块自有线程、注销外部回调（`prefsListener`！）、清空对宿主对象的引用（`HostFabInjector` 注入的 View、`HapticEngine` 缓存）。
2. `onHotReloaded` 里：用 `HookHandle.replaceHook` 重建两条触摸 Hook 与 FAB Hook，重读配置。
3. 否则旧代的 listener/View 残留会导致重复震动、内存泄漏或崩溃。

**在没做完上述清理前不要打开**，仅仅是“宣称支持 API 102”没有意义。

### 5.9 打包核查清单（发版前）

- [ ] APK 内 `META-INF/xposed/java_init.list` 指向 `com.haostoo.wetypehookr.MainModule`
- [ ] `module.prop`：`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`
- [ ] `scope.list` 含两个宿主包
- [ ] `MainModule` 无参构造未被 R8 移除（`-keepclassmembers ... public <init>();`）
- [ ] 代码中无 `de.robv.android.xposed` / `XSharedPreferences` / YukiHook 残留
- [ ] Manifest 的 `<queries>` 与 `scope.list` 一致
- [ ] 安装后 LSPosed 能识别模块、作用域显示两个宿主

---

## 6. Miuix 界面规范与用法

> 依据 Miuix 官方中文文档（`compose-miuix-ui.github.io/miuix/zh_CN`）+ 本项目实际写法。库处于实验阶段，升级版本前先看 changelog。

### 6.1 模块与依赖

| 模块 | 本项目用途 |
| --- | --- |
| `miuix-ui` | 基础组件：`Scaffold`、`Card`、`TopAppBar`、`Text`、`Icon`、`Slider`、`TextField`、`NavigationBar/Rail` |
| `miuix-preference` | `ArrowPreference`、`SwitchPreference`、`OverlayDropdownPreference` |
| `miuix-icons` | `MiuixIcons.*`（含 `Light/Normal/Regular/Medium/Demibold` 五种粗细） |
| `miuix-blur` | 液态玻璃底栏的 `blur` / `lens` / `backdrop`，**硬要求 minSdk 33** |

`miuix-squircle`、`miuix-shader` 由 `miuix-ui` / `miuix-blur` 传递引入，`miuix-nav` 未使用（导航用 `navigation-compose`）。

### 6.2 主题

官方推荐 `ThemeController(ColorSchemeMode.System/Light/Dark/Monet*)` + `MiuixTheme(controller = ...)`；本项目因为颜色模式是**模块级自定义值**（`ModulePrefs.colorMode`，与宿主配置解耦），采用直接传配色方案：

```kotlin
val dark = ModulePrefs.isDark(context, isSystemInDarkTheme())
MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) { /* 页面 */ }
```

要点：

- **每个页面各自包一层 `MiuixTheme`**（`MainScreen`、`HostSettingsScreen` 各一处），`App.kt` 的 `NavHost` 外层没有主题——这样切换颜色模式能立即整页重组，且宿主详情页与根壳层互不影响。
- 不要用 `material3` 的 `MaterialTheme` 参与实际配色（`ui/theme/` 是模板残留）。
- 颜色只取 `MiuixTheme.colorScheme.*`：本项目用到 `surface`、`surfaceContainer`、`primary`、`onSurface`、`onSurfaceContainerVariant`、`secondaryContainer`、`dividerLine`。
- 文本样式取 `MiuixTheme.textStyles.*`（`body1/body2/subtitle/title*`），局部字号可直接给 `fontSize`（本项目多数卡片标题用 `15–22.sp` 直写，属既有风格）。

### 6.3 `Scaffold` 与弹层层级（最容易出错）

官方说明：`OverlayDialog`、`OverlayDropdownPreference`、`OverlayBottomSheet` 等**都依赖 `Scaffold` 提供的 `MiuixPopupHost`，必须在 `Scaffold` 内使用**；`Scaffold` 的 `popupHost` 在布局最后放置（最高 z 序）。

本项目的实践结论：

1. **根壳层的自定义底栏必须放在 `Scaffold` 的 content 内**（`Box` 里 `align(BottomCenter)`），不能放进 `bottomBar` 槽位——否则底栏会盖住所有弹层。
2. 弹层不要靠"移除组合"关闭：`OverlayDialog(show = ...)` 要常驻组合树，由 `show` 驱动进出场动画，否则丢失关闭动画（见 `AboutDialog`/`SponsorDialog`）。
3. 一个页面只用一个 `Scaffold`。波形编辑器不再新建 `Scaffold`，而是作为同一 `Scaffold` 内的 `AnimatedContent` 目标，否则二级 `OverlayBottomSheet` 会跑到另一个 popup host 里。
4. 多 `Scaffold` 并存（如 `Scaffold` 内再嵌）时官方称各自独立管理弹层，但本项目仍按"单 Scaffold + 内容区切换"实现，行为最可控。

### 6.4 页面骨架

**根壳层（`MainScreen`）**

```kotlin
Scaffold(topBar = { SmallTopAppBar(title = ...) }) { padding ->
    Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            HorizontalPager(state = pagerState) { page -> /* 概览 / 宿主 / 设置 */ }
        }
        FloatingBottomBar(                       // 必须在内容区
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 12.dp + navBarBottomPadding),
            ...
        ) { /* FloatingBottomBarItem × 3 */ }
    }
}
```

- 内容底部留白 `76.dp + navigationBars.bottom`，与底栏高度（64dp）+ 外边距对齐。
- 宽屏（`screenWidthDp >= 600`）改 `Row { NavigationRail + 内容 }`，底栏隐藏。
- `BackHandler(enabled = currentPage != 0) { goPage(0) }` 让返回键先回首页。

**二级页（`HostSettingsScreen`）**

```kotlin
Scaffold(
    topBar = {
        TopAppBar(
            title = ...,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回") } },
            scrollBehavior = scrollBehavior,          // MiuixScrollBehavior()
        )
    },
) { padding ->
    Column(Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) { /* 卡片 */ }
}
```

- 二级页**不带根底栏**，只靠顶栏返回。
- `MiuixScrollBehavior` + `nestedScroll` 才有顶栏折叠/回弹效果，别漏。
- 页面内二级状态（波形编辑）用 `AnimatedContent` 横向滑入/滑出（300ms + 220ms 淡入），标题与返回按钮语义随之变化。

### 6.5 组件使用约定

| 场景 | 用 | 不要用 |
| --- | --- | --- |
| 可点设置项 | `ArrowPreference(title, summary, startAction, onClick)` | `Button` / 自绘 Row（用户明确要求） |
| 开关 | `SwitchPreference(title, summary, checked, onCheckedChange)` | `Switch` + 自绘行 |
| 单选/下拉 | `OverlayDropdownPreference(items, selectedIndex, onSelectedIndexChange)` | 自建 `DropdownMenu` |
| 确认弹窗 | `OverlayDialog(title, summary, show, onDismissRequest)` | `androidx.compose.ui.window.Dialog` |
| 底部调整面板 | `OverlayBottomSheet(show, title, onDismissRequest)` | `ModalBottomSheet` |
| 卡片容器 | `Card(insideMargin = PaddingValues(0.dp)) { preference... }` | `Surface` + 圆角手写 |
| 数字输入 | `TextField(state = TextFieldState, label = ...)`（State-based） | `OutlinedTextField` |
| 图标 | `Icon(MiuixIcons.X, contentDescription)` | 手写 `painterResource`（除赞助图） |

细节：

- `Card` 的 `insideMargin` 默认 `PaddingValues(0.dp)`，承载 preference 时保持 0 让分隔线贴边；纯文本卡片再在内容里 `padding(16.dp)`。
- `startAction` 里放图标时统一 `Modifier.padding(end = 12.dp)` + `tint = MiuixTheme.colorScheme.primary`。
- `OverlayDropdownPreference` 的 `selectedIndex` 必须 `coerceIn` 到 items 范围，否则越界崩溃。
- `TextField` 用受控 `TextFieldState` 时，回写要防抖自身触发（`WaveformEditor.ControlledNumberField` 用 `lastEmitted` + `snapshotFlow` 处理）。
- 弹层按钮用 `TextButton`（`ButtonDefaults.textButtonColorsPrimary()` 表示主操作），一行两个时 `Modifier.weight(1f)` + 中间 `Spacer(20.dp)`。

### 6.6 液态玻璃底栏

组成：`LiquidGlassBar.kt` + `liquid/`（`CombinedBackdrop`、`InnerShadow`、`Lens`、`Vibrancy`）+ `miuixanim/`（`DampedDragAnimation`、`InteractiveHighlight`、`inspectDragGestures`），改编自 miuix 示例 `IosLiquidGlassNavigationBar`（Apache 2.0）。

实现要点：

1. 背景采样：根内容用 `rememberLayerBackdrop { drawRect(surfaceColor); drawContent() }` 记录，底栏用 `Modifier.drawBackdrop(backdrop, shape, effects, highlight, layerBlock)` 取像。
2. 效果链：`vibrancy()`（饱和度 1.5）+ `blur(4.dp)` + `lens(refractionHeight, refractionAmount)`；`lens` 内部会检查 `isRuntimeShaderSupported()`，低版本自动降级。
3. 选中胶囊：`DampedDragAnimation` 驱动位移/按压缩放，`InteractiveHighlight` 提供按压高光（AGSL RuntimeShader）。
4. 拖拽跨页：`onDrag` 换算到 `tabWidthPx`，松手吸附到最近 tab 并回调 `onSelected`；拖动结束时的回调用 `pagerState.isScrollInProgress` 过滤中间页，避免来回重定向。
5. `isBlurEnabled = false` 时退化为纯色胶囊（无 blur 依赖，可作低端机降级开关）。

### 6.7 波形曲线绘制

- `buildCurveGeometry()`：x = 累计时长（总时长为 0 时退化为等距），y = `1 - amp/255`，留白左上右下 `14/10/14/18 dp`。
- `pchipSample()`：单调三次 Hermite，保证不过冲、不越界；零长区间直接连线避免除零。
- 绘制：`Canvas` + `drawPath(Stroke(3.2.dp, Round, Round))`，节点 `drawCircle`，选中节点加 11dp 光晕。
- 交互：`detectTapGestures` 取最近节点，命中半径 22dp。
- 语义：`semantics { contentDescription = "波形曲线预览，点击节点可编辑对应波段…" }`。
- **曲线只作预览**，实际震动始终用原始离散 `timings/amplitudes`。

### 6.8 UI 自检清单

- [ ] 新页面有一个 `Scaffold`，所有 `Overlay*` 都在它子树内
- [ ] 没有自绘 `Dialog`/`Popup`；没有裸 `Button` 充当设置项
- [ ] 深色模式下所有文本用 `MiuixTheme.colorScheme.onSurface*`，无硬编码黑色
- [ ] 底部内容留白避开底栏与导航栏（`76.dp + navigationBars`）
- [ ] 二级页可返回（顶栏 + 系统返回键）
- [ ] 长列表用 `verticalScroll`，卡片间距 12dp、水平内边距 12dp
- [ ] 文本在 320dp 宽度下不截断/不重叠

---

## 7. 开发与调试流程

### 7.1 分支约定

| 分支 | 用途 | 规则 |
| --- | --- | --- |
| `master` | 发布分支 | **只在用户明确批准后**合并指定版本；不得直接提交功能 |
| `dev` | 日常开发 | 所有开发、文档、重构提交到 `dev` |

```bash
git status                 # 确认当前在 dev
git checkout dev           # 切分支
git add -A -- app src ...  # 提交（勿提交 .dsh_* / build/ 等临时产物）
git commit -m "feat: ..."
```

合并到 `master` 的流程：用户确认版本 → `git checkout master` → `git merge --no-ff dev` → 打 tag → 用户确认后再 `git push`。

### 7.2 构建

仓库**没有 `gradlew`**，使用本地已下载的 Gradle 9.5.0：

```powershell
& 'C:\Users\huang\.gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat' `
  -p 'D:\Users\huang\IdeaProjects\WeTypeHookR' :app:assembleRelease --console=plain
```

```bash
# Git Bash 等价写法
/c/Users/huang/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat \
  -p 'D:/Users/huang/IdeaProjects/WeTypeHookR' :app:assembleRelease --console=plain
```

产物（`app/build/outputs/apk/release/`）：

| 文件 | 说明 |
| --- | --- |
| `app-universal-release.apk` | 通用包（推荐安装） |
| `app-arm64-v8a-release.apk` | 64 位 |
| `app-armeabi-v7a-release.apk` | 32 位 |

**构建环境注意**

- `gradle.properties` 被 `.gitignore` 忽略，其中既有 `android.useAndroidX=true`、`android.nonTransitiveRClass=true`、JVM 参数，也有签名四要素与 `android.disableCompileSdkVersionCheck=true` / `android.suppressUnsupportedCompileSdk=37`。**新克隆仓库必须自己补这份文件**，否则构建直接失败。
- 沙盒/受限环境下 Gradle 可能因加载 `native-platform.dll` 失败，需要在允许访问 `~/.gradle` 的环境构建。

### 7.3 安装与真机验证

```bash
adb devices
adb install -r app/build/outputs/apk/release/app-universal-release.apk
```

验证顺序：

1. LSPosed 中启用模块 → 勾选两个宿主作用域 → 强制停止输入法使其重新注入。
2. 打开模块 App 概览页：状态卡应为「模块运行中」，摘要形如「已连接 LSPosed · 框架 Service（Root/Manager）」。
3. 进入宿主详情页：注入状态卡应显示「Hook 已注入」+ 最近心跳时间。
4. 输入法打字验证按下/抬起震动；改任意参数应即时生效（无需重启输入法，仅更新模块本体后才需要重启输入法）。
5. 宿主设置页应出现圆形 FAB，可拖动、贴边、位置记忆、点击进入对应宿主设置。

### 7.4 日志定位

**框架日志（logcat）**

| Tag | 来源 | 内容 |
| --- | --- | --- |
| `WETYPE_HOOK` | `MainModule` | Hook 安装结果、配置更新、心跳失败 |
| `WETYPE_REMOTE` | `RemoteService` | Service 绑定/断开、Remote 文件读写失败 |
| `WETYPE_CONFIG_REPO` | `ConfigRepository` | Remote 提交失败、outbox 排队 |
| `WETYPE_FAB` | `HostFabInjector` | 启动设置页失败 |
| `WETYPE_LOG_SERVICE` | `LogService` | 拒绝未授权 UID、非法 category、写盘失败 |

```bash
adb logcat -s WETYPE_HOOK WETYPE_REMOTE WETYPE_CONFIG_REPO WETYPE_FAB WETYPE_LOG_SERVICE
```

**模块内日志（持久化）**

- 开启路径：模块 App → 设置 → 「调试输出」（会写所有宿主的 `debug_log`）。
- 落盘：`/data/data/com.haostoo.wetypehookr/files/haostoo/logs/<host>/<category>.log`
- 内容：首次上报时先写一次硬件/系统信息（`hardware`），之后为 `vibrate` 事件（模式、系统风格下标、时长、强度）。
- 导出：设置 → 导出日志（`exportAllToZip`）或宿主详情（当前 UI 的导出在设置页）。

### 7.5 故障排查表

| 现象 | 优先检查 |
| --- | --- |
| 状态卡红「未连接到 Xposed 框架」 | LSPosed 是否启用模块、模块是否被系统冻结；`RemoteService` 是否收到 `onServiceBind` |
| 状态卡「框架不支持 Remote」 | 框架属性缺少 `PROP_CAP_REMOTE`；此时配置不可写，Hook 走默认值 |
| 打字无震动，卡片显示「未观测到注入」 | 宿主是否被重新注入（改 scope 后要 force-stop）；`installHooks` 是否 `no hook target found`（宿主版本改动导致类名变化） |
| 有震动但改设置不生效 | 观察 `config updated: key=...` 日志；检查 App 侧 `saveSettings` 是否走了 outbox（Remote 提交失败） |
| 点「测试震动」崩溃 | 检查是否引入了 App 侧对 `MainModule` 的引用（`NoClassDefFoundError`） |
| 弹层被底栏挡住 | 底栏是否被放进了 `Scaffold.bottomBar` 或 `Scaffold` 之外 |
| 二级弹层（BottomSheet）不显示 | 是否新建了第二个 `Scaffold`（popup host 不匹配） |
| 宿主版本显示「未知」 | `AndroidManifest.xml` 的 `<queries>` 是否包含该包 |
| FAB 点击无反应 | `HostGrant` 校验失败（超过 60s / creatorUid 非宿主）→ 看 `WETYPE_FAB` 日志与 Toast |

### 7.6 新增一个宿主（改动清单）

1. `RemoteConfig`：加 `XXX_PACKAGE`、`SUPPORTED_PACKAGES`、`displayName`、`groupFor`、波形/状态文件名。
2. `MainModule`：`onPackageReady` 的包名判断 + `installHooks` 里新增目标类/方法分支。
3. `HostFabInjector.targetActivityFor`：加设置主 Activity 全名。
4. `AndroidManifest.xml`：`<queries>` 加包名。
5. `scope.list`：加包名（同步根目录 `SCOPE` 展示用元数据）。
6. UI 会自动列出（`SUPPORTED_PACKAGES` 驱动），无需改页面；`SYSTEM_HAPTICS`/`MODE_NAMES` 通用。
7. 真机验证：Hook 命中日志、FAB 注入、版本查询、波形读写。

---

## 8. 注意事项与已知问题

### 8.1 编码红线

1. **分层**：`HapticEngine` / `RemoteConfig` / `Host*` 不得引用 `io.github.libxposed.api.*`；`MainModule` 不得承载 UI 逻辑。
2. **常量集中**：group 名、文件名、键名只在 `RemoteConfig` 定义，禁止字符串拼接散落各处。
3. **写入顺序**：波形文件写完再提交 revision。
4. **事件路径零阻塞**：Hooker 内不做 IO / IPC / 同步等待。
5. **日志不得影响宿主**：所有日志路径 `runCatching`，失败只降级。
6. **权限校验用 UID**：`LogService` 与 `HostGrant` 都靠 `Binder.getCallingUid()` / `PendingIntent.creatorUid`，不接受消息体自报的包名。
7. **不要动用户设备上的其他东西**：未经许可不重启手机、不改 LSPosed 数据库、不 `kill` 系统进程（详见团队约定）；本项目唯一的“外部动作”是用户主动点的「重载输入法」（`su -c am force-stop`）。

### 8.2 版本与打包

- 改版本号：`app/build.gradle.kts` 的 `versionCode` / `versionName`，以及 README 更新日志。
- `module.prop` **不写**模块名/版本（API 102 由 APK 提供），不要照搬旧教程往里加字段。
- 发版前跑一遍 §5.9 清单。

### 8.3 已知问题

| 编号 | 问题 | 影响 / 现状 |
| --- | --- | --- |
| 1 | 无 AIDL，Hook 看不到 outbox | 框架离线期间的修改，要等重连补写后才作用到输入法 |
| 2 | `conflict` 只有标记 | 冲突时保留本地版本，没有“保留远端/合并”的选择 UI |
| 3 | FAB 外观固定 | 纯 Canvas 蓝色圆形，不跟随宿主深浅色 |
| 4 | 微信输入法 3.5.3+ 的「简单自定义 / 自定义波形」震感偏弱 | 系统层限制，README 建议微信侧用「系统预设」 |
| 5 | `DebugScreen.kt` / `LegacyConfigImporter.kt` 未接入 | README 里的「导入旧 config.xml」暂无 UI 入口；要接就在设置页加 `ArrowPreference` + SAF |
| 6 | `ui/theme/` 是 Material3 模板残留 | 未被引用，可清理（先确认无人 import） |
| 7 | `SCOPE` 与 `scope.list` 不一致 | 仅影响仓库展示，运行期以 `scope.list` 为准 |
| 8 | `gradle.properties` 未入库 | 新环境需手动补，否则构建失败 |
| 9 | 工作区残留 `.dsh_*`、`build/reports` | 临时产物，不属于项目，勿提交 |

### 8.4 临时产物（不要提交）

```
.dsh_*.png  .dsh_*.txt  .dsh_session.jsonl     # 上一轮会话调试产物
build/reports/                                  # 根目录 Gradle 诊断产物
```

`.gitignore` 已忽略 `/app/build/`、`/.gradle/`、`.idea`、`.kotlin/`、`gradle.properties`、`release.jks`、`*.hprof`。

---

## 附录 A：常用命令速查

```bash
# 构建
/c/Users/huang/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat \
  -p 'D:/Users/huang/IdeaProjects/WeTypeHookR' :app:assembleRelease --console=plain

# 安装
adb install -r app/build/outputs/apk/release/app-universal-release.apk

# 只看本模块日志
adb logcat -s WETYPE_HOOK WETYPE_REMOTE WETYPE_CONFIG_REPO WETYPE_FAB WETYPE_LOG_SERVICE

# 重启宿主（设备已 root；也可用模块内「重载输入法」）
adb shell su -c "am force-stop com.tencent.wetype"

# 查看模块私有日志（需 root 或 run-as）
adb shell run-as com.haostoo.wetypehookr ls files/haostoo/logs
```

## 附录 B：参考链接

- LibXposed API 102 Javadoc：https://libxposed.github.io/api/io/github/libxposed/api/XposedModule.html
- LibXposed API 仓库：https://github.com/libxposed/api
- LibXposed Service 仓库：https://github.com/libxposed/service
- LSPosed 现代 Xposed API 指南：https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API
- Miuix 中文文档：https://compose-miuix-ui.github.io/miuix/zh_CN/
- Miuix 组件索引：https://compose-miuix-ui.github.io/miuix/zh_CN/components/
- Android `VibrationEffect.createWaveform`：https://developer.android.com/reference/android/os/VibrationEffect#createWaveform(long[],int[],int)
