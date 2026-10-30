# AppMan

A no-root Android app manager, APK backuper and usage tracker. Plain Java, ~110 KB, no Gradle.

![Build APK](https://github.com/karvanidx/appman/actions/workflows/build.yml/badge.svg)
[![Latest release](https://img.shields.io/github/v/release/karvanidx/appman)](https://github.com/karvanidx/appman/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**[Download the latest APK](https://github.com/karvanidx/appman/releases/latest)**

## Features

**Apps**
- List, search, filter (User / System / Running now), sort (Name / Size / Updated / Package / Recent)
- Multi-select: backup, share, uninstall, and batch Shizuku actions
- Backup APKs (incl. split APKs); export a zip with APKs + native libs + `libs.csv`
- Native library viewer (ABI, name, size)
- CSV export, running indicator, auto-refresh

**Shizuku (no root)**
- Force stop, clear cache, clear data — single or batch
- Uninstall silently via Shizuku, or use the Android dialog
- Live operation log, self-test, diagnostics, status dot

**Usage analytics**
- Today / 7 days / 30 days; screen time, apps used, launches
- Per-app session history: opened at, closed at, duration, ongoing

## Install

1. Download `appman-*.apk` from [Releases](https://github.com/karvanidx/appman/releases/latest).
2. Open it in Files and allow “Install unknown apps” if asked.
3. Grant **Usage access** for storage/analytics/session data.

## Shizuku

1. Start Shizuku via Wireless debugging.
2. AppMan → tap the status dot → Grant permission.
3. Test Shizuku (self-test), then use the power tools.

AppMan uses Shizuku’s system-service binder proxy. The build is `debuggable` so hidden-API
reflection for `ServiceManager`/transaction codes is allowed. Shizuku must be restarted after reboot.

### Limits
- No silent cache/data clearing or force-stop without Shizuku.
- Backups contain APKs only; `/data/data` needs root.
- Android may prune older `UsageEvents`, so session history depends on the range.

## Build (Termux)

Requires OpenJDK 21, Termux `aapt2`, `d8`, `apksigner`, `python3`, and Android platform 36:
`~/android/sdk/platforms/android-36/android.jar` (see `sdk-setup.sh`).

```bash
./build.sh
# dist/appman-<version>.apk
```

`./sdk-setup.sh` downloads `android.jar`; Shizuku API jars are vendored in `app/libs/`.

## CI

GitHub Actions builds every push/PR and uploads the APK artifact.
Add a `KEYSTORE_B64` secret to keep CI signatures stable; otherwise CI uses a temporary key.

## Layout

```
app/src/io/github/karvanidx/appman/   Java sources (no AndroidX)
app/res/                              layouts, drawables, theme
app/libs/                             Shizuku API 13.1.5 (MIT)
tools/mkapk.py                        APK packing + alignment
build.sh / sdk-setup.sh / install.sh  build and install helpers
.github/workflows/build.yml           CI
```

## Credits

Built by [karvanidx](https://github.com/karvanidx) with
[Pi agent](https://pi.dev/) and [DeepSeek Flash](https://www.deepseek.com/en/news/deepseek-v4-1-flash/).
Shizuku integration uses [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) (MIT).

## License

MIT — see [LICENSE](LICENSE).
