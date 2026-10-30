# WeTypeHookR LiquidVivo 风格界面改造指南

本文是交给实现代理的设计与实施指南。产品负责人已确认主导航为 **概览 / 宿主 / 设置** 三项。改造方向是直接采用 LiquidVivo 管理器的应用级页面壳层、导航方式和 Miuix 视觉组织，再把所有页面内容替换为 WeTypeHookR 自己的宿主、震动、迁移和日志功能。

本指南基于当前工作区代码及 LiquidVivo 当前默认分支源码。**只复用通用 UI 架构与外观**；不移植 LiquidVivo 的模块设置、Root、作用域申请、应用管理、Hook 管理或业务逻辑。LibXposed API 102 Remote 数据通道与宿主 Hook 行为继续遵守 [API102_MIGRATION_GUIDE.md](API102_MIGRATION_GUIDE.md)。

## 1. 目标

1. 用 LiquidVivo 风格的单 Activity 管理器壳层替换目前的简单 NavHost + 首页、宿主设置内页签结构。
2. 应用根级主导航固定为“概览 / 宿主 / 设置”，使用 LiquidVivo 的 Pager 与底栏交互模式；宽屏按其自适应逻辑显示侧边导航。
3. 宿主列表与宿主详情分开：点击宿主卡片进入该宿主的设置详情页；详情页显示返回顶栏，不显示根级底栏。
4. 删除当前宿主详情页底部“震动 / 通用 / 关于”三项 NavigationBar。设置分类改为详情页内纵向分组和 Miuix Preference 卡片，不把设置类别放进应用根底栏。
5. 保留并重新组织本项目全部现有功能，包括波形曲线编辑、按宿主设置、旧配置导入、重载宿主、日志查看/清理/导出、帮助和关于。
6. 所有控件采用 Miuix 与项目主题系统；曲线输入/显示、保存、Remote 状态和 API 102 约束见后文。

## 2. 源码核对与当前问题

### 2.1 LiquidVivo 当前可复用的 UI 架构

当前 LiquidVivo 的 `MainActivity` 使用单 Activity 管理器结构：根 Scaffold 承载 NavDisplay；主页面由 `HorizontalPager` 展示；Pager 与应用级导航状态同步；根级 Scaffold 挂载底栏；在适合分栏的宽屏上可换成 `SideRail`。其三个主页面索引为首页、目标应用管理、设置。目标应用详情等二级路由与主页面分开。

它的 Miuix 页面以 `Scaffold + TopAppBar + LazyColumn + Card/Preference` 组织；顶部栏跟随页面滚动行为；导航栏支持普通底栏与浮动底栏样式；页面由统一主题、窗口 Insets、滚动反馈及可选模糊背景协调。可以移植/改造其通用导航与组件代码，但所有包名、文案、状态源、数据模型、业务回调都必须换成 WeTypeHookR 自己的实现。

### 2.2 WeTypeHookR 当前实现基线

- `MainActivity.kt` 是模块 launcher；`App.kt` 使用 Navigation Compose 的 `NavHost`，目前只有“首页选择宿主”和“宿主设置详情”两种路由。
- `HostSelectionScreen.kt` 已显示 Remote 状态、版本和两个宿主入口，但页面壳层较简单，没有独立的概览 / 宿主 / 设置根导航。
- `SettingsScreen.kt` 当前在宿主详情 `Scaffold.bottomBar` 放置“震动 / 通用 / 关于”三项底栏。该底栏层级错误：它把详情页内的内容分类当成了全 App 主导航，也会让进入某个宿主后产生与模块首页不一致的导航语义。按本指南将其移除。
- `SettingsScreen.kt` 的设置状态和 `RemoteConfig` group 按 `hostPackage` 隔离；现有主题模式也是宿主配置字段。UI 重组不得为了填充“设置”主页面擅自把它改成模块级本地偏好。
- `WaveformEditor.kt` 已有按下/抬起波形卡片、波段数、曲线点击、BottomSheet 和下方输入行。保留此功能，按本指南纳入宿主详情页，不重新改回数字表单 Dialog。
- 当前 Miuix UI 依赖为 0.9.0；LiquidVivo 当前 README 描述 Miuix 0.9.3 和 Navigation 3。复制源码前必须对照两边依赖与 API 签名，统一升级一组依赖并验证编译，不得把不同版本组件混在一起。

## 3. 目标信息架构

```text
MainActivity / MainScreen（单 Activity 管理器壳层）
├── 根级导航：概览 | 宿主 | 设置
│   ├── 概览页：模块身份、框架/Remote 状态、重点状态卡与快捷入口
│   ├── 宿主页：微信输入法、豆包输入法宿主卡片
│   └── 设置页：模块级信息、帮助、跨宿主日志管理等
└── 二级路由：宿主设置详情(packageName)
    ├── 震动方式、按下/抬起设置与自定义曲线
    ├── 当前宿主主题与一般设置
    ├── 当前宿主旧配置导入、重载、恢复默认
    └── 当前宿主调试开关及日志入口
```

### 根级底栏规则

- 底栏/浮动底栏只属于根级 `MainScreen`，三项固定为“概览 / 宿主 / 设置”；图标语义、选中动画、模糊/浮动外观、Insets 处理参考 LiquidVivo 的通用底栏实现。
- 三个根页面通过同一 PagerState、同一 selected page 状态与底栏双向同步；点击导航项切换对应页，横向手势切页后选中项跟随更新。返回键从非首个根页回到“概览”。
- 进入宿主设置详情后，显示二级页面自己的 Miuix 顶栏和返回箭头；**隐藏根级底栏**。回退返回原来的根级页面及其滚动/选中状态。
- “震动 / 通用 / 关于”只可作为页面内分组标题、Preference 卡片组或二级内容导航，不得继续占用根底栏。
- 不在每个宿主设置详情中再嵌套第二套底栏，不用三项底栏承载不同层级的目的地。
- 根级主页面允许横向 Pager 切换；宿主详情页、波形编辑页及弹层内容不能加入 Pager 页数。
- LiquidVivo 在特定设备上由底栏改为侧边 Rail。WeTypeHookR 应移植同一类宽屏自适应判断：窄屏使用底栏，宽屏使用 Rail；不要在 Rail 与浮动底栏同时出现。

### 导航页面的内容归属

**概览**

- Miuix 顶栏显示 WeTypeHookR/模块名称。
- 状态卡展示框架服务连接、Remote 能力、配置可写状态；每种状态配有清楚文字和合理图标，不只靠颜色区分。
- 可展示模块版本与简短使用指引/快捷操作。不得展示没有真实状态源支撑的“Hook 生效”“作用域已授权”等文案。
- 这里是状态总览与快捷入口，不重复整页宿主列表。主要宿主配置入口由“宿主”页承担。

**宿主**

- 独立展示全部支持宿主，每个宿主用 Miuix 风格卡片/Preference 行，包含名称、包名或辅助摘要、进入详情的箭头。
- 点选宿主后导航至 `HostSettings(packageName)` 详情路由；传入明确包名并贯穿状态读取、保存、导入、日志筛选和重载操作。禁止从模块 `Context.packageName` 推断目标宿主。
- 宿主页只展示本项目实际支持的 `RemoteConfig.SUPPORTED_PACKAGES`；不要搬入 LiquidVivo 的应用扫描、作用域管理、Root 目标或模块列表逻辑。

**设置**

- 作为模块管理器级设置页，采用 LiquidVivo 风格的顶栏、分组卡片、图标 `startAction`、`ArrowPreference` / `SwitchPreference` 与纵向滚动。
- 只放模块范围的帮助、关于、版本/诊断信息，以及跨宿主日志管理入口等本项目真实存在且具备全局语义的功能。
- 如果一个操作必须指定宿主（导入、重置、重载、单宿主日志/配置），进入操作前应先选择目标宿主，或从“宿主”详情内发起；不可在全局页面悄悄使用上次访问的宿主。
- 当前 `themeMode` 存在 Remote Preferences 的每宿主 group。按本次范围保留当前归属；放在宿主详情的显示/外观相关分组，不迁移到模块级本地配置。模块 App 的全局壳层主题若要全局化，另行提出数据存储变更，不在本 UI 移植任务中暗改。

**宿主详情**

- 顶栏显示宿主名称与返回操作。页面采用一个 Miuix Scaffold、可滚动 LazyColumn/纵向内容和清晰的 Miuix Card 分组。
- 页面按功能分区组织，不使用底部三标签。可采用“震动设置”“按下与抬起”“外观与常规”“配置迁移”“调试日志”等标题；实现者需确保设置项仍能被直接找到，不以过深嵌套替代当前可见功能。
- “震动设置”包含普通模式、系统震动与强度/时长，现有自定义波形入口及曲线编辑界面。
- “外观与常规”保留当前宿主主题模式、恢复默认操作等已有语义。
- “配置迁移”保留导入旧 XML、导入旧波形、重载目标宿主；导入与重置需有用户可理解的目标和确认反馈。
- “调试日志”入口明确绑定当前宿主。日志启用状态、查看/清除/导出继续走现有日志管理实现。
- “关于/帮助”保留在根设置页；若现有 README/帮助只在详情页可访问，也可在宿主页提供快捷入口，但避免复制 LiquidVivo 项目内容。

## 4. LiquidVivo UI 壳层的复用要求

### 应复用/改造的通用部分

- 单 Activity 与统一根主题入口。
- 根级 `MainScreen`、主页面路由、Pager State 与 selected index 同步、弹簧切页交互。
- 根级 Scaffold 挂载 Miuix 底栏/浮动底栏；宽屏侧边 Rail 的自适应策略。
- 通用导航组件（目的地模型、选中状态、图标/标题布局、Insets 和系统导航栏适配）。
- 页面壳层：`Scaffold`、滚动型 Miuix TopAppBar、`LazyColumn`、Card 分组、Preference 行、统一滚动触觉/overscroll。
- LiquidVivo 的 Miuix Theme 包装及可选 blur/浮动底栏效果，仅在依赖兼容、性能可接受且界面可读性合格时复用。
- 页面私有 UI 与跨页面复用组件的拆分方式；状态/数据仍连到 WeTypeHookR 自有 ViewModel/Repository。

### 必须删除/禁止移植的 LiquidVivo 内容

- LiquidVivo 的主题色板/Monet 设置值及存储、浮动底栏配置项、缩放设置、语言项，除非 WeTypeHookR 已有同等业务需求并由现有需求确认。
- Root/超级用户、应用列表、模块列表、作用域申请/撤销、Hook 开关面板、目标版本适配列表、System UI 重启等 LiquidVivo 业务和回调。
- LiquidVivo 自己的 Xposed 状态类、HookEntry、数据模型、权限判断、`Natives`、服务及 repository。
- LiquidVivo 的设置文案、业务图标组合、图标资源、资源 ID、包名和假状态数据。
- 直接把 LiquidVivo 整个仓库/模块覆盖到本项目，或混用其 data/xposed 层与 WeTypeHookR 的 API 102 Remote 数据架构。

LiquidVivo README 声明仓库使用 MIT License。若复制其可识别的源代码/组件而非仅复刻交互与布局，保留相应版权及 MIT 许可证文本，并在项目许可证/第三方声明中记录来源。优先只移植必要的 UI 通用代码，逐个文件检查硬编码业务引用。

## 5. Miuix 与 Compose 约束

- 页面用项目选定版本的 `MiuixTheme`、Miuix `Scaffold`、`TopAppBar`、`Card`、`Text`、`ArrowPreference`、`SwitchPreference`、输入/弹层及图标组件。避免在一个页面里混入 Material 3 的 Scaffold、NavigationBar、Card、Dialog、TextField 和颜色 token。
- 仓库当前仍有 Material3 依赖；重构后先检索真正使用点，再删除未使用依赖，不要在未检查后就移除会影响其他代码的依赖。
- LiquidVivo 当前使用 Miuix KMP 0.9.3、blur 和 Navigation 3；本项目当前 Miuix 0.9.0、Navigation Compose。若采用 Navigation 3 与 Miuix blur，应先统一确认版本、依赖坐标、编译器/Compose BOM 和 API 签名；所有 Miuix artifact 成组升级，禁止 0.9.0/0.9.3 混搭。
- 可移植 LiquidVivo 的页面模式与组件实现，但只加实现实际需要的模块；主题、模糊和悬浮外观要遵从 Miuix token，不能直接硬编码 LiquidVivo 颜色/尺寸覆盖动态主题。
- `MiuixScrollBehavior` 的 nested scroll 只挂在真实 TopAppBar 对应的可滚动列表；不要让自定义顶栏错误消费列表滚动。窗口 Insets、系统栏透明度、navigation bar contrast、底栏安全区域和 display cutout 在窄屏/宽屏均应检查。
- Miuix Overlay（含波形二级 `OverlayBottomSheet`）必须挂在有效的 Miuix PopupHost/Scaffold 下。若进入宿主设置详情后隐藏根页面，Overlay 必须使用详情页仍存在的 Scaffold host。
- 界面须适配深浅主题、横竖屏、窄屏、大字体和屏幕阅读器；曲线 Canvas 必须提供可访问的文本/输入替代操作。

## 6. 自定义震动曲线在新页面中的行为

本次页面壳层调整应保留现有 `WaveformEditor.kt` 的曲线编辑功能，并将其作为宿主详情内的编辑内容/二级页面打开，而不是改为底栏页签或恢复旧注入式 Activity。

- 按下与抬起分别一张 Miuix Card。顺序为：标题与说明 → 有效波段数输入 → 拟合曲线 → 索引输入行。数量范围继续为 2–8。
- 每张卡片展示的输入行数必须等于当前有效波段数；时长 0–500 ms、振幅 0–255；索引 0 时长固定 0。点击有效曲线点显示对应波段的 Miuix `OverlayBottomSheet` 二级编辑，值立即同步到曲线和下方行。
- 曲线采用平滑且不越过相邻振幅上下界的拟合显示（优先 monotone cubic Hermite/PCHIP）；曲线仅用于预览。Remote JSON 和 `HapticEngine` 继续使用原始离散 `timings` 与 `amplitudes`，不得由曲线采样值反写数据。
- x 轴根据波段累计时间形成；重复时间点、零时长和所有点重叠必须安全绘制并保持原数组索引映射。首项 0 时长不允许为绘图修改成非零。
- 数量编辑使用受控文本状态，允许输入中的短暂空值；提交/失焦时校验范围，非法时提示并阻止保存。解决当前 `TextFieldState` 与外部 value 同步问题。
- 本次现有默认值继续保留：按下 `timings=[0,40,10,10]`、`amplitudes=[0,250,0,100]`；抬起 `timings=[0,40,10,10]`、`amplitudes=[0,180,0,90]`。无旧值时首次编辑显示四段默认值，取消不持久化。
- 波段数量在当前草稿中缩小后再次扩大，应恢复仍在缓冲区中的值；超过旧缓冲长度时使用确定性插值生成新波段值，避免全零填充。最终只保存有效前缀。
- 保存前校验数量、数组等长、首时长 0、时长和振幅范围；Remote 服务不可用、存储失败时不得假报成功，保留草稿并显示错误。写完并关闭 Remote File 后再更新该宿主 revision。

## 7. API 102 与既有行为边界

- 继续只支持 LibXposed API 102 Remote；`compileOnly api:102.0.0`、`implementation service:102.0.0` 及入口/manifest 等规范按照项目的 API102 迁移指南执行。
- 模块管理 UI 通过现有 `RemoteService`/Repository 读写 Remote Preferences 与 Remote Files。不要让 Composable 直接拼接 group/key/file 名称。
- Hook 侧保持只读 Remote 访问；界面改造不得加入 `XSharedPreferences`、自建 ContentProvider、共享文件或宿主外部目录作为设置同步后备。
- 旧 XML/JSON 仍为用户选择文件的手动迁移；日志仍通过当前受限绑定 Service；按宿主筛选和 UID 校验语义不改。
- 修改导航后要确认波形保存、导入、重置、重载、日志导出均保留目标 packageName。多个宿主不能因 Pager/ViewModel 复用而串写配置。
- 若新 UI 框架要调整 Activity/Application/生命周期实现，保持 `MainModule` Hook 入口与模块 launcher Activity 的职责隔离；不能由 UI 路由或主题初始化触发目标 Hook 逻辑。

## 8. 建议文件与改造顺序

允许实现代理按现有目录调整，但应维持以下职责清晰：

1. **依赖核对**：检查 LiquidVivo 当前 `MainActivity.kt`、`BottomBar.kt`、`BottomBarMiuix.kt`、home/settings Miuix 页面和仓库 README；对照本项目 Compose/Miuix 版本，形成依赖升级清单。
2. **根导航骨架**：重构 `MainActivity.kt` / `App.kt` 成单 Activity + LiquidVivo 风格 MainScreen；引入概览、宿主、设置三页根路由和根 Pager；底栏只放此三页。
3. **目标详情路由**：宿主卡片进入 `HostSettings(packageName)`，详情页不带根底栏，使用返回顶栏；保存原根 Pager 状态和选中页。
4. **底栏组件**：移植或重写 LiquidVivo 的一般化底栏、选中动画、宽屏 Rail 和 Insets 逻辑；清除 Module/Root/SuperUser 特有 badge/动作。
5. **页面内容迁移**：把 Remote/版本状态整理到概览；宿主卡片放宿主页；将原 VibrationTab/GeneralTab 内容按归属整合进宿主详情；将帮助/关于与跨宿主管理入口放根设置页。
6. **移除旧错误导航**：删除 `SettingsScreen` 详情页的 NavigationBar 与 `震动/通用/关于` selectedTab 状态分屏；这些内容依 Miuix 卡片/分组呈现。
7. **波形与 Remote 接线**：保持曲线、二级 BottomSheet、草稿、验证与保存语义；验证新 Activity/NavDisplay 生命周期不丢宿主目标和编辑状态。
8. **收尾与视觉核查**：清理 LiquidVivo 业务代码及未使用依赖，整理字符串资源与许可证声明；运行必要构建，并在目标设备/模拟器检查窄屏、宽屏、深浅色、底栏/侧栏、嵌套返回、Remote 断开、曲线编辑和日志导出。

不要为便于复制而整体复制 LiquidVivo UI 目录后留下死代码、旧业务逻辑、未用资源、原项目包名，或引入多套导航状态。完成后应能从代码中明确辨认“通用壳层”和“WeTypeHookR 页面/数据”。

## 9. 验收清单

- [ ] launcher 打开一个统一管理器 Activity，根级显示 LiquidVivo 风格三目的地导航“概览 / 宿主 / 设置”。
- [ ] 根底栏和横向 Pager 选中状态双向同步；Android 返回从非首根页回到概览。
- [ ] 宽屏导航按 LiquidVivo 模式转为 SideRail；不会同时显示底栏与 Rail。
- [ ] 概览只展示本项目真实模块/框架/Remote 状态和必要快捷入口。
- [ ] 宿主页有两个目标宿主卡片；选中后进入对应详情，目标包名随路由稳定传递。
- [ ] 宿主详情页有返回顶栏且隐藏根底栏；现有“震动 / 通用 / 关于”不再构成详情页底部导航。
- [ ] 所有现有项目功能仍可到达：模式与强度、系统震动、自定义曲线、按宿主主题、重置、重载、手动导入、日志开关/查看/清理/导出、帮助/关于。
- [ ] 波形卡片顶部有有效波段数，下方有与数量一致的字段；曲线点选打开正确的 Miuix 二级编辑面板；默认值与当前 `RemoteConfig` 一致。
- [ ] 新壳层没有 LiquidVivo Root/作用域/应用列表/主题色板等业务项、硬编码状态或无效按钮。
- [ ] UI 的 Scaffold、TopAppBar、Card、Preference、NavigationBar/Rail、Popup 使用一致的 Miuix 组件与主题；底栏 Insets/系统栏/大字体布局正常。
- [ ] API 102 Remote 配置、Remote revision、日志 Service 调用方校验和手动导入边界没有被破坏；不存在 `XSharedPreferences`。
- [ ] 若复制 LiquidVivo 源码，保留 MIT 版权与许可证声明，并清除所有指向 LiquidVivo 业务层的引用。

## 10. 参考资料

- [LiquidVivo 仓库](https://github.com/Sleep-jay/LiquidVivo)
- [LiquidVivo 当前 MainActivity / 根导航与 Pager](https://github.com/Sleep-jay/LiquidVivo/blob/master/LiquidVivo/app/src/main/java/com/LiquidVivo/ui/MainActivity.kt)
- [LiquidVivo 当前底栏实现](https://github.com/Sleep-jay/LiquidVivo/blob/master/LiquidVivo/app/src/main/java/com/LiquidVivo/ui/component/bottombar/BottomBarMiuix.kt)
- [LiquidVivo 当前 Miuix 首页](https://github.com/Sleep-jay/LiquidVivo/blob/master/LiquidVivo/app/src/main/java/com/LiquidVivo/ui/screen/home/HomeMiuix.kt)
- [LiquidVivo 当前 Miuix 设置页](https://github.com/Sleep-jay/LiquidVivo/blob/master/LiquidVivo/app/src/main/java/com/LiquidVivo/ui/screen/settings/SettingsMiuix.kt)
- [LiquidVivo 当前 README（架构、依赖与页面规则）](https://github.com/Sleep-jay/LiquidVivo/blob/master/LiquidVivo/README.md)
- [Miuix 中文组件总览](https://compose-miuix-ui.github.io/miuix/zh_CN/components/)
- [Miuix Scaffold](https://compose-miuix-ui.github.io/miuix/zh_CN/components/scaffold)、[Card](https://compose-miuix-ui.github.io/miuix/zh_CN/components/card)、[TextField](https://compose-miuix-ui.github.io/miuix/zh_CN/components/textfield)、[OverlayBottomSheet](https://compose-miuix-ui.github.io/miuix/zh_CN/components/overlaybottomsheet)
- [LibXposed API 102 迁移指南（本项目）](API102_MIGRATION_GUIDE.md)、[LibXposed API](https://github.com/libxposed/api)、[LibXposed Service](https://github.com/libxposed/service)、[API 102 Javadoc](https://jingmatrix.github.io/libxposed-api/)
