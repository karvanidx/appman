# AppMan

A tiny, no-root Android app manager and screen-time tracker built entirely inside Termux —
no Gradle, no Android Studio, no Kotlin, no AndroidX. ~94 KB.

- **Package:** `io.github.karvanidx.appman`
- **Version:** 1.5
- **Author:** [karvanidx](https://github.com/karvanidx)
- **Built with:** Pi agent + DeepSeek
- **License:** MIT

![Build APK](https://github.com/karvanidx/appman/actions/workflows/build.yml/badge.svg)

**[⬇ Download the latest APK](https://github.com/karvanidx/appman/releases/latest)** — see [Releases](https://github.com/karvanidx/appman/releases).

## Features

### Apps
- All installed apps (user + system): icon, version, APK size, updated date
- Search by name/package, filter All / User / System
- Sort by **Name / Size / Updated / Package / Recent** (recent uses usage access)
- Long-press → multi-select → **Backup / Share / Uninstall** in bulk
- **Batch Shizuku actions**: force stop / clear cache / clear data for selected apps
- **Running now** filter (foreground packages from usage events)
- **Uninstall options**: silent via Shizuku (no system popup, live log) or the Android dialog
- **Backup APK** (including split APKs + `info.txt`) to
  `Download/AppMan/backup-<timestamp>/<package>/`
- **Share APK** (single APK, or a `.zip` for split apps)
- **Uninstall** through the official Android confirmation dialog
- App info shortcut, copy package name, CSV export of the app list
- Auto-refresh when apps are installed/removed

### Shizuku power tools (no root)
Each app's details dialog has **Force stop**, **Clear cache**, **Clear data**:
- `am force-stop <pkg>`
- `deleteApplicationCacheFilesAsUser(<pkg>)` (cache; some ROMs never send the completion callback, so it is fire-and-request)
- `pm clear <pkg>` (extra confirmation)

A small **Shizuku dot** in the app bar shows state at a glance:
green = authorized, amber = running but permission missing, gray = off.
Tap it for the **Shizuku status** screen: Open Shizuku, Grant permission,
**Test Shizuku (self-test)**, Copy diagnostics.
Power buttons are disabled (grayed out) until Shizuku is authorized.

Every Shizuku action opens a **live operation log** with timestamps, the exact command,
exit code and output — no more guessing what happened.

Shizuku runs a shell service over **Wireless debugging** (no root, no PC needed).
Android kills it on reboot, so it must be started again after a reboot.

Implementation: AppMan uses Shizuku's **system-service binder proxy**
(`ShizukuBinderWrapper` + `SystemServiceHelper`) to call `IPackageManager` and
`IActivityManager` as the shell identity — the classic Shizuku API. The Shizuku
*user-service* path (`bindUserService`) stays stuck on some ROMs, so it is not used.
AppMan is built with `android:debuggable="true"` (it is a sideloaded personal app) so
`ServiceManager` and AIDL transaction-code reflection are allowed.

### Usage analytics
- **Usage analytics** screen: Today / Last 7 days / Last 30 days (segmented control)
- Screen-time summary card, apps used, total launches
- Per-app screen time with a bar, launch count and last-used time
- Tap an app → **session history**: every open/close with start time, end time and duration
- Requires the same **Usage access** permission already used for data/cache sizes
- Recent-app sorting uses `lastUsed` from `UsageStatsManager`

### Advanced details
- **Native libraries** viewer: lists every `.so` inside the APK(s), ABI and size
- **Export package (.zip)**: base APK + split APKs + all native libs + info/libs.csv
- Long-press tooltips on buttons and the Shizuku dot
- Power buttons cool down after use (Clear data stays disabled until you reopen details)

## No-root limitations

- Without Shizuku, Android has no public API to clear another app's cache/data or force-stop
  it. AppMan then falls back to the App info screen.
- Backups contain APKs only. `/data/data` is unreadable without root (even with Shizuku).
- Launch counts are derived from `UsageEvents` (the public `UsageStats.getAppLaunchCount()`
  is a hidden API), so they are as accurate as Android's event log.
- Some OEM firmware blocks reading other apps' APKs; those apps are reported as failed
  during a backup, the rest still succeed.

## CI

GitHub Actions builds every push/PR and uploads the APK artifact (`.github/workflows/build.yml`).
The CI build uses the `KEYSTORE_B64` repository secret if present; otherwise it generates a
temporary keystore (the artifact is fine for testing, but not update-compatible with a
locally-built APK). Add your keystore as a base64 secret to keep signatures stable.

## Build (Termux, no root)

Requirements already set up in this environment:

- OpenJDK 21, Termux `aapt2`, `d8`, `apksigner`, `python3`
- `~/android/sdk/platforms/android-36/android.jar` (`./sdk-setup.sh` re-downloads it)
- Shizuku API 13.1.5 jars vendored in `app/libs/` (MIT)

```bash
cd ~/projects/appman
./build.sh
```

Output: `dist/appman-1.5.apk` (+ `.idsig`), v2+v3 signed.

Pipeline: `aapt2 compile/link` → `javac -source 8 -target 8 -bootclasspath android.jar`
→ `d8` → `tools/mkapk.py` (packs `classes.dex`, aligns `resources.arsc` to 4 bytes and
native libs to 16 KB) → `apksigner sign` (keystore auto-created on first build).

`ANDROID_JAR=/path/to/android.jar ./build.sh` to use another platform jar.

## Install

### Tap the APK
`dist/appman-1.5.apk` is copied to `/sdcard/Download/AppMan/appman-1.5.apk`.
**Files → Download → AppMan → appman-1.5.apk → Install**.

> The package was renamed from `com.appman` to `io.github.karvanidx.appman` in v1.2.
> It installs as a separate app. Uninstall the old **AppMan 1.1** afterwards to avoid
> two icons; settings and backups on shared storage are not affected.

### ADB over Wireless debugging
Developer options → Wireless debugging (and "Pair device with pairing code"), then:

```bash
./adb-pair.sh <pair_ip:port> <6-digit-code> <connect_ip:port>
adb install -r dist/appman-1.5.apk
```

## Shizuku setup

1. Open **Shizuku** → **Start via Wireless debugging** (pair if asked).
2. Open **AppMan** → tap the **Shizuku dot** in the app bar → **Grant permission**.
3. Tap **Test Shizuku (self-test)** — it should show the package/activity binders and
   transaction codes as **ok**, and Shizuku UID `2000`.
4. Use Force stop / Clear cache / Clear data in any app's details.

## Project layout

```
app/AndroidManifest.xml
app/res/                      layouts, drawables, theme
app/src/io/github/karvanidx/appman/
  MainActivity.java           list, selection, details, Shizuku UI, operation log
  AnalyticsActivity.java      screen time / launches / last used
  AppRepo.java                installed apps + usage stats
  AppAdapter.java             list adapter
  BackupManager.java          APK backup sessions
  StorageWriter.java          MediaStore (Android 10+) / legacy Downloads
  ApkProvider.java            provider for sharing APKs
  ShizukuHelper.java          Shizuku binder proxy, self-test, diagnostics
  UsageRepo.java              UsageStats / UsageEvents, sessions
  NativeLibs.java             .so listing inside APKs
  PackageExporter.java        APKs + native libs .zip export
  Utils.java                  sizes, dates, durations, storage stats
app/libs/                     vendored Shizuku API 13.1.5 (MIT)
.github/workflows/build.yml   GitHub Actions APK build
tools/mkapk.py                packaging + zipalign
build.sh, install.sh, adb-pair.sh, sdk-setup.sh
```

## Publish to GitHub

The repo is initialized and committed locally. To publish (replace `AppMan` with your repo name):

```bash
git remote add origin https://github.com/karvanidx/AppMan.git
git push -u origin main
```

Authentication is up to your Git credential helper / GitHub CLI; no token is stored in
this repository. `.gitignore` excludes `build/`, `dist/`, `*.apk` and `appman.keystore`.

## Credits

- App: **karvanidx**
- Built with **Pi agent** + **DeepSeek**
- Shizuku integration uses the [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) (MIT)
- Android framework only — no other third-party libraries

## License

MIT — see [LICENSE](LICENSE).
