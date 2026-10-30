# WeTypeHookR 免 Root 框架适配操作指南

**适用项目：** WeTypeHookR（LibXposed API 102，Remote 配置）  
**目标框架：** JingMatrix/LSPatch、7723mod/NPatch  
**基线日期：** 2026-10-18  
**范围决定：** 同时规划 Manager/Local 与 Integrated 模式；NPatch 可先使用明确标识支持 API 102 的 Canary/源码构建；不使用 `XSharedPreferences`。Integrated 模式在标准 API 102 Remote 无法到达设置 App 时，允许增加经过身份验证的 IPC 配置桥。

本文供实现代理按照步骤适配和验证。它是框架兼容操作方案，不代表已完成代码改造或所有框架版本均已验证。实施期间若上游版本状态改变，先重新核对官方 release、运行时 API 版本和 Remote 能力，再更新兼容矩阵。

## 1. 当前项目基线

- 本项目已声明 `minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`、`autoHotReload=false`；`scope.list` 当前包含微信输入法 `com.tencent.wetype` 与豆包输入法 `com.bytedance.android.doubaoime`。
- Hook 入口使用 LibXposed API；配置由模块 App 侧 `RemoteService` 写入 Remote Preferences / Remote Files，宿主进程侧通过 `XposedInterface` 只读读取。按下/抬起设置使用不同的固定 group/key，波形文件按宿主隔离，并通过 `customhaptic_revision` 通知刷新。
- 模块 App 已有 launcher Activity。日志由宿主进程绑定模块包下的 `LogService` 上报，Service 通过调用 UID 校验来源。
- 当前 `RemoteService` 只依赖 `XposedServiceHelper`，还没有 NPatch Remote SDK fallback，也没有 Integrated 模式的显式本地 IPC 配置后端。
- 当前 `app/build.gradle.kts` 的 `minSdk=30`。虽然 LSPatch/NPatch 框架本身标注 Android 9（API 28）起，本项目 APK 在不调整 minSdk 前不能宣称支持 API 28/29；若要覆盖这些 Android 版本，需先检查依赖与代码兼容性后再改 `minSdk`。

## 2. 截至基线日期的框架状态与结论

| 框架/模式 | API 102 状态 | 配置服务判断 | 本项目接入结论 |
|---|---|---|---|
| JingMatrix/LSPatch v1.2 Manager mode | 官方发布说明基于 Vector，支持现代 LibXposed；v1.2 发布说明含 companion 设置触达能力 | 先使用标准 `XposedServiceHelper`/API 102 Remote；对设置 App 到运行中宿主的读写与通知做设备验证 | 首期稳定版本测试目标 |
| JingMatrix/LSPatch v1.2 Embedded/Integrated | 同上，模块代码嵌入每个被修补 APK | 标准 Remote/companion 可能可用，但必须验证写入能到目标进程且重启后配置保留；失败时用本指南的 IPC 后端 | 纳入目标，按明确模式选择后端 |
| NPatch 最新稳定版 v1.0.9 | Release 页面明确写明该稳定版尚未包含 API 102；虽然在线文档/主分支出现 API 102 支持描述，二者存在版本不一致 | 不可因主分支文档就把 v1.0.9 标成 API 102 Remote 可用 | 不列入稳定兼容；先用满足 API 102 的 Canary/指定源码构建验证 |
| NPatch Local（Manager） | NPatch Remote API 文档描述 API 101/102 runtime 和 Remote Store；Manager 版本要求在文档中标为 v1.0.7+，但应遵守具体构建实际 API | 模块 App 优先标准 `XposedServiceHelper`；未收到标准 Service 时，用官方 `NPatchRemoteClient` 异步连接 Local Manager | 可用通过 API/capability 检查的 Canary/源码构建验证 |
| NPatch Integrated | 目标进程有目标本地 Store；公开 `NPatchRemoteClient` 没有 Manager Provider 可连接 | 先验证 Companion/标准 Service 是否能把目标本地 Remote 暴露给模块设置 App；否则必须切到本地持久化 + 认证 IPC 后端 | 纳入目标，但不能假称可用 NPatch Remote Client |

**框架发布版和开发文档不能混为一谈。** NPatch v1.0.9 Release 明确标注未带 API 102，NPatch 在线 Remote 文档/开发分支则有 API 102 API 说明。实现验收必须记录被测 NPatch APK 的精确版本/commit，并在运行时核验 API version 与 `PROP_CAP_REMOTE`；不满足时只标为实验构建，不纳入稳定支持声明。

JingMatrix/LSPatch 必须使用 [JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch) 这一基于 Vector 的现代实现。不要误用早期 `LSPosed/LSPatch` 归档版本或其他只支持旧 Xposed API 的同名分支。

## 3. 两种免 Root 工作模式

### 3.1 Manager / Local 模式

框架 Manager 保持安装并负责目标模块清单。用户将每个目标 APK 修补为 Manager 模式，安装修补后的同包名应用，再在 Manager 中启用 WeTypeHookR。通常更换模块或配置不要求重新修补目标应用。

- LSPatch v1.2 Manager mode：按其 Manager patch 流程分别修补两个目标；安装模块 APK 为独立 companion/设置 App；启用 WeTypeHookR 并核对作用域。
- NPatch Local mode：同样分别修补两个目标并由 NPatch Manager 管理模块；模块必须以真实包名 `com.haostoo.wetypehookr` 安装并被 Manager 识别。必要时由 Remote SDK 连接 NPatch Local Manager。
- 这一模式优先走 LibXposed 标准 Remote；NPatch SDK 只负责在模块设置 App 中建立 Local Manager 侧的连接，不进入目标 Hook 代码。

### 3.2 Integrated / Embedded 模式

修补时把模块代码嵌入每个目标 APK。目标运行不依赖 Manager，但更新目标 Loader/模块代码通常需要重新修补和安装相应目标。为继续使用模块自己的 launcher 设置界面，**还须单独安装 WeTypeHookR APK 作为 companion 设置 App**；只把 APK 嵌入目标、未安装 companion 时，用户没有独立的模块设置入口。

- LSPatch v1.2 有 companion 触达/服务变化说明；验收时仍需验证 companion 打开后改设置能否立即到达运行中的目标 Hook，且进程重启后值仍在。
- NPatch Integrated 的公开 `NPatchRemoteClient` 不连接目标本地 Store。先验证框架是否给 companion 设置 App 投递可写标准 API 102 Service；若没有，使用第 5 节明确选择的 `Integrated IPC` 后端。
- Integrated 不得自动猜测当前框架模式，也不得在标准 Remote 短时断线时静默改写另一份本地配置。模块 App 应显示已选择/已连接的配置后端和目标宿主，允许用户显式选“框架 Remote”或“嵌入式 IPC”。

## 4. 配置与日志架构改造要求

### 4.1 统一传输接口

在 UI/Hook 业务模型与 LibXposed Binder 实现之间建立一个很薄的配置仓库/传输接口，例如 `ConfigBackend`；现有 `RemoteConfig` 模型、group/key/file 常量和 JSON schema继续作为统一数据契约。建议分层：

1. **标准 LibXposed Backend：** 模块 App 用 `XposedServiceHelper.registerListener` 获取 `XposedService`；宿主 Hook 用 `XposedInterface` 读 Remote Preferences/Files。
2. **NPatch Local Backend：** 仅在 NPatch Local Manager 模式使用官方 `NPatchRemoteClient` 作显式/备用连接，取得同一 API 102 `IXposedService` 合约。优先等待标准 service；只有确认未到达且 NPatch Provider 可用时才异步 `connectAsync`。不应反复并发连接或每个 Composable 各建 client。
3. **Integrated IPC Backend：** 仅当 Integrated 的模块 companion 无法取得标准可写 Remote Service 时启用。详情见下一节。它不是 NPatch Remote Store，也不得伪装成 LibXposed Remote。

后端状态应包括 `未连接 / 正在连接 / 可读写 / 仅 Hook 侧可读 / 写入失败 / 后端选择不匹配`，并保留框架名称、版本/API、Remote capability、所选模式和错误用于诊断。`isAvailable()`/Provider 可解析只代表候选端点存在，最终以 authenticated connect、Remote capability 和真实读写结果为准。

### 4.2 Integrated IPC 后端（仅作 Remote 不可达时的明确备用）

**目标：** 仍不用 `XSharedPreferences`；设置 App 的模块私有存储是唯一持久来源，宿主 Hook 通过受限 Binder 获取和订阅配置，不直接读模块私有文件。

- 模块设置 App 的本地数据库/私有文件按宿主包名存放完整设置与波形 JSON，写入后使用单调递增 revision。Remote 模式仍以框架 Remote 为唯一权威，不同时写本地和 Remote 两份数据。
- 提供单独的显式绑定配置 Service，或在现有 IPC Service 上增加独立、版本化、强类型的配置接口；不要把任意路径、文件名、任意 JSON 写入接口暴露给调用端。建议以 AIDL/Messenger 请求 `getSnapshot(hostPackage)`、注册 revision 监听、取消监听；所有 Binder 操作短小，磁盘 I/O 转后台线程。
- Service 端从 Binder 调用上下文读取 `Binder.getCallingUid()`，验证 UID 对应的已安装包中包含受支持宿主包名；不能信任客户端自报 packageName。配置字段范围、波形 JSON、宿主包名、请求长度都要服务端校验。拒绝未知 UID、未支持宿主或超长消息。
- Hook 客户端绑定失败/断开时继续使用进程内最后一个校验通过的配置或默认值，不阻塞输入法主线程、不崩溃。订阅配置变化，revision 增长后原子替换缓存；重连时先拉取全量快照，再继续订阅。
- 日志与配置权限分离。保留 `LogService` 的最小 append 能力和 UID 校验；不能因为已有日志 IPC 就允许日志 Service 改设置，也不能把配置 Binder 开成匿名广播。
- 模块 App UI 明确标示 Integrated IPC 模式正在生效；导入、重置、波形保存和日志查看要使用与当前选择后端一致的 Repository。切换后端前明确告知配置不自动合并，并提供显式导出/导入或迁移操作。
- 当 Framework Remote 恢复或用户切换回 Manager 模式，不自动用旧本地快照覆盖远程配置。先显示两端目标宿主与更新时间/摘要，再让用户选择导入方向；若产品暂不做冲突 UI，禁止自动双写/合并。

实现前必须先验证：两个框架中“独立安装的 companion App”能否接收标准 `XposedService` 以及 service 的 `getRemotePreferences()`、`openRemoteFile()` 是否可写。只有验证失败的 Integrated 框架/模式才启用 IPC 后端；不要对所有模式无差别复制第二套存储。

### 4.3 API 102 Remote 数据契约保持不变

- 目标 Hook 侧继续用 `XposedInterface.getRemotePreferences(group)` 和 `openRemoteFile(name)`；Remote Preferences/Files 在注入侧只读。
- 两宿主继续使用固定独立 group 与 file，例如 `wetype_settings`、`doubao_settings`、`customhaptic_wetype.json`、`customhaptic_doubao.json`；不按框架名称或 patch mode 改配置名，保证不同框架下同一份模块数据模型。
- 写 Remote File 完整成功并关闭后，再提交该宿主 `customhaptic_revision`；监听到 revision 再重载，解析失败保留最后有效缓存。
- API 102 的 `minApiVersion/targetApiVersion` 不因免 Root 框架改回 100/101；不添加旧 API fallback 或旧 Xposed Hook API。
- `staticScope=true` 与 `scope.list` 的两宿主范围保留。实现者要在各 Manager/Integrated 模式确认静态作用域可被识别；若某框架忽略 manifest 静态作用域，应记录框架差异，不直接扩大到所有应用。

### 4.4 日志 Service 兼容检查

已有 `LogService` 应保持独立于 Remote 配置仓库。分别从 LSPatch/NPatch 修补目标进程向模块 `LogService` 发送测试日志，验证：

- 目标进程能绑定模块包的显式导出 Service；Service 可取得真实调用 UID。
- `PackageManager.getPackagesForUid(callingUid)` 在修补后仍能识别目标包；被其他包调用/自报目标包必须被拒绝。
- Manager/Integrated 模式下目标 UID、包可见性、多用户 Profile 变化不会让日志静默丢失或误归属。
- 框架或 Manager 不可用时日志失败不得影响 Hook，使用有界队列/丢弃策略，不阻塞输入法线程。

如修补框架改变 `callingUid` 与可见 package 映射，先把日志状态标为兼容缺陷，不能为了“支持”而移除 UID 校验。

## 5. 实施阶段与操作步骤

### 阶段 A：建立可复现基线

1. 保存原始微信输入法、豆包输入法 APK；若是 split APK，保存 base 与全部必需 split，记录版本号、证书摘要、ABI、Android 版本和安装来源。
2. 当前模块 API 102 APK 使用同一份未混淆/发布产物作为测试模块；记录 SHA-256、`module.prop`、`scope.list`、入口文件和版本号。
3. 准备可恢复的非生产设备/用户 Profile。先验证原始输入法可正常输入、震动 Hook 在 API 102 Rooted Vector/现有框架下工作，再开始 patch 测试。
4. 开启模块诊断信息：framework name/version、API version、`PROP_CAP_REMOTE`、backend 类型、host package、配置 revision、绑定/写入错误。不得记录配置隐私内容。

### 阶段 B：LSPatch v1.2 Manager mode

1. 安装官方 [JingMatrix/LSPatch 稳定版](https://github.com/JingMatrix/LSPatch/releases/latest) Manager v1.2；确认所选构建的 Vector runtime 运行 API 102。
2. 将 WeTypeHookR 模块 APK 安装为独立 App/companion，并确保包名未被重命名。
3. 在 LSPatch Manager 分别选择原始微信输入法和豆包输入法，使用 Manager mode patch；选择/启用 WeTypeHookR 模块并检查每个 patched target 的 scope。
4. 安装修补后的目标 APK（若签名变化，按系统要求先卸载同包名原版；先备份数据）。分别启动两个输入法，确认 Hook 入口加载且 Remote 能力可用。
5. 打开模块 App，验证标准 `XposedServiceHelper` 连接；在微信/豆包各保存不同设置，确认 group 和波形文件互不覆盖；更改设置后验证现运行目标收到 Remote Preferences/revision 更新。
6. 验证 LSPatch v1.2 companion 设置行为：目标已运行时修改普通设置和波形能否及时到达目标；若不能，在 UI 提示“修改后需重启目标”并记录为框架限制，不能误报即时生效。

### 阶段 C：LSPatch v1.2 Embedded mode

1. 为两个目标分别从原始 base/split APK 进行 Embedded patch，把 WeTypeHookR 嵌入每个目标；记录 patch 参数、签名证书及输出校验值。
2. 安装 WeTypeHookR companion APK 与两个 patched host；确认 launcher Activity 可打开。
3. 在未安装/停止 LSPatch Manager 的条件下，分别测试：设置 App 获取标准 Remote service、写基础偏好、写 Remote File、revision 更新、目标重启后读取。
4. 如果标准 Remote 对 companion 不可用，启用 Integrated IPC 后端做同样测试；验证服务 UID 白名单、revision 订阅和每宿主独立持久化。
5. 更新配置后退出/杀掉 companion，再启动目标验证持久化；结束目标进程再开，确认缓存从权威后端重建。

### 阶段 D：NPatch API 102 Canary / 源码构建

1. 获取 NPatch Releases/源码；**不可直接用 v1.0.9 稳定版作为 API 102 验收结论**。挑选明确含 API 102 的 Canary/commit，记录构建渠道、版本、commit 和校验值；不要只根据仓库网页文案判定。
2. 确认其运行时对模块报告 API 102，目标侧 `PROP_CAP_REMOTE` 可用；创建最小测试确认 API 102 lifecycle、Remote Preferences 只读监听、Remote Files 只读行为。
3. Local Manager mode 下安装 NPatch Manager、安装模块 companion App、分别 patch 两个目标、在 Manager 中启用 module/scope。先走标准 service 投递；若模块 App 没收到标准 `XposedService`，使用官方发布的 NPatch Remote API AAR，通过 `NPatchRemoteClient.connectAsync` 连接 Local Manager。
4. NPatch Remote SDK 集成按下面的“NPatch Remote SDK 依赖”说明固定版本、核对校验值，并按官方文档增加 `<queries><provider android:authorities="top.nkbe.npatch.remote" /></queries>`。SDK 只放在 App 侧，不进入 Hook 侧。
5. `isAvailable()` 仅用于探测 Provider 是否可见，连接必须以认证 Binder 成功为准；处理 Provider 缺失、未登记模块、UID/包名不匹配、Manager 被杀、Binder 过期、RemoteException。使用异步连接，不在 UI 主线程阻塞。
6. Integrated mode 下检查标准 companion Service 是否存在；NPatch Remote SDK 的公开客户端不适用 Integrated。不可连接时使用明确选择的 Integrated IPC 后端，并做完整存储、权限和崩溃恢复验证。

### 阶段 E：回归、恢复与发布说明

1. 依次覆盖 2 个宿主 × 2 个框架 × Manager/Local 与 Integrated 模式；未实际跑通的矩阵单元保持“实验/未验证”，不写为支持。
2. 回归 Rooted API 102 Remote 原路径，确保新增 backend 选择没有破坏标准 service。
3. 验证模块更新、目标输入法更新、patcher/Manager 更新的流程彼此区分；目标 Loader/API 变化时需重新 patch 的模式必须有清晰 UI/README 提示。
4. 更新 README/发行说明：列出框架和最低验证版本、支持模式、模块 App 是否必须单独安装、NPatch Canary 的实验状态、需要重 patch 的时机、配置后端及已知限制。
5. 保留原始 APK 和 patch 参数；验证管理器能恢复原始应用或按已记录步骤卸载/重装。不得引导用户通过第三方工具绕过签名/加固或远程完整性验证。

## 6. 免 Root 修补的安装/更新限制

### NPatch Remote SDK 依赖

截至 2026-10-18，官方仓库源码的 Gradle 配置声明 `group = top.nkbe.npatch`、`artifactId = npatch-remote-api`、`version = 1.0.1`，但这只是 Maven publication 的坐标配置：仓库只配置了 `publishReleasePublicationToMavenLocal` 本地发布任务，没有配置可验证的远程 Maven 仓库地址；官方 README 和开发指南要求从 GitHub Releases 下载 AAR。因此不得把 `top.nkbe.npatch:npatch-remote-api:1.0.1` 或推测的 JitPack 坐标写成可直接解析的依赖。

GitHub Releases 当前最新正式 Release 是 `v1.0.0`，提供的文件名为 `npatch-remote-api-v1.0.0-release.aar`。使用官方 [v1.0.0 Release 资产](https://github.com/7723mod/NPatch-Remote-API/releases/download/v1.0.0/npatch-remote-api-v1.0.0-release.aar)，放到 `app/libs/`。该 AAR 的 SHA-256 为 `cbd2e96b37701a56cd08fb67003ef416609173ef97df6c65907b6dd83f20f952`；升级时以 Release 页面实际资产、版本说明和校验值为准，不要只按 master 分支的版本号升级。

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/npatch-remote-api-v1.0.0-release.aar"))
    // 若没有通过 libxposed:service 间接依赖 interface，则显式声明：
    implementation("io.github.libxposed:interface:102.0.0")
}
```

这是 NPatch Local Manager 的设置 App 连接客户端，只服务于 companion App。它不是目标进程 Hook API，也不能用于 NPatch Integrated 的 host-local Store。配套声明包可见性：

```xml
<queries>
    <provider android:authorities="top.nkbe.npatch.remote" />
</queries>
```

若未来官方发布远程 Maven/JitPack 仓库，只有在官方文档明确给出 repository URL、完整坐标且可解析对应版本后，才切换到远程依赖；否则继续使用经校验的 Release AAR。

- LSPatch/NPatch 的核心是修补具体目标 APK，不是像 Root 注入那样自动覆盖设备全部应用。两个输入法都必须分别修补；其他未修补进程不会加载模块。
- 修补 APK 的签名可能与原商店包不同。系统可能要求卸载原版才能安装；应用私有数据、系统预装更新包、同签名共享 UID、签名权限、应用自校验或服务端完整性验证都可能导致失败或数据迁移困难。
- 如果输入法是 ROM 预装/priv-app，非 Root 无法替换只读系统分区；卸载更新包后重新安装修补版也不保证 ROM 允许，需单独评估设备。不可把“框架可 patch APK”推断成“每台设备都可替换预装输入法”。
- split APK 必须完整收集并用相同 patch 参数重组；目标版本更新后旧 patched APK 不是新版本，通常要从新原版 APK 重 patch。优先从原始 APK 输入，不把旧 patched APK 当作下一次补丁的默认 source。
- Manager 模式需要 Manager 常驻/可重新拉起；Integrated 模式不依赖 Manager，但更新模块代码一般要重 patch/安装目标。Shizuku 只影响部分安装/管理操作，不是框架 Hook API 的必要依赖。
- NPatch/LSPatch 的框架最低 Android API 为 28，但本项目现为 minSdk 30。文档支持范围应同时写明框架与 WeTypeHookR APK 自身的最低 SDK，不能混为一谈。

## 7. 兼容性验收表

| 测试项 | LSPatch Manager | LSPatch Integrated | NPatch Local Manager | NPatch Integrated |
|---|---:|---:|---:|---:|
| 运行时明确报告 API 102 | 必须 | 必须 | Canary/源码必须 | Canary/源码必须 |
| `PROP_CAP_REMOTE` 读取 | 必须 | 必须 | 必须 | 必须 |
| 模块设置 App 取得标准 writable service | 验证 | 验证 | 验证 | 验证 |
| NPatch Remote Client 连接 | 不适用 | 不适用 | service fallback | 不支持；不要尝试 Manager Provider |
| Integrated IPC fallback | 不适用 | 若标准 service 失败则验证 | 不适用 | 标准 service 失败时必须验证 |
| 两宿主 Remote group/file 隔离 | 必须 | 必须 | 必须 | 若走 Remote 必须；若走 IPC 按 host package 隔离 |
| 基础设置即时变化/进程重启恢复 | 必须 | 必须 | 必须 | 必须 |
| 曲线 JSON/revision 刷新 | 必须 | 必须 | 必须 | 必须 |
| LogService UID 鉴权 | 必须 | 必须 | 必须 | 必须 |
| 管理器死亡/服务断开行为 | 必须 | 不适用或按框架模式 | 必须 | 按 IPC/框架服务行为 |
| 回退/恢复原版路径 | 必须 | 必须 | 必须 | 必须 |

### 不允许发布为“支持”的失败条件

- 只确认 Hook 回调被调用，但未验证 Remote capability、目标配置读取与设置 App 写入。
- 只在其中一个输入法/一个 patch mode 成功。
- 模块设置页面显示保存成功，但 Hook 进程读到旧值或另一个宿主的值。
- 通过删除 UID 校验、隐藏 Remote 不支持错误、从不受支持的私有目录读文件来让测试“变绿”。
- NPatch Canary/源码构建的成功结果被描述为 NPatch 稳定版已支持 API 102。
- Integrated IPC 未验证调用者身份、配置持久化、Binder 断线恢复和多目标隔离。

## 8. API 102 / Android 组件检查清单

- [ ] 保持 `minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`；当前入口、`scope.list` 和 API 依赖符合项目迁移指南。
- [ ] 不依赖 `de.robv.android.xposed`、`XSharedPreferences`、资源 Hook 或私有框架 AIDL。
- [ ] Hook 进程只使用 API 102 `XposedInterface`；NPatch SDK 只在模块 App 侧运行。
- [ ] NPatch `queries` provider 声明只用于 package visibility，不扩大 exported component。
- [ ] Remote API 的异步连接、Service death、Binder stale 和 RemoteException 均有生命周期处理。
- [ ] `LogService` 和 Integrated IPC Service 都校验 Binder UID、调用包名、目标范围和消息边界；没有任意路径/任意配置写操作。
- [ ] Module App 仍独立可启动；Integrated patch 中必须明确配套 companion App 的安装要求。
- [ ] 多进程/重复启动、服务重连、模块进程被系统回收、App 用户/Profile 切换不会错配 host config。

## 9. 官方资料与版本依据

- [JingMatrix/LSPatch README](https://github.com/JingMatrix/LSPatch)：Rootless 机制、Manager/Embedded 模式、现代 LibXposed 模块说明。
- [LSPatch v1.2 Release](https://github.com/JingMatrix/LSPatch/releases)：v1.2 companion 设置触达与连接说明；发行说明中的 Runtime API 以安装构建实际报告为准。
- [JingMatrix/Vector Releases](https://github.com/JingMatrix/Vector/releases)：Vector v2.2 发布说明 API 102 支持；LSPatch 基于 Vector runtime。
- [7723mod/NPatch README](https://github.com/7723mod/NPatch)：Local/Integrated 模式及运行方式。
- [NPatch Releases](https://github.com/7723mod/NPatch/releases)：检查当前稳定版与 Canary 的 API 状态。v1.0.9 Release 当前说明尚未包含 API 102。
- [NPatch Remote API 官方开发指南](https://npatch.nkbe.top/guide/remote-api)：Local Manager 模式 SDK、`NPatchRemoteClient`、Provider 查询、Integrated 不适用范围、Remote read/write 边界。
- [NPatch Remote API 仓库/Release](https://github.com/7723mod/NPatch-Remote-API)：获取当前公开 SDK/AAR；以当前发布版本和官方文档为准。
- [LibXposed API](https://github.com/libxposed/api)、[LibXposed Service](https://github.com/libxposed/service)、[LibXposed API 102 Javadoc](https://jingmatrix.github.io/libxposed-api/)。
- [本项目 API 102 与 Remote 架构指南](API102_MIGRATION_GUIDE.md)。
