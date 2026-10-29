# 发布到 GitHub 清单

## 1. 先做的事

- [ ] 把下文的 `tubo` / `<你的用户名>` 换成你自己的 GitHub 账号名
- [ ] 确认 `LICENSE` 里的 Copyright 年份与署名
- [ ] 在 Android Studio 里执行一次 `./gradlew assembleDebug`，确保能编译
- [ ] 截图放进 `docs/screenshots/`（见该目录 README）

## 2. 仓库初始化

项目目录当前**不是 git 仓库**，第一次发布：

```bash
cd AMCSL

# 若之前没有 wrapper jar，先在装了 Gradle 的机器上执行一次（会补全 gradlew + jar）
gradle wrapper --gradle-version 8.7

git init
git add .
git commit -m "feat: MC服务器启动器 v2.0 初始发布"
git branch -M main
git remote add origin https://github.com/<你的用户名>/MCServerLauncher.git
git push -u origin main
```

> `local.properties`、`build/`、`.gradle/` 已被 `.gitignore` 排除，不会上传。

## 3. 发布 Release

```bash
# 先出签名包（需要自备 keystore，见下）
./gradlew assembleRelease

git tag v2.0
git push origin v2.0
```

推 tag 后 GitHub Actions 会自动构建 debug APK 并挂到 Release 上
（`.github/workflows/android.yml`）。若想上传正式签名的包，手动在
Release 页面拖入 `app/build/outputs/apk/release/app-release.apk`。

### 签名配置（可选，建议加上）

在项目根目录建 `keystore.properties`（已被 gitignore）：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=******
keyAlias=mcsl
keyPassword=******
```

`app/build.gradle` 中加入：

```gradle
def ksProps = new Properties()
def ksFile = rootProject.file('keystore.properties')
if (ksFile.exists()) {
    ksProps.load(new FileInputStream(ksFile))
}

android {
    signingConfigs {
        release {
            storeFile file(ksProps['storeFile'])
            storePassword ksProps['storePassword']
            keyAlias ksProps['keyAlias']
            keyPassword ksProps['keyPassword']
        }
    }
    buildTypes {
        release {
            minifyEnabled false
            signingConfig signingConfigs.release
        }
    }
}
```

首次生成 keystore：

```bash
keytool -genkeypair -v -keystore release.jks -alias mcsl \
  -keyalg RSA -keysize 2048 -validity 10000
```

## 4. 仓库设置建议

| 项目 | 建议 |
| --- | --- |
| Description | Android 上的 Minecraft Java 版服务器启动器，无需 Termux / root |
| Topics | `minecraft` `minecraft-server` `android` `kotlin` `launcher` `termux` `fabric` |
| License | MIT（GitHub 会自动识别 LICENSE 文件） |
| Releases | 勾选 `Set as the latest release` |
| Issues | 建议开启，并加个 `bug` / `enhancement` 模板 |

## 5. 发布前自查

- [ ] `local.properties` 没有被提交（含本机 SDK 路径）
- [ ] 没有硬编码的密钥 / token
- [ ] README 里的截图链接都能打开
- [ ] `README_EN.md` 内容与中文版一致（至少功能列表同步）
- [ ] 在 Android 11+ 真机上完整走一遍：授权 → 装 Java → 建实例 → 启动

## 6. 已知问题（建议写进 Release Notes）

- 未实现前台服务，后台/息屏可能被系统杀进程
- 网页控制台无鉴权，仅限可信局域网
- 基岩版（BDS）需要 proot + glibc rootfs，当前仅有入口
- Java 仅支持 17 / 21 / 25（Termux 已停止提供 8 / 11）
