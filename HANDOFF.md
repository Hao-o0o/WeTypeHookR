# WeDouVibrator（WeTypeHookR）交接文档

> 生成时间：2026-10-18 21:50（Asia/Shanghai）
> 用途：上一轮会话上下文已满，本文档用于让新会话无缝接管。读完本文 + 三份设计指南即可继续开发，不必回溯历史对话。

---

## 0. 最新架构变更（2026-10-18 重构，覆盖后文对应章节）

本轮按 `HOST_INJECTED_SETTINGS_ACTIVITY_GUIDE.md` 重构，并移除全部免 Root 适配：

| 变更 | 现状 |
| --- | --- |
| 免 Root（LSPatch / NPatch / IPC） | **已全部删除**：AIDL、IntegratedConfigService/Client、LocalConfigStore、NPatch SDK 依赖与 Manifest queries 均已移除；模块只支持 LSPosed 标准 Remote |
| 配置来源 | `ConfigRepository` 单一入口：Remote Preferences/Files 为唯一权威；`ConfigStore` 负责本地 cache + outbox（离线待提交，重连后按 baseRevision 补写） |
| 数据契约 | `ConfigSnapshot(host, settings, waveform, revision, state, conflict)`，`SyncState = SYNCED / PENDING_SYNC / OFFLINE_CACHE / DEFAULT`；Remote prefs 新增 `schema_version / settings_revision / updated_at` |
| 宿主设置页 | `HostSettingsScreen` 不再被 `service == null` 挡住；顶部新增宿主身份卡（包名 / 版本）与注入状态卡 |
| 宿主入口 | `HostFabInjector` 在 `ImeMainSettingActivity`（微信）/ `SettingsActivityNext`（豆包）注入可拖动 FAB；点击经 `HostGrant`（PendingIntent creatorUid 校验）打开 `HostSettingsActivity` |
| 注入心跳 | Hook 写 Remote 文件 `hook_status_<host>.json`（module_version / hook_installed / framework / config_revision / heartbeat_at） |
| 已知限制 | 没有 AIDL 时 Hook 看不到 outbox，离线修改要等框架重连后才作用到输入法 |

§6「后端与连接优先级」中的兼容模式 / IPC 描述已作废；其余章节（Hook、波形、UI 规范）仍然有效。

---

## 1. 项目是什么

一个 **LSPosed / LSPatch / NPatch 模块**，给微信输入法、豆包输入法加上可自定义的触感反馈（震动）。

| 项 | 值 |
| --- | --- |
| 应用名 | WeDouVibrator |
| 包名 | `com.haostoo.wetypehookr` |
| 版本 | versionName `1.2.2` / versionCode `1225`（**尚未为本次改动升级版本号**） |
| minSdk / targetSdk / compileSdk | 33 / 35 / 37 |
| 构建 | AGP 9.3.2 + Kotlin 2.4.10 + Gradle 9.5.0 + JDK 21 |
| 框架 | LibXposed API 102（`minApiVersion=102`, `targetApiVersion=102`, `staticScope=true`, `autoHotReload=false`） |
| 目标宿主 | `com.tencent.wetype`（微信输入法）、`com.bytedance.android.doubaoime`（豆包输入法） |

### 功能
- Hook 输入法触摸事件，在 `ACTION_DOWN / UP / POINTER_DOWN / POINTER_UP` 触发震动。
- 四种模式：`无震动` / `系统预设风格`（20 种 HapticFeedbackConstants）/ `简单自定义`（时长+强度）/ `自定义波形`（曲线编辑器）。
- 配置按宿主隔离，修改**自动保存**（无保存按钮）。
- 支持 Root（标准 LibXposed Remote）、免 Root（LSPatch / NPatch）两种运行方式。

---

## 2. 必读的设计文档（仓库根目录）

| 文件 | 作用 |
| --- | --- |
| `API102_MIGRATION_GUIDE.md` | API 102 迁移规格：Remote Preferences/Files、去掉 XSharedPreferences、设置移出宿主进程 |
| `UI_AND_WAVEFORM_DESIGN_GUIDE.md` | UI 与波形编辑器规格（管理器壳层、曲线、合规校验），**已按 v2 实现** |
| `NONROOT_FRAMEWORK_SUPPORT_GUIDE.md` | 免 Root 框架支持规格（LSPatch v1.2 / NPatch Canary、ConfigBackend 抽象、IPC 服务） |

---

## 3. 架构总览

```
┌───────────────────── 模块 App 进程 (com.haostoo.wetypehookr) ─────────────────────┐
│ MainActivity → App (NavHost: "main" | "host/{packageName}")                        │
│   ├─ MainScreen        概览 / 宿主 / 设置  三页 HorizontalPager + 液态玻璃底栏        │
│   │                    宽屏(≥600dp) 用 NavigationRail                              │
│   ├─ HostSettingsScreen 宿主详情（按下/抬起震动、曲线、其他选项、日志）              │
│   ├─ WaveformEditor     曲线 + 有效波段数 + 逐段输入                                │
│   ├─ RemoteService      XposedServiceHelper / NPatch SDK 连接                      │
│   ├─ ConfigRepository   按后端路由读写（REMOTE / IPC）                              │
│   ├─ IntegratedConfigService  AIDL 只读快照服务（UID 校验）                         │
│   └─ LogService         Messenger 日志接收（UID 校验）                              │
└───────────────────────────────────────────────────────────────────────────────────┘
             ▲ AIDL / Remote Preferences / Messenger
┌────────────┴──────── 宿主进程 (wetype / doubao，模块被框架注入) ────────────────────┐
│ MainModule : XposedModule（入口，java_init.list）                                  │
│   ├─ onPackageReady: 判断 Remote 可用性 → 选 REMOTE 或 IPC → 装配 HapticEngine      │
│   ├─ ImeRootViewHooker: hook 输入法触摸 → HapticEngine.triggerHapticPreview         │
│   ├─ IntegratedConfigClient: IPC 模式下订阅快照并写入 HapticEngine 缓存             │
│   └─ DebugLog: 经 LogService 上报日志                                              │
└───────────────────────────────────────────────────────────────────────────────────┘
```

### 关键分层原则（务必遵守）

**`MainModule` 继承 `io.github.libxposed.api.XposedModule`，而 `io.github.libxposed:api` 是 `compileOnly`，不会打进 APK。**
因此 **App 进程里任何对 `MainModule` 的引用都会抛 `NoClassDefFoundError: io.github.libxposed.api.XposedModule`**（这正是早期"点测试震动就崩溃"的根因）。

所有 App 与 Hook 共用的逻辑都放在 **`HapticEngine`（普通 object，不依赖任何 Xposed 类型）**：
- `HapticEngine.cachedSettings` / `cachedCustomHaptic`
- `HapticEngine.triggerHapticPreview(context, view, modeIndex, systemIndex, duration, strength, isDown, waveform)`
- `HapticEngine.killHost(context, packageName, onResult)`（`su -c am force-stop`，**免 Root 下必然失败**）

---

## 4. 源码清单（`app/src/main/java/com/haostoo/wetypehookr/`）

### 2026-10-18 重构后的文件变化（本节表格其余条目为历史快照）

- **新增**：`ConfigStore.kt`（本地 cache + outbox，原子写）、`HostGrant.kt`（PendingIntent 启动凭证）、`HostSettingsActivity.kt`（宿主直达设置页）、`HostFabInjector.kt`（设置页 FAB 注入与拖动吸附）、`HostInfo.kt`（宿主版本查询）
- **删除**：`IntegratedConfigService.kt`、`IntegratedConfigClient.kt`、`LocalConfigStore.kt`、`Backend.kt`、`app/src/main/aidl/`、`app/libs/npatch-remote-api*.aar`
- **改造**：`ConfigRepository.kt`（唯一配置入口 + ConfigSnapshot/StateFlow）、`MainModule.kt`（Remote 快照 + 心跳 + FAB Hook）、`SettingsScreen.kt`（快照驱动 + 宿主/注入卡）、`ConfigTransfer.kt`（走仓库）

### Hook 侧
| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `MainModule.kt` | 174 | 入口；`onPackageReady` 选后端并装配；两条 IME 触摸 hook |
| `HapticEngine.kt` | 103 | 共享震动引擎 + 缓存 + 杀进程 |
| `HapticUtils.kt` | 68 | 20 种 HapticFeedbackConstants 映射 |
| `DebugLog.kt` | 146 | 宿主侧日志客户端（绑 `LogService`，失败重连 + 有界队列） |
| `IntegratedConfigClient.kt` | 99 | IPC 客户端：绑定服务、订阅 revision、写回 HapticEngine 缓存 |
| `LocalConfigStore.kt` | 143 | IPC 配置文件 `<filesDir>/haostoo/local/<host>/{settings,waveform}.json + revision` |

### 模块 App 侧（UI）
| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `MainActivity.kt` | 22 | 入口 Activity（`enableEdgeToEdge`） |
| `App.kt` | 55 | `App()`：MiuixTheme + NavHost（`main` / `host/{packageName}`） |
| `MainScreen.kt` | 881 | 概览/宿主/设置三页、状态卡、信息卡、设置页全部逻辑、赞助/确认弹层 |
| `SettingsScreen.kt` | 525 | `HostSettingsScreen`：宿主详情 |
| `WaveformEditor.kt` | 447 | 波形编辑器（WaveDraft、逐段输入、节点编辑 Sheet） |
| `WaveformCurve.kt` | 212 | PCHIP 曲线绘制 + 节点命中 |
| `LiquidGlassBar.kt` | 461 | 液态玻璃底栏（移植自 LiquidVivo，源自 miuix `IosLiquidGlassNavigationBar`） |
| `liquid/` | 434 | `CombinedBackdrop` / `InnerShadow` / `Lens` / `Vibrancy` |
| `miuixanim/` | 340 | `DampedDragAnimation` / `InteractiveHighlight` / `DragGestureInspector` |
| `DebugScreen.kt` | 95 | 日志开关 / 导出 / 清除弹层 |
| `LegacyConfigImporter.kt` | 67 | 旧版 `config.xml` 解析 |
| `ModulePrefs.kt` | 43 | 模块级偏好（颜色模式、后端选择） |

### 配置与能力
| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `RemoteConfig.kt` | 185 | 常量、`SettingsState`、Remote prefs 映射、波形解析/序列化、默认波形 |
| `RemoteService.kt` | 150 | 标准 `XposedServiceHelper` + NPatch Remote SDK；`channel()` 返回连接方式 |
| `ConfigRepository.kt` | 160 | 按后端路由读写；`switchBackend` 迁移数据 |
| `ConfigTransfer.kt` | 95 | 全局配置导出/导入（JSON，含所有宿主） |
| `Backend.kt` | 33 | `BackendType`（REMOTE/IPC）、`BackendState`、`BackendDiagnostics` |
| `IntegratedConfigService.kt` | 100 | AIDL 服务端（`Binder.getCallingUid()` → 包名 → 必须是受支持宿主） |
| `LogService.kt` | 104 | Messenger 日志服务（同样 UID 校验 + 类别白名单 + 长度截断） |
| `LogStore.kt` | 81 | 模块私有日志目录的查看/清理/打包 |

### AIDL
`app/src/main/aidl/com/haostoo/wetypehookr/IConfigService.aidl`（`getSnapshot(host, protocol)`、`registerListener`、`unregisterListener`）
`IConfigListener.aidl`（`onConfigChanged(host, revision)`）

### 已删除（不要恢复）
`SettingsActivityInjector.kt`（宿主页面注入）、`Logger.kt`、`assets/xposed_init`、`META-INF/yukihookapi_init`。

---

## 5. 配置数据模型

```kotlin
data class SettingsState(
    val downIndex: Int, val upIndex: Int,              // 0无震动 1系统预设 2简单自定义 3自定义波形
    val downDuration: Float, val downStrength: Float,  // ms / 0..1
    val upDuration: Float, val upStrength: Float,
    val downSystemIndex: Int, val upSystemIndex: Int,  // 系统预设风格下标
    val themeMode: Int = 2,                            // 0浅色 1深色 2跟随系统
    val debugLog: Boolean = false,
)
```
- Remote prefs 键：`down_mode/up_mode/down_system/up_system/down_duration/down_strength/up_duration/up_strength/theme_mode/debug_log/customhaptic_revision/backend`
- 分组：`wetype_settings` / `doubao_settings`；波形文件：`customhaptic_wetype.json` / `customhaptic_doubao.json`
- **默认波形（取自用户真机）**：
  ```json
  {"down":{"timings":[0,40,10,10],"amplitudes":[0,250,0,100]},
   "up":  {"timings":[0,40,10,10],"amplitudes":[0,180,0,90]}}
  ```

### 波形合规规则（Android 文档）
- 段数 2..8；`timings` 与 `amplitudes` 等长
- `timings[0]` 必须为 `0`（编辑器中灰显只读）
- 时长 0..500ms、强度 0..255
- 至少一段时长 > 0
- 畸形 JSON 不覆盖上一个有效缓存

---

## 6. 后端与连接优先级

`RemoteService.init(context)`：
1. **标准 LibXposed Service**（`XposedServiceHelper`）→ Root / LSPatch Manager
2. 失败则 **NPatch Remote SDK**（`NPatchRemoteClient.connectAsync`，需 `<queries><provider android:authorities="top.nkbe.npatch.remote"/></queries>`）
3. 都不可用 → 状态卡变红

`channel()` 显示文案：`框架 Service（Root/Manager）` / `免 Root · NPatch Remote SDK` / `未连接`

Hook 侧选择后端：
```kotlin
val remoteAvailable = (getFrameworkProperties() and XposedInterface.PROP_CAP_REMOTE) != 0L
val useIpc = !remoteAvailable || prefs.getString("backend") == "ipc"
```
- `REMOTE`：Remote Preferences + Remote Files（只读）
- `IPC`：`LocalConfigStore` 写文件 + `IntegratedConfigService` 提供只读快照

**UI 上只有一个开关「兼容模式」**（在设置页「调试与日志」卡片里）：
- 关闭 = REMOTE；开启 = IPC
- 摘要：`免Root框架无法正常使用时开启`
- 关闭时若 `!RemoteService.isConnected()` → 弹 miuix `OverlayDialog` 确认

---

## 7. UI 结构与设计约束

### 根壳层（`MainScreen.kt`）
- `HorizontalPager` 三页：**概览 / 宿主 / 设置**
- 底栏：`FloatingBottomBar`（液态玻璃，`rememberLayerBackdrop` 采样 + blur/lens/innerShadow）
- 宽屏（`LocalConfiguration.screenWidthDp >= 600`）改用 `NavigationRail`
- 二级页（宿主详情）是独立路由：返回顶栏、**不显示底栏**

### 层级陷阱（重要）
miuix 的 `OverlayDialog` 渲染到 **Scaffold 的 popupHost**，而 popupHost 在 Scaffold 布局最后才 `place`（最高 z）。
→ **底栏必须在 Scaffold 的内容区里**（`layerBackdrop` 之后、同一 Box 内 `align(BottomCenter)`），否则它会盖住所有弹层。
→ `LocalPopupStates` / `LocalDialogStates` 是 `internal`，App 侧无法自己提供，弹层必须写在某个 Scaffold 的子树中。

**所有弹层都用 miuix `OverlayDialog`，不要用 `androidx.compose.ui.window.Dialog` 自绘悬浮窗**（用户明确要求）。

### 设置页（模块级）
颜色模式 → 导出/导入配置（全局 JSON）→ 「调试与日志」卡（兼容模式开关、调试输出、导出日志、清除日志）→ 帮助文档 → 关于作者（仓库/酷安/频道/赞助）

### 宿主详情页
顶部信息卡 → `按下震动` 卡（模式下拉；模式 1 显示风格下拉、模式 2 显示时长/强度滑杆、模式 3 显示曲线预览 +「设置波形」）→ `抬起震动` 卡（同构）→ 外观主题 → 其他选项（恢复默认 / 重载输入法 / 复制到其他宿主）→ 日志

### 视觉规范
- 严格用 miuix 0.9.3 组件 + `MiuixIcons`（`TopAppBar`/`Card`/`ArrowPreference`/`SwitchPreference`/`OverlayDropdownPreference`/`OverlayDialog`/`OverlayBottomSheet`/`TextField`/`Slider`/`NavigationBar`）
- 图标用 `top.yukonga.miuix.kmp.icon.extended.*`
- 液态玻璃底栏移植自 LiquidVivo `FloatingBottomBar.kt`（Apache 2.0，源自 miuix 示例 `IosLiquidGlassNavigationBar`）

---

## 8. 构建与验证

仓库**没有 `gradlew`**，用已下载的发行版：

```powershell
& 'C:\Users\huang\.gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat' `
  -p 'D:\Users\huang\IdeaProjects\WeTypeHookR' :app:assembleRelease --console=plain
```

- 产物：`app/build/outputs/apk/release/app-universal-release.apk`（另含 `app-arm64-v8a-release.apk`、`app-armeabi-v7a-release.apk`）
- 安装：`adb -s 10AE5H0GZH000TN install -r <apk>`
- 测试机：vivo V2359A / Android 16 (API 36) / LSPosed 2.2.1
- **构建需要 `danger-full-access`**：`workspace-write` 沙盒下 Gradle 无法加载 `native-platform.dll`（缓存目录在工作区外）

### 用户偏好（务必遵守）
- **不要截图验证**："后续都不需要截图验证"（m01871 / USER#64）
- 需要安装到设备时会明确说"adb已连接，安装新版"
- 有一次要求"构建后先不要安装"（m01628），之后又恢复安装

---

## 9. 关键决策与理由

| 决策 | 理由 |
| --- | --- |
| 设置页从宿主注入改为模块自身 launcher Activity | API 102 要求；且注入方式在免 Root 下不可用 |
| 全部改 Remote Preferences/Files | API 102 标准；禁用 XSharedPreferences |
| `HapticEngine` 独立 object | `XposedModule` 是 compileOnly，App 进程引用 `MainModule` 必崩 |
| minSdk 33 | `miuix-blur` 要求 API 33 的 `RuntimeShader` |
| 升级到 AGP 9.3.2 / Kotlin 2.4.10 / Gradle 9.5.0 / miuix 0.9.3 | LiquidVivo 的玻璃底栏依赖 miuix 0.9.3 + miuix-blur，需要配套工具链 |
| 未采用 `Kyant0/AndroidLiquidGlass`（`io.github.kyant0:backdrop`） | 只有 backdrop/blur/lens 原语、无现成底栏；且与当时的 Kotlin 2.2.10 元数据不兼容。最终采用 LiquidVivo 的 miuix 方案 |
| 自动保存（无保存按钮） | 用户要求（m00619 "算了不要手动的保存按钮了"） |
| 波形曲线仅作预览、保存离散 timings/amplitudes | 实际执行仍走 `VibrationEffect.createWaveform` |
| 弹层一律 miuix `OverlayDialog` | 用户明确要求（m02023 "不要自己写悬浮窗，用miuix的"） |

---

## 10. 已知问题 / 待办

1. **版本号**：已升到 `1.3.0 (1300)`。
2. **待真机验收**：宿主设置页 FAB 是否只出现在设置主页、拖动贴边与位置记忆、点击后是否落到当前宿主配置页、注入状态卡是否读取到心跳。
3. **离线修改对 Hook 不可见**：没有 AIDL 后，outbox 只在模块 App 内；框架断开期间的修改要等重连补写后才会作用到输入法。
4. **FAB 外观固定**：纯 Canvas 绘制的蓝色圆形，不跟随输入法深浅色主题，后续可按宿主主题调整。
5. **host 版本查询依赖 queries**：Manifest 已声明两个宿主包；新增宿主时必须同步，否则版本显示“未知”。
6. **冲突暂未提供选择 UI**：`ConfigSnapshot.conflict` 只做标记与提示，出现冲突时保留本地版本。
7. **临时产物待清理**（见下），上次清理命令被用户拒绝，未删除。
8. **`NONROOT_FRAMEWORK_SUPPORT_GUIDE.md` 已整体作废**，是否删除待用户决定。

---

## 11. 临时产物（可删除）

工作区里这些是本轮调试/交接产生的，**不属于项目**：

```
.dsh_after_install.png  .dsh_assistant.txt  .dsh_detail2.png  .dsh_module_settings.png
.dsh_overview.png  .dsh_session.jsonl  .dsh_settings_new.png  .dsh_settings_new2.png
.dsh_tools.txt  .dsh_users.txt
build/            (根目录，Gradle 诊断产物)
```

`app/libs/npatch-remote-api-v1.0.0-release.aar`（11,742 B，SHA-256 `cbd2e96b37701a56cd08fb67003ef416609173ef97df6c65907b6dd83f20f952`）**属于项目依赖，保留**。

---

## 12. 用户需求演进（按时间，便于理解意图）

1. 按 `API102_MIGRATION_GUIDE.md` 完成 API 102 升级
2. 安装到手机验证
3. 参照 LiquidVivo 重做设置应用（底栏切换、图形化波形、miuix + LSP102 规范）、修"测试震动崩溃"
4. 取消手动保存 → 自动保存
5. 按 `UI_AND_WAVEFORM_DESIGN_GUIDE.md` 重构（曲线预览、二级卡片、有效波段数、节点编辑）
6. 详细复用 LiquidVivo 组件（状态卡/信息卡/底栏/切换动画；宿主每卡独立；关于作者卡；图标）
7. 液态玻璃底栏；`timings[0]` 固定为 0 且灰显；曲线更丝滑更粗；按方向编辑；预览仅自定义波形模式显示
8. 升级 miuix 0.9.3 + miuix-blur
9. 修底栏宽度/黑底/边距/点击跳转/长按滑动路由
10. 按 `NONROOT_FRAMEWORK_SUPPORT_GUIDE.md` 做免 Root 支持（ConfigBackend + IPC）
11. 连接方式显示在状态卡；Remote 优先、免 Root 兜底
12. 兼容模式（原"配置后端"改名，与调试输出同卡）；状态卡断开变红；频道 `t.me/imevibrator`；赞助图 `/sdcard/Pictures/WeiXin/赞助.png`
13. 兼容模式文案、删除状态行、赞助弹层被底栏挡住、加标题与"保存图片到相册"、关闭兼容模式的确认框
14. **弹层必须用 miuix 的，不要自绘悬浮窗**

---

## 13. 下次接手建议的第一步

```powershell
# 1. 确认工作区干净状态与当前构建是否通过
cd 'D:\Users\huang\IdeaProjects\WeTypeHookR'
git status --short

# 2. 直接构建
& 'C:\Users\huang\.gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat' `
  -p 'D:\Users\huang\IdeaProjects\WeTypeHookR' :app:assembleRelease --console=plain
```

然后优先处理：**升级版本号 + 同步 README + 免 Root 真机闭环验证**。
