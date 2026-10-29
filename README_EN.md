# MCServerLauncher

Run a real Minecraft **Java Edition** server on your Android phone.

Native Kotlin, **no Termux required**, no root. The launcher downloads a Java
runtime from the Termux repo, unpacks it and runs your server for you.

> English summary only. See [README.md](README.md) (Chinese) for the full docs.

## Features

- **Multi-instance** – each server gets its own folder (world, mods, plugins, jar)
- **Core downloader** – Vanilla (Mojang manifest) and Fabric
- **Java manager** – auto-detects installed runtimes, one-tap install of openjdk 17 / 21 / 25
- **Download center** – every download with progress, full install log and cancel button
- **Mods** – Modrinth integration, filtered by instance MC version
- **Console** – live output, send commands (`op`, `stop`, …)
- **Web console** – control the server from a browser on the same Wi-Fi

## Data layout

```
/storage/emulated/0/MCServerLauncher/
├── servers/      instances (world / mods / plugins / jar)
├── java/         downloaded runtime packages
├── downloads/  logs/  tmp/
```

Java binaries are unpacked into the app-private `files/java/` because
`/storage/emulated/0` is mounted **noexec**.

## Build

```bash
git clone https://github.com/<you>/MCServerLauncher.git
cd MCServerLauncher
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

Requires JDK 17 and Android SDK Platform 34.

## Known limitations

- No foreground service yet – the OS may kill the server in background
- Web console has no authentication – trusted LAN only
- Bedrock (BDS) is a glibc binary and needs proot; entry point only for now

## License

[MIT](LICENSE) © 2026 tubo
