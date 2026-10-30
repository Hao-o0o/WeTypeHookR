# WeTypeHookR API 102 与模块设置页迁移指南

本文是实现任务说明，供执行代理按步骤完成迁移。它基于当前仓库代码和 LibXposed API 102 文档；遇到本文标注为未定的行为时，先询问项目负责人，不要自行扩大或缩小产品范围。

## 已确认的产品决定

1. 模块最低要求为 LibXposed API 102，使用标准 Remote 能力；不使用 `XSharedPreferences`。
2. 设置入口迁至模块自身的 launcher Activity，由模块主界面统一选择微信输入法或豆包输入法并进入各自设置。
3. 原有用户配置提供手动导入入口。导入旧文件前，用户可能需要借助有权限的文件管理器、ADB 或 root 将文件复制到系统文件选择器可访问的位置。
4. 自定义波形配置由模块页管理，跨进程存储使用 LibXposed Remote Files。
5. 调试日志仍须在模块页查看、清理和导出。由于目标进程侧 Remote Preferences/Files 为只读，日志写入单独通过受限的模块绑定 Service 完成；此 Service 不承载设置配置。
6. 目前 Hook 只针对 `com.tencent.wetype` 和 `com.bytedance.android.doubaoime`。静态 scope 继续限定为这两个包。

## 当前实现基线

- `app/build.gradle.kts` 当前依赖 `io.github.libxposed:api:101.0.1`，尚未引入 `libxposed/service`。
- `app/src/main/resources/META-INF/xposed/` 已有 `module.prop`、`java_init.list`、`scope.list`，但 `module.prop` 是 `minApiVersion=93`、`targetApiVersion=101`。
- `app/src/main/assets/xposed_init` 是遗留入口声明，且引用仓库中不存在的 `MainModule_YukiHookXposedInit` 类。
- `MainModule.kt` 已使用现代 `XposedModule` / `hook(...).intercept(...)` API。主要迁移工作是版本和声明清理、配置通道、设置 Activity 与日志跨进程写入。
- `SettingsActivityInjector.kt` 和 `MainModule.kt` 中微信/豆包设置入口 Hook 负责劫持宿主 Activity；`App.kt` 通过宿主外部目录读写 `config.xml`；`MainModule.kt` 用宿主外部目录和 `FileObserver` 热加载。
- `SettingsScreen.kt`、`DebugScreen.kt` 和 `DebugLog.kt` 仍存在对注入 Activity、宿主 `packageName` 或宿主外部文件路径的耦合。
- 仓库当前工作区已有 `.idea/vcs.xml` 未提交修改；迁移不得覆盖或格式化该文件。

## API 102 规则

- Hook API 依赖使用 `compileOnly("io.github.libxposed:api:102.0.0")`。
- 模块应用侧使用 `implementation("io.github.libxposed:service:102.0.0")` 接收框架服务，并通过 `XposedServiceHelper` 注册监听。
- 模块入口只通过 `META-INF/xposed/java_init.list` 声明；模块信息和静态作用域只通过 `META-INF/xposed/module.prop`、`scope.list` 声明。不要再依赖 `assets/xposed_init`、YukiHook 初始化资源或旧式 Manifest Xposed metadata。
- `targetApiVersion=102` 的实现不得调用 `de.robv.android.xposed` 遗留 API。不要添加 `XposedBridge`、`XposedHelpers`、`XC_MethodHook`、`XSharedPreferences` 或资源 Hook。
- `XposedInterface.getRemotePreferences(group)` 在目标 Hook 侧只读；写入由模块 App 取得的 `XposedService` 完成。用 Remote Preferences 的 change listener 更新 Hook 侧缓存，替换当前配置文件 `FileObserver`。
- Remote Files 在模块 App 侧可写，在 Hook 侧只读；文件名必须是单层名称。波形文件写完并关闭后，再提交 Remote Preferences 中的版本号/变更标记，让 Hook 侧收到偏好变更并重新读取文件。
- Remote 能力在 embedded 框架中可能不存在。Hook 与设置页均须检查框架能力/服务状态，显示明确的不可用状态；不得静默退回私有 SharedPreferences、旧文件或 `XSharedPreferences` 并声称设置已生效。
- API 102 支持热重载，但本次迁移不要求自动热重载。由于当前模块有 Hook、缓存、观察器和日志资源，在完成可靠的 `onHotReloading` 清理及 `onHotReloaded` 重建前，将 `autoHotReload` 设为 `false` 或不启用；不得仅为宣称 API 102 而打开热重载。

## 目标架构

### 1. 模块主界面与目标选择

- 在 Manifest 注册正常的 launcher Activity。Activity 属于模块应用自身进程，使用现有 Compose 设置界面和普通 Activity 生命周期。
- 主界面列出两个已支持宿主；点选后打开对应宿主的设置页。所有 Repository/Composable/日志 API 显式传入宿主包名，不再从 `context.packageName` 推断目标宿主。
- 在 README 中将入口说明改为“打开模块应用，在首页选择宿主”。删除通过宿主设置页进入寄生页面的说明。
- 从 `MainModule.kt` 移除只为显示设置页而设置的宿主 Activity Hook；保留震动相关 Hook。检查 `onActivityResult` Hook：若它只服务注入页面的日志导出，改用正常 Activity Result API 后删除。

### 2. Remote Preferences：基础设置

- 每个宿主使用固定且互不冲突的 group，例如 `wetype_settings`、`doubao_settings`。group/file 常量集中定义，禁止在多个文件中拼接不同名称。
- 将现有 `SettingsState` 字段映射为 Remote Preferences 的基本类型键：按下/抬起模式、时长、强度、系统震动索引、主题、调试输出开关。继续维持“主题按宿主分别保存”的当前语义。
- 模块侧由 Application 或单一 Service manager 调用 `XposedServiceHelper.registerListener`，保存当前 `XposedService`；`onServiceDied` 清空已失效引用。UI 只在服务可用时发布设置，服务不可用时明确提示并避免假成功。
- 在 Hook 侧按 `PackageReadyParam.packageName` 选择对应 group，读取默认值并建立 Remote Preferences 变更监听；收到变化后原子替换该进程内 `cachedSettings`。避免多个宿主共用一个可能互相覆盖的缓存。
- 保存默认设置时只更新当前所选宿主的 group。保存结果、当前 Remote 服务状态和目标包名需要在 UI 层保持一致。

### 3. Remote Files：自定义波形与手动导入

- 每个宿主使用独立、固定的单层文件名，例如 `customhaptic_wetype.json` 与 `customhaptic_doubao.json`。Hook 侧通过 `openRemoteFile(name)` 只读解析，不再从宿主外部目录读取新配置。
- 模块侧通过 `XposedService.openRemoteFile(name)` 写文件；打开后先截断旧内容，再完整写入、flush 并关闭，最后更新该宿主 Remote Preferences 中的 `customhaptic_revision`。Hook 侧监听此 revision 并重读波形文件，解析失败时保留上一个有效缓存并写诊断日志。
- 设置页提供导入旧 `config.xml` 和 `customhaptic.json` 的明确操作。用 Storage Access Framework 读取用户选择的文件，校验并映射旧 XML 标签和 JSON 波形字段，预览目标宿主与将应用的字段后再提交 Remote 存储。
- Android 系统通常不允许文件选择器直接浏览其他应用的 `Android/data`。导入说明应写明用户需先将旧文件复制到可访问目录；不能承诺自动扫描宿主目录，也不能在权限失败时伪报导入成功。
- 导入仅写入用户选择的宿主配置，不修改另一个宿主的 group/file。旧 XML 缺少新字段时按现有 `defaultSettings()` 规则补齐。

### 4. 调试日志：单独受限绑定 Service

Remote 目标端接口只读，不能用 Remote Preferences/Files 写入 Hook 进程产生的日志。日志功能通过模块应用自身的显式绑定 Service 传输，不能把它复用为设置配置通道。

- 模块侧在 Manifest 声明显式、导出的绑定 Service；协议只提供追加日志记录所需的最小操作。建议使用 Messenger 或小型 AIDL 接口，所有文件 I/O 放在 Service 的后台执行器，按宿主包名分别写模块私有目录中的日志文件。
- Hook 侧 `DebugLog` 使用显式组件绑定模块包的 Service，发送宿主包名、类别、时间戳和消息。服务端必须校验调用者 UID 对应的已安装包确为受支持宿主，并校验消息类别和长度；不能信任消息体自行声明的包名。若用 Messenger，在 Binder 回调转发到 Handler 前捕获调用 UID，或使用 `Message.sendingUid`；若用 AIDL，在 Binder 调用上下文内校验 `Binder.getCallingUid()`，不得在异步线程中读取已失效的 Binder 调用身份。
- Service 不接受任意路径、文件名或清理/导出命令。清理由模块 UI 在模块私有目录执行；导出由模块 Activity 使用 `ACTION_CREATE_DOCUMENT` 复制日志。对单条日志大小、总文件大小和轮转策略设上限，防止异常日志耗尽存储。
- 绑定失败、模块进程未启动或 IPC 断开时，不得让日志异常影响键盘 Hook；采用有界重试/丢弃策略并记录框架日志。关闭调试开关后停止发送普通调试事件。
- 把 `DebugScreen.kt` 现有 `LocalActivity` 和 `MainModule.requestLogExport` 注入 Activity 回调改为普通 Activity Result API。模块应用直接执行查看/清理/导出。

### 5. API 元数据、构建和混淆

- 更新 `app/build.gradle.kts` 的 API 依赖到 102.0.0，并加入 service 102.0.0。
- `module.prop` 设置 `minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`；`scope.list` 保留两个宿主包名；`java_init.list` 保留唯一的 `com.haostoo.wetypehookr.MainModule`。
- 检查依赖 Manifest 合并结果，确保 libxposed service 的服务接收 Provider/通信组件声明没有被重复声明或错误覆盖。业务配置数据不得经自建 ContentProvider 传输。
- 检查 R8 配置：如发布构建启用混淆，应用 LibXposed 官方 `-adaptresourcefilecontents META-INF/xposed/java_init.list` 和保留入口构造函数规则。当前 `-dontobfuscate` 已存在，不能因此省略对最终 APK 中入口文件与入口类的核查。
- 删除 `app/src/main/assets/xposed_init` 和 `app/src/main/resources/META-INF/yukihookapi_init` 等不再使用的旧入口资源；删除前确认没有对应构建脚本依赖。不要删除标准 `META-INF/xposed` 三个文件。

## 建议实施顺序

1. 先完成 API 102 声明迁移：依赖、`module.prop`、入口资源清理、Manifest 合并检查；确认 Hook 入口仍能由框架加载。
2. 新增设置存储抽象和 Remote Service manager，按目标包名建立 Remote Preferences groups 与波形 Remote Files；先接通 Hook 侧读取/监听。
3. 将现有 `App` 和设置 UI 改为显式接收目标包名，新增主 Activity 与宿主选择页；移除宿主 Activity 劫持入口。
4. 新增手动旧配置导入，支持选择旧 XML/JSON，明确 Android/data 文件需要用户先复制出来。
5. 新增日志绑定 Service 和调用方校验，改造 DebugLog、DebugScreen 的查看/清理/导出路径。
6. 全局检索并清除旧配置/入口假设，更新 README 与模块最低框架要求。

## 验收清单

- launcher 图标打开模块主界面；可以分别进入微信输入法与豆包输入法设置，两个宿主的字段互不串写。
- 已授权并启用 API 102 Remote 能力时，保存基础设置后目标 Hook 进程收到偏好变更并即时生效，不依赖重启或宿主 `FileObserver`。
- 两个宿主分别导入波形文件后，Hook 读取各自 Remote File；波形更新后通过 revision 变更刷新缓存；畸形 JSON 不覆盖有效缓存。
- 旧 XML/JSON 可通过手动导入映射；未选择目标、文件不可读、XML/JSON 无效或 Remote 服务不可用时 UI 提供明确错误，不写部分配置。
- 日志通过受限 Service 从宿主进程写入模块私有目录；未授权 UID 不能伪装目标包写入；模块页可按宿主查看、清理和通过系统文档选择器导出。
- 在不支持 `PROP_CAP_REMOTE` 或 service 未连接的环境，设置页明确显示不可用，Hook 采用明确默认值并记录原因；不得切回 `XSharedPreferences`、自建配置 Provider 或旧宿主配置文件作为隐式后备。
- 最终 APK 的 `META-INF/xposed/java_init.list` 指向唯一有效入口，`module.prop` 和 `scope.list` 内容正确；发布构建下入口未被 R8 删除或改名而未同步。
- Hook 代码中不存在 `de.robv.android.xposed`、`XposedHelpers`、`XC_MethodHook`、`XSharedPreferences`、设置页劫持 Hook、旧 `FileObserver` 配置监听或 YukiHook 初始化残留。
- 变更 README 中 API 最低版本、框架兼容范围、设置入口、旧配置手动迁移办法、波形文件和日志的新存储说明。

## 规范参考

- [LibXposed 102 模块开发指南（用户提供）](https://soclear.github.io/InfoTech/Android/AndroidReverseEngineering/LibXposed102%E6%A8%A1%E5%9D%97%E5%BC%80%E5%8F%91%E6%8C%87%E5%8D%97.html)
- [LibXposed API 官方仓库](https://github.com/libxposed/api)
- [LibXposed API 官方 Javadoc（102）](https://jingmatrix.github.io/libxposed-api/)
- [LibXposed Service 官方仓库](https://github.com/libxposed/service)
- [LSPosed：Develop Xposed Modules Using Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
- [LibXposed 101 中文 API 参考（只作 API 101/通用 API 对照，API 102 行为以官方 Javadoc 为准）](https://nspron.github.io/libXposed-101-Api-Chinese/)

API 102 入口、依赖和混淆规则以 LibXposed 官方仓库/Javadoc 为准；用户提供的社区指南用于 Remote 数据通道实践参考。社区文章中关于 `autoHotReload`、Remote 文件写入的示例不替代官方 API 文档，执行时需以所用 102.0.0 Service 接口签名和能力查询为准。
