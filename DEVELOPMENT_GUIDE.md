# WeTypeHookR（WeDouVibrator）开发文档

> **文档定位**：目标架构与规范参考（v1.4）。实施步骤、阶段闸门、文件清单见 `TASK_HOST_SETTINGS_MIGRATION.md`。
> **状态说明**：仓库当前代码为 `1.3.0`，本文描述改造完成后的形态；两者不一致处，一律以任务书为准执行。
> **技术底座**：LibXposed API 102 + YukiHook 1.5.0 + Miuix 0.9.3 + Compose。
> 历史交接类文档（`HANDOFF.md`、`API102_MIGRATION_GUIDE.md`、`HOST_INJECTED_SETTINGS_ACTIVITY_GUIDE.md`）仅作背景，不再作为设计依据。

---

## 1. 模块定位

一个 Xposed 模块，为微信输入法（`com.tencent.wetype`）与豆包输入法（`com.bytedance.android.doubaoime`）补齐可自定义的触感反馈。模块**没有桌面入口**，设置界面运行在输入法进程内，由输入法设置页的浮动按钮唤起。

| 项 | 值 |
| --- | --- |
| 应用名 / 模块名 | WeDouVibrator |
| 包名 | `com.haostoo.wetypehookr` |
| 目标宿主 | 微信输入法、豆包输入法（静态作用域） |
| 框架要求 | LibXposed API 102（LSPosed 1.9.3+） |
| 系统要求 | Android 13（API 33）及以上 |
| 仓库 | https://github.com/Hao-o0o/WeTypeHookR |

功能：

1. 拦截输入法根 View 的触摸事件，在按下 / 抬起（含多指）时触发震动。
2. 四种模式：无震动、系统预设风格（20 种 `HapticFeedbackConstants`）、简单自定义（时长 + 强度）、自定义波形（2–8 段）。
3. 配置按宿主隔离，存于宿主数据目录，改动即时生效。
4. 宿主内设置页：注入状态、按下/抬起设置、波形编辑器、模块设置、调试与日志、帮助与关于。
5. 宿主设置页注入可拖动 FAB，作为唯一入口。

---

## 2. 项目结构

```
WeTypeHookR/
├── app/
│   ├── build.gradle.kts                # 依赖 / KSP / 资源包 ID / 签名 / ABI 分包
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml          # 无 Activity / Service；仅 application + queries
│       ├── java/com/haostoo/wetypehookr/
│       │   ├── HookEntry.kt             # ★ YukiHook 入口（注解 + encase/loadApp）
│       │   ├── HostCatalog.kt           # 宿主包名 / 显示名 / proxy / 版本查询
│       │   ├── HapticEngine.kt          # ★ 震动执行 + 进程内缓存（无框架依赖）
│       │   ├── HapticUtils.kt           # 系统震动常量映射
│       │   ├── SettingsModel.kt         # SettingsState / 波形模型与校验
│       │   ├── SettingsStore.kt         # ★ 宿主目录读写 + 原子写 + FileObserver
│       │   ├── HostPaths.kt             # 宿主目录路径
│       │   ├── HookRuntime.kt           # 进程内注入状态
│       │   ├── BaseHostActivity.kt      # AppCompatActivity + ModuleActivity
│       │   ├── SettingsActivity.kt      # ★ 宿主内设置页
│       │   ├── SettingsScreen.kt        # 设置内容（复用现有卡片）
│       │   ├── SettingsTopBar.kt        # 标题 + 宿主副标题 + 切换入口
│       │   ├── WaveformEditor.kt        # 波形编辑器 + 草稿模型
│       │   ├── WaveformCurve.kt         # PCHIP 曲线绘制
│       │   ├── ModuleSettingsPage.kt    # 颜色模式 / 导入导出 / 调试 / 帮助 / 关于
│       │   ├── HostFabInjector.kt       # FAB 注入与拖动吸附
│       │   ├── DebugLog.kt              # 直接写宿主日志目录
│       │   ├── LogStore.kt              # 日志查看 / 清理 / 打包
│       │   ├── ConfigTransfer.kt        # 单宿主 JSON 导入导出
│       │   └── LegacyConfigImporter.kt  # 旧 config.xml 解析（迁移用）
│       └── res/
│           ├── values/strings.xml        # ★ 中文文案（唯一文案来源）
│           ├── values-en/strings.xml     # 英文文案
│           ├── values/arrays.xml         # 模式 / 系统风格 / 颜色模式列表
│           ├── values/themes.xml         # Theme.WeTypeHookR（AppCompat 无 ActionBar）
│           └── drawable-nodpi/sponsor.png
├── settings.gradle.kts / build.gradle.kts
├── gradle.properties                    # ⚠ 未入库（含 AndroidX 开关与签名）
└── TASK_HOST_SETTINGS_MIGRATION.md      # 施工文档
```

关键分层：

- `HookEntry` 只做装配（装钩子、挂配置监听、注册 Activity、注入 FAB），不含业务逻辑。
- `HapticEngine` / `SettingsModel` / `HostPaths` **不得引用任何框架类型**，保证可被 UI 与 Hook 共用。
- UI 只通过 `SettingsStore` 读写配置，不直接碰文件或缓存。

---

## 3. 技术栈与依赖

| 依赖 | 作用 | 说明 |
| --- | --- | --- |
| `io.github.libxposed:api:102.0.0` | 框架 API | **compileOnly**，不打包 |
| `com.highcapable.yukihookapi:yukihook-core:1.5.0` | Hook DSL、宿主生命周期、寄生 Activity、资源注入 | implementation |
| `com.highcapable.yukihookapi:yukihook-runtime-libxposed:1.5.0` | LibXposed API 底座 | implementation，一个变体只能有一个底座 |
| `com.highcapable.yukihookapi:yukihook-compiler:1.5.0` | KSP 编译器，生成入口与 `META-INF/xposed` | ksp |
| `androidx.appcompat:appcompat:1.7.1` | `ModuleActivity` 需要的 AppCompat 宿主 | implementation |
| `miuix-ui / preference / icons:0.9.3` | UI 组件 | implementation |
| `compose-bom` + `material3` | Compose 基础 | implementation |
| `compose-markdown:0.5.4` | 帮助文档渲染 | implementation |

工具链：AGP `9.3.2`、Kotlin `2.4.10`、Gradle `9.5.0`、JDK 21、compileSdk 37 / targetSdk 35 / minSdk 33。

`yukihook-core` 会带入 `kotlin-stdlib 2.4.21`（与 2.4.10 同属 2.4 线，Gradle 取高版本）。若出现元数据版本不兼容，先把 Kotlin 对齐到 2.4.21。

两个构建期约束：

```kotlin
// 1) 模块资源注入宿主，必须换资源包 ID（不能是 0x7f）
androidResources.additionalParameters +=
    listOf("--allow-reserved-package-id", "--package-id", "0x64")

// 2) 入口由 KSP 生成，源码里不能存在手写 META-INF/xposed
```

---

## 4. 进程与数据流

```
宿主进程（输入法）
├─ HookEntry
│   ├─ installTouchHooks()                触摸 → HapticEngine.dispatch
│   ├─ SettingsStore.attach()             读文件 → 缓存 + FileObserver
│   ├─ withApplicationReady { registerModuleActivities(proxy) }
│   └─ HostFabInjector.install()          FAB → startActivity(SettingsActivity)
├─ SettingsActivity（模块类，宿主进程）
│   └─ SettingsContent → SettingsStore 读写宿主目录
└─ 数据：<host>/files/haostoo/{config/*.json, logs/*.log}
```

- 只有一个进程、一套状态：UI 与 Hook 通过同一批文件与同一个内存缓存协作。
- Hook 侧只读缓存，不在触摸回调中做 IO。
- UI 写入文件即完成"提交"，Hook 由 `FileObserver` 在 200ms 内感知。

---

## 5. 入口与钩子

### 5.1 入口注解

```kotlin
@YukiHookLibXposedEntry(
    targetApiVersion = 102,          // 必填，≥ minApiVersion
    scope = ["com.tencent.wetype", "com.bytedance.android.doubaoime"],
    staticScope = true,
    hotReload = YukiHookLibXposedEntry.HotReload.NONE,
)
object HookEntry : YukiHookXposedModule {
    override fun onHook() = encase { /* loadApp(...) */ }
}
```

| 参数 | 含义 |
| --- | --- |
| `targetApiVersion` | 目标 API，本项目 102 |
| `minApiVersion` | 默认 101，不可低于 101 |
| `scope` / `staticScope` | 推荐作用域 / 是否锁定为静态 |
| `hotReload` | `NONE` / `MANUAL` / `AUTO`，本项目 `NONE` |
| `entryClassName` | 生成的入口类名（默认由注解类推导） |
| `javaEntries` / `nativeEntries` | 附加的原生入口（本项目不用） |

KSP 会生成 `META-INF/xposed/{java_init.list,module.prop,scope.list}`。**源码中手写这三个文件会导致编译失败**。模块名与描述来自 Manifest 的 `android:label` / `android:description`。

### 5.2 装配

```kotlin
override fun onHook() = encase {
    HostCatalog.ALL.forEach { host ->
        loadApp(host) {
            installTouchHooks()
            SettingsStore.attach(appContext, host)
            withApplicationReady { app -> app.registerModuleActivities(HostCatalog.proxyFor(host)) }
            HostFabInjector.install(this)
        }
    }
}
```

- `encase`：模块入口的执行容器；`loadApp(pkg)`：按包名限定，回调内 `this` 是 `PackageParam`。
- `appContext`：宿主 `Application`（可直接当 Context 用）；`packageName`：当前宿主包名。
- `withApplicationReady`：宿主 `Application` 就绪后回调，注册 Activity 必须在此之后。

### 5.3 钩子写法

```kotlin
"com.tencent.wetype.plugin.hld.view.ImeRootView".toClass()
    .method { name = "dispatchTouchEvent"; param(MotionEvent::class) }
    .hook {
        before {
            val view = instance as? View ?: return@before
            val event = args[0] as? MotionEvent ?: return@before
            HapticEngine.dispatch(view, event)
        }
    }
```

约束：

- 只做副作用，不改参数、不吞返回值；用 `before` 即可。
- 回调里只读 `HapticEngine.cachedSettings`（不可变快照），不做 IO / 加锁 / 反射查找。
- 宿主版本改动导致类名失效时，`runCatching` 兜底并记日志，不能让输入法崩溃。
- 触摸目标：微信 `ImeRootView#dispatchTouchEvent`，豆包 `KeyboardView#onTouchEvent`；事件类型 `ACTION_DOWN / UP / POINTER_DOWN / POINTER_UP`。

### 5.4 底层 API 102 能力（YukiHook 之下）

了解即可，日常用 DSL；需要时可直接调用：

| 能力 | 接口 |
| --- | --- |
| 挂钩 | `hook(Executable): HookBuilder`；`setPriority` / `setExceptionMode` / `setId` / `intercept(Hooker)` |
| 挂钩句柄 | `HookHandle.unhook()` / `replaceHook(Hooker)` / `getId()` |
| 拦截链 | `Chain.getThisObject()` / `getArgs()` / `getArg(i)` / `proceed()` / `proceed(args)` / `proceedWith(result)` |
| 类初始化 | `hookClassInitializer(Class)` |
| 去内联 | `deoptimize(Executable)` |
| 框架信息 | `getFrameworkName()` / `getFrameworkVersion()` / `getFrameworkProperties()` |
| 能力位 | `PROP_CAP_REMOTE` / `PROP_CAP_SYSTEM` / `PROP_RT_API_PROTECTION` |

生命周期回调（`registerFrameworkEvents`）：`onModuleLoaded`（`processName` 在此）→ `onPackageLoaded` → `onPackageReady`，以及 102 新增的 `onHotReloading` / `onHotReloaded`。本项目通过 YukiHook 的 `loadApp` 获取等价时机，不再手写这些回调。

API 102 行为约束：模块不得调用 `de.robv.android.xposed` 遗留 API；101 起不再注入 zygote，只在作用域进程加载。

---

## 6. 宿主内 Activity

### 6.1 机制

`registerModuleActivities(proxy)` 让未在宿主 Manifest 声明的模块 Activity 可以在宿主进程启动。它的做法是：在 `startActivity` 提交给系统之前把 Intent 的目标换成宿主已声明的 `proxy`，并在启动阶段换回模块 Activity、注入模块资源。对使用者的表现就是普通的 `startActivity(Intent(ctx, SettingsActivity::class.java))`。

由此得到三个直接好处：

1. Activity 的 `Context`、`packageName`、`filesDir` 都是宿主的 → 配置可直接写宿主目录。
2. 模块资源（`R.string` / `R.drawable` / `R.style`）在页面内可用 → 文案可走标准资源。
3. 不需要跨进程启动凭证、AIDL 或导出组件。

### 6.2 `ModuleActivity` 契约

| 成员 | 作用 | 是否必须 |
| --- | --- | --- |
| `delegate.onCreate(savedInstanceState)` | 应用 `moduleTheme` | 必须，在 `super.onCreate` 之前 |
| `delegate.onConfigurationChanged(newConfig)` | 重新注入模块资源 | 必须 |
| `delegate.onRestoreInstanceState(bundle)` | 修正 `viewHierarchyState` 的 ClassLoader | 必须 |
| `getClassLoader()` | 返回模块 ClassLoader | 必须 |
| `moduleTheme` | 模块的 AppCompat 主题资源 id | 必须（`-1` 会退回宿主主题） |
| `proxyClassName` | 单独指定 proxy，空则用注册时的全局值 | 可选 |

### 6.3 proxy

必须是宿主 Manifest 中真实存在、`launchMode = standard`、无 ActionBar、启动无副作用的 Activity。默认用两个设置页：

| 宿主 | proxy |
| --- | --- |
| 微信输入法 | `com.tencent.wetype.plugin.hld.reactnative.activity.ImeMainSettingActivity` |
| 豆包输入法 | `com.bytedance.android.doubaoime.activity.SettingsActivityNext` |

`launchMode` 若不是 `standard`，二次打开可能只触发 `onNewIntent`；此时换一个候选并在 `HOST_NOTES.md` 记录。

### 6.4 资源

- 资源包 ID 必须改为 `0x64`（或其他未占用值），避免与宿主 ID 冲突。
- 资源注入由框架在 `callActivityOnCreate` 前完成，页面里直接 `stringResource(...)` / `painterResource(...)` 即可。
- 不要在宿主进程用 `createPackageContext(模块包)` 取资源：宿主对模块包没有可见性声明，会被拒。

### 6.5 限制

- 关闭（`finish()`）即返回输入法设置页，不自定义转场。
- 任务栈会多一条记录，Recent 中显示为输入法自身，可接受。
- 热重载要求模块 Activity 已销毁；本项目 `hotReload = NONE`，不涉及。

---

## 7. 配置契约

### 7.1 位置

```
<host>/files/haostoo/config/settings.json    # 设置 + revision
<host>/files/haostoo/config/haptic.json      # 波形
<host>/files/haostoo/config/ui.json          # FAB 位置 + 颜色模式
<host>/files/haostoo/logs/<category>.log     # 日志
```

优先 `getExternalFilesDir(null)`（`/sdcard/Android/data/<host>/files/haostoo/`），为空时回退 `filesDir`。路径只允许由 `HostPaths` 提供。

### 7.2 字段

| 键 | 类型 | 含义 |
| --- | --- | --- |
| `schema` | int | 当前 2 |
| `revision` / `updated_at` | long | 提交版本 / 时间戳 |
| `down_mode` / `up_mode` | int | 0 无 / 1 系统预设 / 2 简单自定义 / 3 自定义波形 |
| `down_system` / `up_system` | int | 系统预设下标 0–19 |
| `down_duration` / `up_duration` | float | 毫秒（10–200） |
| `down_strength` / `up_strength` | float | 0–1 |
| `theme_mode` | int | 历史字段，保留兼容 |
| `debug_log` | bool | 调试日志开关 |

`ui.json`：`fab_x` / `fab_y`（0–1 归一化）、`color_mode`（0 浅 / 1 深 / 2 跟随）。

### 7.3 波形规则

- 段数 2–8；`timings` 与 `amplitudes` 等长；
- `timings[0]` 必须为 `0`（编辑器只读）；
- 时长 0–500ms，强度 0–255；
- 至少一段时长 > 0；
- 非法 JSON 不覆盖上一份有效缓存。

### 7.4 写入与热更新

写入顺序：临时文件 → `fd.sync()` → `rename`；先 `haptic.json` 后 `settings.json`。

`FileObserver(CLOSE_WRITE or MOVED_TO)` + 200ms 去抖 + `revision` 比对 → 重读 → 原子替换内存缓存。UI 保存后不直接改缓存。

首次运行若无 `settings.json`，从旧 `config/config.xml` 迁移一次，失败则用默认值。

---

## 8. UI 规范（Miuix）

### 8.1 主题

- `MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme())`，`dark` 由 `ui.json` 的 `color_mode` 决定，与系统深色解耦。
- XML 主题 `Theme.WeTypeHookR` 只负责窗口（AppCompat、无 ActionBar、透明背景）。
- 颜色只用 `MiuixTheme.colorScheme.*`：`surface`、`surfaceContainer`、`primary`、`onSurface`、`onSurfaceContainerVariant`、`secondaryContainer`、`dividerLine`。
- 文本样式用 `MiuixTheme.textStyles.*`（`headline2`、`body1/body2`、`footnote1`、`subtitle`）。

### 8.2 结构与弹层

- 页面只有一个 `Scaffold`；`OverlayDialog` / `OverlayBottomSheet` / `OverlayDropdownPreference` 必须在其子树内，否则不渲染。
- 弹层由 `show` 参数驱动，组件常驻组合树以保留关闭动画。
- 二级页（波形编辑、模块设置）在同一 `Scaffold` 内用 `AnimatedContent` 切换，不要新建 `Scaffold`，否则二级弹层找不到 popup host。

### 8.3 组件

| 场景 | 组件 |
| --- | --- |
| 可点设置项 | `ArrowPreference(title, summary, startAction, onClick)` |
| 开关 | `SwitchPreference` |
| 枚举选择 | `OverlayDropdownPreference` |
| 确认弹窗 | `OverlayDialog` |
| 底部调节面板 | `OverlayBottomSheet` |
| 卡片 | `Card(insideMargin = PaddingValues(0.dp))` 包 preference |
| 数字输入 | `TextField(state = TextFieldState, label = ...)` |
| 滑杆 | `Slider(value, onValueChange, valueRange)` |
| 图标 | `Icon(MiuixIcons.X, contentDescription)` |

约定：卡片间距 12dp、页面水平内边距 12dp、设置项图标 `startAction` 统一 `padding(end = 12.dp)` + `primary` 着色。

### 8.4 顶栏

miuix `TopAppBar` 提供 `title` / `subtitle`，但 `title` 是 `String`，无法挂点击。宿主名需要在标题下方显示且整块可点，因此使用自写 `SettingsTopBar`（`Surface` + `Row` + 可点击 `Column`，内含主标题、副标题、指示箭头），高度 56dp，顶部 `statusBarsPadding()`。

### 8.5 波形曲线

- `Canvas` 绘制，PCHIP 单调三次插值，保证不过冲。
- x 轴为累计时长（总时长为 0 时退化为等距），y 为 `1 - 振幅/255`。
- 节点点击命中半径 22dp，点击打开该段编辑面板。
- 曲线仅作预览，实际震动用离散 `timings/amplitudes`。

---

## 9. 构建与调试

### 9.1 构建

仓库无 `gradlew`，使用本地 Gradle 9.5.0：

```bash
/c/Users/huang/.gradle/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat \
  -p 'D:/Users/huang/IdeaProjects/WeTypeHookR' :app:assembleRelease --console=plain
```

产物：`app/build/outputs/apk/release/app-universal-release.apk`（另有 `arm64-v8a` / `armeabi-v7a`）。

注意：

- `gradle.properties` 未入库，含 `android.useAndroidX`、JVM 参数、签名四项与 `android.suppressUnsupportedCompileSdk=37`；新环境必须补齐，否则构建失败。
- KSP 版本需与 Kotlin 工具链匹配；升级 Kotlin 时同步升级 KSP 与 YukiHook。

### 9.2 日志

| tag | 来源 |
| --- | --- |
| `WETYPE_HOOK` | 钩子安装 / 配置更新 |
| `WETYPE_UI` | 设置页生命周期与宿主识别 |
| `WETYPE_CFG` | 配置读写与迁移 |
| `WETYPE_FAB` | 入口按钮注入与跳转 |

```bash
adb logcat -s YukiHook WETYPE_HOOK WETYPE_UI WETYPE_CFG WETYPE_FAB
```

模块内日志：`<host>/files/haostoo/logs/*.log`，由设置页开启后写入。

### 9.3 排查

| 现象 | 先看 |
| --- | --- |
| 桌面还有图标 | Manifest 是否还留着 `<activity>` |
| LSPosed 不显示模块 | APK 内 `META-INF/xposed/module.prop` 是否生成、`java_init.list` 是否指向生成入口 |
| 点 FAB 没反应 | `WETYPE_FAB` 日志；proxy 是否存在且 `standard`；`registerModuleActivities` 是否在 `withApplicationReady` 内调用 |
| 页面起来但白屏/崩溃 | `WETYPE_UI` 日志；`moduleTheme` 是否为 AppCompat 主题；资源包 ID 是否冲突 |
| 文字显示成资源 id 或缺失 | 资源包 ID / 注入是否成功；是否误用了宿主的 `R` |
| 改设置不生效 | `WETYPE_CFG` 是否收到文件事件；`revision` 是否变化 |
| 打字无震动 | 钩子是否装上（`WETYPE_HOOK`）；是否被 `runCatching` 吞掉异常后未记录 |

---

## 10. 红线

1. `io.github.libxposed:api` 必须 `compileOnly`；`HapticEngine` / `SettingsModel` / `HostPaths` 不得引用框架类型。
2. 不得手写 `META-INF/xposed/*`；入口与元数据由 KSP 生成。
3. 资源包 ID 不得为 `0x7f`。
4. 设置页必须是 `ModuleActivity` 子类，且三个生命周期方法与 `getClassLoader()` 都委托给 `delegate`。
5. 配置只有一个权威源：宿主目录文件。不得引入 SharedPreferences、跨进程通道或其他副本。
6. Hook 回调不做 IO、不加锁、不反射查找。
7. 弹层只用 miuix `Overlay*`，且必须在 `Scaffold` 子树内。
8. 文案只在 `res/values*/strings.xml` 与 `arrays.xml`；`.kt` 中不出现中文字面量。
9. 不开启热重载。
10. 不恢复 launcher Activity；合并 `master` 需用户批准。
11. 设备操作边界：不擅自重启手机、不改 LSPosed 数据库、不杀系统进程。

---

## 11. 已知限制

| 项 | 说明 |
| --- | --- |
| 入口唯一 | FAB 注入失败则无法进入设置，只能等宿主适配；失败时有 Toast 提示 |
| 任务栈 | 宿主内 Activity 会多一条 Recent 记录，显示为输入法自身 |
| 跨宿主切换 | 不同应用的数据目录不可互访，切到另一宿主通常为只读态 |
| 热重载 | 关闭；更新模块需重载输入法 |
| 微信输入法 3.5.3+ | 简单自定义 / 自定义波形震感偏弱，属系统侧限制，建议用系统预设 |
| 资源 ID | 与其他模块共用 `0x64` 会冲突，需要时改段 |
