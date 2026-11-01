# MCServerLauncher v0.1 beta — 实现方案

> 版本号锁定 `v0.1 beta`。本文件是 v0.1 beta 功能清单的实现依据与阶段划分。
> 参考来源：ZalithLauncher2（ZL2，Kotlin/Compose）、FoldCraftLauncher（FCL，Java）。

## 1. 参考源码侦察结论

两份参考仓库都很大，不适合整仓克隆（ZL2 ≈ 888 MB，FCL ≈ 1.5 GB），
因此通过 GitHub Trees API 列举文件树 + `raw.githubusercontent.com` 按需拉取关键文件。

### 1.1 ZL2 技术形态

| 项 | 结论 |
| --- | --- |
| UI 框架 | **Jetpack Compose + Material3**（`ui/screens/**`、Nav3、`NavKey`） |
| 语言 | Kotlin，JVM 17 |
| 主题 | Material3 动态主题 + 7 套内置色板（见 §2） |
| 自有组件库 | `CardGrid`、`ColorPicker`、`Guide`、`InputMap`、`LayerController`、`PetLib` |
| 联机 | 内置 `Terracotta` 模块（`net.burningtnt.terracotta` + `jniLibs/*/libterracotta.so`） |
| NBT | 依赖 `opennbt-1.6`（`ZalithLauncher/libs/`） |

### 1.2 ZL2 模块 → 本项目功能映射

| ZL2 文件/模块 | 对应功能清单条目 |
| --- | --- |
| `game/download/assets/platform/{modrinth,curseforge}/**` | 15 资源中心（MR/CF） |
| `game/download/modpack/platform/**` + `ModrinthPackParser` / `CurseForgePackParser` | 16 整合包安装 |
| `game/version/export/platform/{ModrinthPackExporter,CurseForgePackExporter}` | 23 导出（mrpack/zip） |
| `game/version/resource_pack/**` + `ResourcePackManageScreen` | 24 资源包管理 |
| `utils/nbt/NBTUtils.kt`（opennbt） | 22 世界管理 NBT 编辑器 |
| `terracotta/**` + `Terracotta` 模块 | 13 联机 |
| `ColorPicker` 模块 | 20 取色器 |
| `Guide` 模块 | 18 使用教程 / 原型引导 |
| `CardGrid` 模块 | 服务端核心选择页卡片布局 |
| `ui/theme/**` | 14 个性化主题、20 背景 |
| `components/Unpack*Task.kt` + `components/jre/**` | 4 Java 运行时管理 |
| `ui/screens/content/home/ActionMenuDrag.kt` | 1 拖拽重排序 |

### 1.3 CurseForge API Key 结论（重点）

**ZL2 没有在仓库里提交任何 CF key**。`ZalithLauncher/build.gradle.kts` 的逻辑是：

```kotlin
fun getKeyFromLocal(envKey: String, fileName: String? = null, default: String? = null): String {
    val key = System.getenv(envKey)
    return key ?: fileName?.let { /* 读 rootDir/fileName */ } ?: default ?: ""
}

buildKeys {
    string("CURSEFORGE_API", getKeyFromLocal("CURSEFORGE_API_KEY", ".curseforge_api.txt", defaultCurseForgeApiKey), true)
}
```

优先级：**环境变量 `CURSEFORGE_API_KEY` → 仓库根 `.curseforge_api.txt`（已 gitignore，不在仓库中）→ Gradle 属性 `curseforge_api_key`**，
最终注入 `BuildConfig.CURSEFORGE_API`。

ZL2 还内置了一个**免 key 的 MCIM 镜像源**：

```kotlin
const val CURSEFORGE_API      = "https://api.curseforge.com/v1"
const val MCIM_CURSEFORGE_API = "https://mod.mcimirror.top/curseforge/v1"
```

`CurseForgeSearcher(api = CURSEFORGE_API, source = "Official CurseForge")` 以构造参数区分源，
所以“官方 CF”与“MCIM 镜像”是两个可选源。

**本项目采用同样策略**（见 `CurseForgeRepo.kt`）：
- key 一律从 `BuildConfig.CURSEFORGE_API` 读取，默认为空，**绝不硬编码 / 提交**；
- key 为空时自动回落 MCIM 镜像；
- 后续接下载源管理页时，官方源与镜像源作为两个可切换源暴露。

### 1.4 许可证风险（需要你决策）

| 项目 | 许可证 |
| --- | --- |
| 本项目 MCServerLauncher | MIT |
| ZL2 (ZalithLauncher/ZalithLauncher2) | **GPL-3.0** |
| FCL (FCL-Team/FoldCraftLauncher) | **GPL-3.0** |

参考接口格式、算法思路、行为设计没有问题；但**直接复制 GPL-3.0 源码到 MIT 项目会使整个项目必须改为 GPL-3.0**。
本方案的实现均为按接口/行为重写，不搬运源码。若你打算直接移植 ZL2 的模块（如 `CardGrid`、`ColorPicker`），
需要同时把本项目许可证改为 GPL-3.0 —— 这需要你明确许可，我未做任何许可证改动。

## 2. ZL2 主题色板（已提取）

`ColorTheme` 是 7 个色位的容器，每套色板提供 7 个颜色：

| 色位 | 名称 |
| --- | --- |
| 1 | embermire（余烬） |
| 2 | velvetRose（丝绒玫瑰） |
| 3 | mistwave（雾浪） |
| 4 | glacier（冰川） |
| 5 | verdantField（碧野） |
| 6 | urbanAsh（城市灰） |
| 7 | verdantDawn（碧晓） |

主题类型枚举：`DYNAMIC, EMBERMIRE, VELVET_ROSE, MISTWAVE, GLACIER, VERDANTFIELD, URBAN_ASH, VERDANT_DAWN, CUSTOM`。

默认色板（embermire，深色）关键角色值：

| 角色 | 值 |
| --- | --- |
| primary | `#FFB59F` |
| onPrimary | `#5F1600` |
| primaryContainer | `#C4502B` |
| secondaryContainer | `#643122` |
| tertiary | `#F0D44C` |
| background / surface | `#1C110E` |
| onBackground / onSurface | `#F4DED8` |
| surfaceVariant | `#58423B` |
| onSurfaceVariant | `#DFC0B7` |
| outline | `#A68B83` |
| surfaceBright | `#443632` |
| surfaceContainerLowest→Highest | `#160C09` `#241916` `#291D19` `#342723` `#3F322E` |
| error / errorContainer | `#FFB4AB` / `#93000A` |

浅色变体同样存在（`primaryLight = #A63A17` 等）。完整表格见 ZL2 `ui/theme/Color.kt`。

布局语义（`ui/theme/Palette.kt`）：
- 应用背景 = `surfaceContainer`
- 卡片背景 = `surfaceBright`（受背景不透明度影响时自动降不透明）
- 卡片标题 = `surface` @ alpha 0.5
- 卡片内 item = 深色 `surfaceVariant`，浅色 `surfaceColorAtElevation(2.dp)`

## 3. 阶段划分

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| P0 | 构建/主题地基：Compose + Material3 接入、ZL2 色板、导航与侧边栏骨架、设置页重构 | 进行中 |
| P1 | 实例模型扩展（星标/图标/排序/实例独立设置）、配置文件管理器、Java 适配校验、服务端核心选择页 | 进行中 |
| P2 | 下载源管理+测速、多线程/限速/代理、资源中心（MR/CF）、整合包、模组/资源包管理、导出 | 待开始 |
| P3 | 控制台多标签/终端、自定义 Jar、世界管理+NBT+备份、前台服务常驻 | 待开始 |
| P4 | 联机（Terracotta 方案）、个性化（背景/配色/FPS）、关于页、网页控制台用户权限 | 待开始 |

## 4. 本切片已落地内容

见 `app/src/main/java/com/tubo/mcserver/`：

| 文件 | 说明 |
| --- | --- |
| `Instance.kt` | 实例模型扩展：`pinned` / `order` / `iconPath` / `loaderVersion` / `core` / `javaHome` / `minMemMb` / `maxMemMb` / `nogui` / `customArgs` 及各自 override 开关；新增 backups/resourcepacks 目录 |
| `InstanceManager.kt` | 新字段持久化到 `instance.prop`（向后兼容旧文件）；`save` / `update` / `setPinned` / `reorder` |
| `ServerCoreCatalog.kt` | 服务端核心选择页数据模型与 5 大类分类 |
| `ConfigManager.kt` | `server.properties` 读写 + 预设中文名称/说明，未预设项原样展示 |
| `CurseForgeRepo.kt` | CF 检索与下载，MCIM 镜像免 key 回落 |
| `ui/theme/ZTheme.kt` | ZL2 色板移植（Compose），7 套色板 + 浅/深色角色 |
| `app/build.gradle` | 开启 `buildConfig`，注入 `CURSEFORGE_API`（默认空） |

### 4.1 未解决 / 待办

- 本机**没有安装 Android SDK**（只有 Android Studio IDE），因此本切片**未经编译验证**。
- 实例 UI、侧边栏、核心选择页的 Compose 界面尚未接线（下一步）。
