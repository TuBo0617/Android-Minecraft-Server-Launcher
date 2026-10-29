# MC 服务器启动器 (MCServerLauncher)

在 Android 手机上开一个真正的 Minecraft Java 版服务器。

纯原生 Kotlin，**不需要 Termux**，不需要 root。启动器会自己从 Termux 仓库下载 Java 运行时并解包运行，一键建实例、下核心、装模组、看控制台、开网页远程管理。

<p>
  <img alt="Android" src="https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-21-blue">
  <img alt="License" src="https://img.shields.io/badge/License-MIT-green">
</p>

---

## 功能

| 功能 | 说明 |
| --- | --- |
| 🗂 **多实例管理** | 每个服务器独立目录（世界 / mods / plugins / 核心 jar），长按删除，点击切换 |
| ⬇ **核心下载** | 原版 Vanilla（Mojang 官方清单）、Fabric（自动挑稳定 loader） |
| ☕ **Java 管理** | 自动检测已安装的 Java，支持多版本切换；一键下载安装 openjdk 17 / 21 / 25（源自 Termux 仓库，内置运行） |
| 📥 **下载中心** | 所有下载（Java / 核心 / BDS / 模组）统一排队展示进度，可看**完整安装日志**、可取消 |
| 🧩 **模组安装** | 接入 Modrinth，按实例版本自动筛选，一键装进 `mods/` 或 `plugins/` |
| 💻 **控制台** | 实时滚动服务器输出，直接下发 `op` / `stop` 等命令 |
| 🌐 **网页远程控制台** | 手机开服，同 Wi-Fi 下电脑浏览器即可看日志、发命令、启停 |
| ⛏ **基岩版入口** | BDS 下载 + proot 依赖准备（受 glibc 限制，目前仅预留入口） |

## 截图

> 建议上传 6 张：实例页 / 核心下载 / Java 管理 / 下载中心 / 控制台 / 设置。
> 把图片放到 `docs/screenshots/` 后替换下面的链接。

| 实例 | 下载中心 | Java 管理 |
| --- | --- | --- |
| ![实例](docs/screenshots/instances.png) | ![下载](docs/screenshots/downloads.png) | ![Java](docs/screenshots/java.png) |

## 安装

1. 到 [Releases](../../releases) 下载最新的 `app-release.apk`
2. 安装时允许「安装未知来源应用」
3. 首次启动会申请**所有文件访问权限**（`/storage/emulated/0/MCServerLauncher` 需要它）

## 快速上手

```
① 设置 → 授权存储
② Java 页 → 选版本 → 下载并安装（进度在「下载」页）
③ 实例页 → 新建实例（选 MC 版本 + Vanilla/Fabric）
④ 设置页 → 填最大内存（建议 ≥1024MB）
⑤ 控制台 → 启动，等 "Done (x.xxx s)!" 出现
⑥ 同一 Wi-Fi 下用手机 IP:25565 进入游戏
```

## 数据目录

所有数据都在公共存储，卸载 App 不会丢，可直接用文件管理器备份：

```
/storage/emulated/0/MCServerLauncher/
├── servers/          服务器实例
│   └── <实例名>/
│       ├── server.jar          原版核心
│       ├── fabric-server-launch.jar
│       ├── server.properties
│       ├── world/              世界存档
│       ├── mods/  plugins/
│       └── instance.prop       实例元信息
├── java/packages/    Java 安装包缓存（.deb，可删）
├── downloads/        其他下载缓存
├── logs/
└── tmp/
```

### 关于 Java 运行目录

Android 把 `/storage/emulated/0` 挂载为 **noexec**，任何从那里执行的二进制都会 `Permission denied`。所以：

- **Java 可执行文件** 解包到应用私有目录 `files/java/openjdk-XX/`（系统限制，无法挪到公共存储）
- **安装包、服务器数据、世界存档** 全部留在公共目录

## 从源码构建

环境要求：JDK 17、Android SDK（Platform 34 + Build-Tools 34）。

```bash
git clone https://github.com/<你的用户名>/MCServerLauncher.git
cd MCServerLauncher
# local.properties 里写你自己的 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

发布包：

```bash
./gradlew assembleRelease   # 需要在 build.gradle 里配置 signingConfig
```

> 仓库默认使用阿里云 Maven 镜像 + google()/mavenCentral() 作为回退，海外网络可直接删掉 aliyun 那几行。

## 技术栈

- Kotlin 1.9.24 / AGP 8.5.2 / compileSdk 34 / minSdk 21
- 单 Activity + Fragment，无 Room、无 Hilt，纯 `object` 单例 + SharedPreferences
- `HttpURLConnection` 手写下载（带进度与取消）
- 自研 `ar` + `tar.xz` 解析（`DebExtractor`），用于解 Termux `.deb`，含软链接与可执行位还原
- `ProcessBuilder` 拉起 java 进程，输出流分行缓冲 + 网页控制台复用同一日志

## 已知限制

- ⚠ **后台保活**：未实现前台服务，息屏或切后台后系统可能杀掉服务器进程（开发中）
- ⚠ **网页控制台无鉴权**：同局域网内任何人可操作，仅在可信网络使用
- ⚠ **基岩版不可用**：BDS 是 glibc 二进制，需要 proot + Debian rootfs，目前只有入口和依赖下载
- ⚠ **Java 8/11**：Termux 主仓库已停止提供，仅支持 17 / 21 / 25
- 未适配平板 / 横屏特殊布局；只有 debug 构建配置，发布前需自备签名

## 贡献

欢迎 Issue 和 PR。提交前请确保 `./gradlew assembleDebug` 能通过。

## 致谢

- [Termux packages](https://github.com/termux/termux-packages) — Java 运行时来源
- [Modrinth](https://modrinth.com) — 模组 API
- [Fabric Meta](https://meta.fabricmc.net) / [Mojang Piston](https://piston-meta.mojang.com) — 核心下载

## 免责声明

本项目与 Mojang Studios、Microsoft、FabricMC 均无关联。Minecraft 是 Mojang Studios 的商标。请使用你合法拥有的游戏账号。

## 许可证

[MIT](LICENSE) © 2026 tubo
