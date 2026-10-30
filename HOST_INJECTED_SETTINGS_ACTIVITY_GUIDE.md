# 宿主设置入口与统一配置存储开发指南

> 目的：在两个输入法各自的设置主 Activity 中注入可拖动 FAB；点击后打开一个宿主专属的简化设置 Activity。该页面直接进入当前宿主配置，复用模块 App 的设置视觉与业务组件，并展示宿主版本和注入状态。模块 Launcher 主页面及其导航 UI 保持原样。
>
> 本文是实现指南，不代表下列功能已经完成。当前代码状态以“现状盘点”为准。

## 1. 当前代码现状

- 模块 App Launcher 是 `MainActivity`，Compose 根页面是 `MainScreen`。宿主详情已由 `HostSettingsScreen(hostPackage, onBack)` 承载，可作为新入口复用。
- `SettingsScreen.kt` 的 `HostSettingsScreen` 目前直接观察 `RemoteService.service`，服务未连接或没有 Remote capability 时将配置置空并报错。因此即使 `ConfigRepository` 已有 IPC 存储，这个页面仍被框架连接状态挡住。
- `ConfigRepository` 按全局 `BackendType` 路由：Remote 模式读写 LibXposed Remote Preferences/Files；IPC 模式读写模块私有目录的 `LocalConfigStore`。Remote 断开时，只有设置了 `pendingRemoteMigration` 才会走本地临时回退。
- `LocalConfigStore` 已按宿主包名存设置、波形、revision；`IntegratedConfigService` 通过 AIDL 提供受 UID 校验的快照与 revision 监听。此服务当前只在 Integrated IPC 路径中供 Hook 读取，接口不含 Activity 启动授权和注入心跳。
- `MainModule.onPackageReady` 在 Remote 可用时直接读取 Remote 配置；Remote 不可用或后端标记为 IPC 时才初始化 `IntegratedConfigClient`。当前 Remote 模式没有对模块 App 本地 IPC 离线变更的统一优先级/合并流程。
- Hook 目前处理输入事件；尚无输入法设置 Activity 识别、FAB 注入、宿主版本卡、注入状态心跳或专用宿主设置 Activity。

涉及的现有入口：`MainModule.kt`、`MainScreen.kt`、`SettingsScreen.kt`、`ConfigRepository.kt`、`LocalConfigStore.kt`、`IntegratedConfigService.kt`、`IntegratedConfigClient.kt`、`RemoteService.kt`、`RemoteConfig.kt`。

## 2. Activity 方案与 Android 边界

推荐的“宿主设置 Activity”是**模块伴生 App 声明的 exported Activity**，例如 `HostSettingsActivity`，由宿主设置页里的 FAB 通过显式 Intent 打开。Activity 在模块 App 进程运行，使用相同的配置 Repository 和 Miuix 设置组件。它不替换也不改动 Launcher 的 `MainActivity`/`MainScreen`。

Android 运行时 Hook 可以在宿主 Activity 的 View 树中添加 FAB，但不能仅靠加载模块代码，就把一个新的 Activity 组件登记进宿主 APK 的 Manifest/PackageManager。若需求必须是“组件包名属于宿主、Activity 真正在宿主 APK 内”，则必须让每个 patcher 把该 Activity 类和 Manifest 声明一并合入目标 APK，并处理 classloader、签名与升级；这不作为首选，且不能假设 LSPosed、LSPatch、NPatch 都会自动完成。

跨 App Activity 使用显式 Intent；模块 App 的 Activity 必须可被宿主进程启动，因此需要 `android:exported="true"`。导出组件不能信任 Intent 中自报的宿主包名：宿主身份需通过现有 AIDL Service 的 Binder 调用 UID 校验，并由该服务发放一次性、短时有效的宿主启动 grant。Activity 消费 grant 后才能得到宿主包名。无 grant、已过期、已消费或宿主不在 allowlist 时拒绝展示设置。不要仅靠可伪造的 `hostPackage` extra 做鉴权。

Android 官方说明了跨 App 显式启动 Activity 的组件模型，以及导出组件必须自行做好访问控制：[Intents and Intent Filters](https://developer.android.com/guide/components/intents-filters)、[Activity Security](https://developer.android.com/guide/components/activities/secure-bal)。

## 3. 用户路径与页面结构

```mermaid
flowchart LR
    A[用户打开输入法设置页] --> B[Hook 识别受支持的宿主设置 Activity]
    B --> C[将可拖动 FAB 加入该 Activity 内容层]
    C --> D[用户点击 FAB]
    D --> E[宿主进程通过受认证 AIDL 获取一次性启动 grant]
    E --> F[显式启动模块 App 的 HostSettingsActivity]
    F --> G[消费 grant，得到不可伪造的宿主包名]
    G --> H[读取该宿主唯一的有效配置快照]
    H --> I[写入 Repository，递增 revision 并通知 Hook]
```

专用页面是 `HostSettingsScreen` 的宿主直达入口，不显示宿主选择器，也不显示模块主界面的三项底栏：

1. 配置页顶部直接显示当前宿主名、包名与宿主版本（versionName、versionCode/longVersionCode）。
2. 状态卡显示“注入情况”，替代“框架连接”卡。区分尚未观测到注入、模块入口已运行、Hook 已安装、最近心跳时间、配置加载/更新状态。不要将框架 Service 连通等同于宿主 Hook 已注入。
3. 下方直接展示当前宿主的按下/抬起设置、波形、重置、复制、日志等既有功能；所有自动保存调用统一配置 Repository。
4. 主题、颜色和交互复用模块 App；页面返回回到输入法原设置 Activity，状态栏/导航栏按现有 Miuix/edge-to-edge 规范处理。

FAB 只在确认是受支持输入法的“设置主 Activity”时显示，不要全局 Hook 所有 Activity。优先通过宿主专属页面特征/稳定容器定位；保持宿主 Activity 引用为弱引用，避免重建后重复添加。按钮拖动结束后吸附到最近屏幕侧边，位置仅作为 UI 偏好按宿主分别保存；不要借用宿主 SharedPreferences 存储震动配置。点击与拖动手势要区分，FAB 不遮挡设置项、返回手势和系统边缘区域；不申请悬浮窗权限。

## 4. 配置唯一来源、离线修改和同步

### 4.1 约束

- 逻辑上每个宿主只有一份当前有效配置：`hostPackage + schemaVersion + revision + settings + waveform`。两个入口是同一个配置实体的两个编辑器，不能各维护一份 UI 私有状态或默认配置。
- `MainActivity` 与 `HostSettingsActivity` 不直接读写 RemoteService、SharedPreferences 或文件；统一调用 `ConfigRepository`。`HostSettingsScreen` 的加载也必须改为仓库快照，移除当前“service=null 就显示错误且清空设置”的分支。
- UI 连接状态、注入状态与配置可编辑性相互独立。没有框架 Service 时，页面仍可读最后已知配置/默认值并编辑；页面标注“离线待同步”即可，不得伪报“已应用”。
- 不使用 `XSharedPreferences`。Remote 模式仍遵循 API 102：设置 App 通过 `XposedService` 写，目标 Hook 侧只读；IPC 仅用有身份校验的 AIDL。

### 4.2 每种运行模式的权威后端

保持项目现有两种后端互斥，切换后端时仍需显式迁移，禁止每次编辑同时写 Remote 和本地两个完整副本。

| 有效模式 | 权威配置 | Hook 读取/监听 | 模块设置页行为 |
| --- | --- | --- | --- |
| Root / Remote 可用 | API 102 Remote Preferences + Remote Files | `XposedInterface` Remote API 与 revision listener | 直接读写该宿主的 Remote group/file |
| Remote 暂时未连上 | Remote 是已选后端；模块私有目录只作最近快照 + 有基准 revision 的待提交变更 outbox | Hook 向 `IntegratedConfigService` 拉取待提交变更；无待提交变更时继续 Remote | 展示缓存/默认值，可写离线 outbox；Service 恢复后校验基准 revision、提交 Remote、收到确认后清除 outbox |
| Integrated 或框架明确无 Remote capability | 模块私有 `LocalConfigStore` / IPC | `IntegratedConfigClient` 快照与 revision listener | 直接读写该宿主的本地 IPC 权威配置 |

Remote 离线 outbox 不是第二份可独立编辑的配置：它只保存“基于哪个远程 revision 的哪些待提交修改”，Remote 仍是 Remote 模式的权威源。缓存只用于展示最后已知状态；不得在服务恢复时把整个过期缓存无条件覆盖 Remote。若远端基准 revision 与 outbox 不匹配，保留待提交内容并提示冲突，按字段合并；同字段冲突要求用户选择本地/远程值，或使用明确、可审计的“本次本地修改优先”规则。提交失败不删除 outbox。

建议给当前设置/波形统一增加 `revision`、`schemaVersion`、`updatedAt` 和来源标记。Remote Preferences 中的波形 revision 与设置 revision 应能识别为同一次逻辑提交；IPC 本地快照更新应原子提交。若暂时沿用 `settings.json`、`waveform.json` 两文件，需先写临时文件、校验、原子 rename，再递增 revision，避免半写入状态被 Hook 读取。后续可收敛为每宿主一个 SQLite transaction 或一个原子快照文件。

### 4.3 Repository API 与同步时序

新增/调整 Repository 使两个 Activity 通过相同接口工作，建议提供：

```kotlin
observeEffectiveConfig(hostPackage): Flow<ConfigSnapshot>
saveSettings(hostPackage, settings): SaveResult
saveWaveform(hostPackage, waveform): SaveResult
resetHost(hostPackage): SaveResult
refresh(hostPackage)
```

写入时先校验宿主 allowlist、数值范围和 waveform schema。Repository 在一个串行队列中将本次修改赋予新 revision：

1. 已连接且 Remote 后端有效：提交 Remote Preferences/Files，提交成功后发布快照；Remote 写失败则返回失败，不显示“已生效”。
2. Remote 后端暂时离线：把修改写入本地 outbox，原子增加本地 revision，并通过 AIDL 通知目标 Hook。UI 显示“已保存，等待同步；宿主可读取的状态见注入卡”。
3. Integrated/IPC：写入 `LocalConfigStore` 权威快照后通过 AIDL 通知 Hook，显示“已保存”。
4. Remote Service 重新连接：按 outbox 基准 revision 校验后补写 Remote；Remote 确认成功后清除已确认操作并广播新快照。重连和提交需幂等。

波形文件必须完整写入、flush、close 成功后，才提交对应 revision；Hook 收到更新时先验证整个快照/JSON，再原子替换内存缓存。解析失败保留上一个有效值并回报诊断。不要在每次 slider 拖动的每一帧都做同步 Binder/文件 I/O；拖动期间更新 Compose draft，拖动结束/值确认后自动保存。

宿主 Hook 在初始化时先确定有效后端，再获取一次完整快照，之后订阅变化。Remote 模式下，如 IPC outbox 有更新 revision，outbox 覆盖项暂时优先于旧 Remote 值；没有待提交变更时使用 Remote。每次更新均比较 revision，较旧消息不得覆盖新缓存。读取与绑定放后台线程，输入事件 Hook 只读取内存中的不可变快照，绝不等待 IPC/网络/Binder。

## 5. 启动 grant、注入心跳和宿主身份

### Activity launch grant

扩展 `IntegratedConfigService` 或新增专用的受限 Binder interface，至少有：

- `requestHostSettingsGrant()`：根据 `Binder.getCallingUid()` 找出调用者拥有的受支持宿主包名，不信任客户端传入的包名；返回随机、一次性、短时 token。
- `consumeHostSettingsGrant(token)`：仅成功消费一次，返回宿主包名。过期、重放、未授权宿主均拒绝。
- 设置 Activity 以模块包名显式启动，不提供通用任意 host 参数。Activity 无有效 grant 时显示无权限/已过期并立即关闭。

现有 `IntegratedConfigService` 只能授权配置读取/监听，不应误认为已经有 Activity 授权能力。不要把 grant 加入日志 IPC。

### 注入情况卡

加一个最小状态上报接口，和配置接口分开授权/字段。`MainModule.onPackageReady` 在确认宿主包名、入口加载后上报 module version、框架名称/API（若 API 可读）、启动时间和 hook 安装结果；目标 Hook 安装完成后再上报 hook 状态。服务端继续以 Binder UID 与包名 allowlist 认证，时间使用服务端接收时间。

卡片应显示精确状态，例如：

- “未观测到注入” + 最近成功时间（无法证明绝对未注入，使用“未观测到”措辞）；
- “模块已加载，Hook 安装失败/未完成”；
- “Hook 已注入，配置 revision N”；
- “Hook 已注入，存在待同步设置”；
- “宿主应用未安装/版本无法读取”。

状态心跳失败不应阻止设置页读写。注入状态记录与震动配置分开存放，避免状态刷新改写配置 revision。

### 宿主版本

从有效 grant 得到的受信任宿主包名查询 PackageManager，显示 `versionName` 和 `longVersionCode`（或兼容字段）。Manifest 增加两个固定宿主 `<queries><package .../></queries>` 声明，遵守 Android 包可见性。版本查询失败要显示“未知”，不能从 FAB Intent 直接信任版本字符串。

## 6. Manifest 与跨框架启动要求

在模块 App Manifest 增加专用 Activity（示意）：

```xml
<activity
    android:name=".HostSettingsActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:launchMode="singleTop" />
```

不声明可被任意隐式 Intent 命中的过滤器。通过模块包名 + Activity class 的显式 Intent 打开，使用一次性 grant extra。宿主进程当前有可见 Activity 且是用户点击 FAB 触发；如果目标框架的启动策略拦截外部 Activity，给出可操作失败提示并提供“打开模块 App”的路径，不申请 SYSTEM_ALERT_WINDOW 绕过限制。

Root LSPosed、LSPatch Manager/Local 与 Integrated、NPatch Manager/Local 与 Integrated 分别验证：模块 companion 是否单独安装、模块 Activity 是否可解析启动、IPC Service 是否可绑定、包可见性查询是否可用。Integrated patch 若未安装 companion APK，则无法启动模块 App 中的配置 Activity；产品必须说明安装 companion 的要求。不能把嵌入到宿主的模块 DEX 等同于模块 APK 的 Activity 已注册到宿主系统组件清单。

## 7. 代码改造边界

### 保持不变

- 不调整 Launcher `MainActivity`、`MainScreen` 的导航、底栏、页面入口或设置项布局。
- 不把模块主页面改成宿主快捷入口，也不要求用户先选择宿主才能从宿主 FAB 进入配置。
- 现有宿主配置字段、波形编辑 UI、自动保存语义和宿主包名隔离继续复用。

### 新增或调整

1. 新增 `HostSettingsActivity`，只做 grant 校验、宿主上下文与页面生命周期；直接调用共享的 `HostSettingsScreen(hostPackage, mode=HOST_DIRECT, onBack=...)`。
2. 将宿主设置屏幕的数据加载/保存彻底改到 `ConfigRepository` 的有效快照 API，不能在 UI 层直接要求 `RemoteService.service != null`。
3. 新增 host settings launch grant 与 injection heartbeat 的 AIDL 方法/接口；保持配置、日志、状态、启动授权权限最小化分离。
4. 在 `MainModule` 中按宿主白名单识别各输入法设置 Activity，仅在设置主页安装 FAB；点击时走 grant + explicit Intent。宿主特定 Activity/锚点匹配独立封装，失败时不影响输入法设置页。
5. 将 `LocalConfigStore` 从“仅 Integrated 使用”扩展为同一模块配置 Repository 的离线 outbox/IPC 后端；统一 revision、原子性、冲突与清理策略。
6. 重构 `MainModule` 配置启动逻辑，使其按后端决策加载完整快照，并监听有效配置更新；输入事件 Hook 保持只读内存缓存。
7. 新增固定宿主 package queries；检查 `exported` 组件安全规则与 LSPatch/NPatch manifest merge 行为。

## 8. 实施顺序

1. **先统一数据契约：** 定义 ConfigSnapshot、revision、schemaVersion、离线 outbox、Remote/IPC 决策和冲突策略；确认旧 Remote 配置与 `LocalConfigStore` 如何一次性导入，迁移成功后不再自动双写。
2. **消除服务门槛：** 先重构现有 `HostSettingsScreen` 使用有效配置快照，验证模块主页面当前宿主设置可离线查看/编辑；补上重连/outbox 状态。
3. **完成 Hook 同步：** Root Remote、Remote 离线 outbox、Integrated IPC 三路均能读相同有效快照并通过 revision 更新内存；Binder 断开不阻塞键盘。
4. **完成 Activity grant：** 给受支持 host UID 发放一次性 grant，拒绝伪造/重放；新 Activity 仅渲染该 grant 对应宿主。
5. **实现设置页 FAB：** 先逐个输入法验证 Activity/锚点，再加拖动、贴边吸附、位置保存、重复注入清理和显式跳转。
6. **加入宿主元信息：** 版本号查询、注入心跳与状态卡；状态获取失败不影响保存。
7. **回归并更新文档：** 覆盖 Root 与全部计划支持的免 Root 模式，记录 companion 安装要求、未支持路径和失败提示。

## 9. 验收清单

- [ ] Launcher `MainActivity`/`MainScreen` 布局和导航未变化。
- [ ] 微信输入法和豆包输入法各自在“设置主 Activity”只显示一个 FAB；输入法其他 Activity 不出现。
- [ ] FAB 可拖动，点击可打开模块 App 的专用宿主配置 Activity；关闭后返回原设置位置。
- [ ] 不通过 Intent 自报包名决定配置目标；无效、过期、重复 token 不能打开宿主配置。
- [ ] 宿主页直达当前宿主配置，没有宿主选择器/模块底栏；宿主名、版本、注入状态与框架连接状态分开。
- [ ] 框架服务未连接时仍可读取最近快照或默认值、修改所有设置和波形；UI 明确区分已应用与待同步。
- [ ] Root Remote 正常时，模块主页面、宿主页面、Hook 实际效果读写同一 Remote 配置；保存后 Remote listener 更新 Hook 缓存。
- [ ] Remote 离线保存时产生唯一待提交 outbox；Hook 能获取最新有效值；Remote 恢复后冲突处理正确，提交确认后 outbox 清理，重复重连不重复应用。
- [ ] Integrated IPC 模式不写 Remote 副本；配置 AIDL 仍校验 UID/宿主 allowlist/协议版本，配置消息大小和波形 schema 有上限。
- [ ] 波形文件异常、进程死亡、Service 死亡、activity 重建、快速连续编辑时不丢失已确认 revision，不用半写入值替换有效缓存。
- [ ] Hook 安装状态未上报、Activity grant 失败、包不可见、companion 未安装、目标框架禁止启动时都有可理解状态，且不阻止宿主原功能。
- [ ] 同一宿主从两个设置入口连续编辑，第二个入口刷新后看到同一 revision；两个宿主的配置、波形和日志隔离。

## 10. 官方与项目参考

- [Android Intents and Intent Filters](https://developer.android.com/guide/components/intents-filters)：显式组件启动与 Intent 数据。
- [Android Activity Security](https://developer.android.com/guide/components/activities/secure-bal)：导出 Activity 与跨 App 启动安全。
- `API102_MIGRATION_GUIDE.md`：API 102 Remote 与目标 Hook 约束。
- `NONROOT_FRAMEWORK_SUPPORT_GUIDE.md`：NPatch/LSPatch 模式、IPC 校验和验收矩阵。
- `app/src/main/java/com/haostoo/wetypehookr/ConfigRepository.kt`、`LocalConfigStore.kt`、`IntegratedConfigService.kt`、`IntegratedConfigClient.kt`：当前配置后端与 IPC 基础。
