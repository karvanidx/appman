# AppMan

A tiny, no-root Android app manager and screen-time tracker built entirely inside Termux —
no Gradle, no Android Studio, no Kotlin, no AndroidX. ~94 KB.

- **Package:** `io.github.karvanidx.appman`
- **Author:** [karvanidx](https://github.com/karvanidx)
- **Built with:** Pi agent + DeepSeek
- **License:** MIT

## Features

### Apps
- All installed apps (user + system): icon, version, APK size, updated date
- Search by name/package, filter All / User / System
- Sort by **Name / Size / Updated / Package / Recent** (recent uses usage access)
- Long-press → multi-select → **Backup / Share / Uninstall** in bulk
- **Backup APK** (including split APKs + `info.txt`) to
  `Download/AppMan/backup-<timestamp>/<package>/`
- **Share APK** (single APK, or a `.zip` for split apps)
- **Uninstall** through the official Android confirmation dialog
- App info shortcut, copy package name, CSV export of the app list
- Auto-refresh when apps are installed/removed

### Shizuku power tools (no root)
Each app's details dialog has **Force stop**, **Clear cache**, **Clear data**:
- `am force-stop <pkg>`
- `pm clear --cache-only <pkg>`
- `pm clear <pkg>` (extra confirmation)

A small **Shizuku dot** in the app bar shows state at a glance:
green = shell service ready, amber = running but needs permission/service, gray = off.
Tap it for the **Shizuku status** screen: Open Shizuku, Grant permission,
**Restart shell service**, **Test shell (id)**, Copy diagnostics.
Power buttons are disabled (grayed out) until the service is ready.

Every Shizuku action opens a **live operation log** with timestamps, the exact command,
exit code and output — no more guessing what happened.

Shizuku runs a shell service over **Wireless debugging** (no root, no PC needed).
Android kills it on reboot, so it must be started again after a reboot.
Implementation: `Shizuku.bindUserService()` starts `ShellService.java` as the ADB shell
UID (2000), which is why `pm` / `am` work without root.

### Usage analytics
- **Usage analytics** screen: Today / Last 7 days / Last 30 days
- Total screen time, apps used, total launches
- Per-app screen time with a bar, launch count and last-used time
- Requires the same **Usage access** permission already used for data/cache sizes
- Recent-app sorting uses `lastUsed` from `UsageStatsManager`

## No-root limitations

- Without Shizuku, Android has no public API to clear another app's cache/data or force-stop
  it. AppMan then falls back to the App info screen.
- Backups contain APKs only. `/data/data` is unreadable without root (even with Shizuku).
- Launch counts are derived from `UsageEvents` (the public `UsageStats.getAppLaunchCount()`
  is a hidden API), so they are as accurate as Android's event log.
- Some OEM firmware blocks reading other apps' APKs; those apps are reported as failed
  during a backup, the rest still succeed.

## Build (Termux, no root)

Requirements already set up in this environment:

- OpenJDK 21, Termux `aapt2`, `d8`, `apksigner`, `python3`
- `~/android/sdk/platforms/android-36/android.jar` (`./sdk-setup.sh` re-downloads it)
- Shizuku API 13.1.5 jars vendored in `app/libs/` (MIT)

```bash
cd ~/projects/appman
./build.sh
```

Output: `dist/appman-1.2.apk` (+ `.idsig`), v2+v3 signed.

Pipeline: `aapt2 compile/link` → `javac -source 8 -target 8 -bootclasspath android.jar`
→ `d8` → `tools/mkapk.py` (packs `classes.dex`, aligns `resources.arsc` to 4 bytes and
native libs to 16 KB) → `apksigner sign` (keystore auto-created on first build).

`ANDROID_JAR=/path/to/android.jar ./build.sh` to use another platform jar.

## Install

### Tap the APK
`dist/appman-1.2.apk` is copied to `/sdcard/Download/AppMan/appman-1.2.apk`.
**Files → Download → AppMan → appman-1.2.apk → Install**.

> The package was renamed from `com.appman` to `io.github.karvanidx.appman` in v1.2.
> It installs as a separate app. Uninstall the old **AppMan 1.1** afterwards to avoid
> two icons; settings and backups on shared storage are not affected.

### ADB over Wireless debugging
Developer options → Wireless debugging (and "Pair device with pairing code"), then:

```bash
./adb-pair.sh <pair_ip:port> <6-digit-code> <connect_ip:port>
adb install -r dist/appman-1.2.apk
```

## Shizuku setup

1. Open **Shizuku** → **Start via Wireless debugging** (pair if asked).
2. Open **AppMan** → tap the **Shizuku dot** in the app bar → **Grant permission**.
3. Tap **Restart shell service** (or just use a power action — it starts on demand).
4. Optional: **Test shell (id)** should print `uid=2000(shell)`.

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
  ShizukuHelper.java          Shizuku binding, state, diagnostics, shell transport
  ShellService.java           runs as shell (UID 2000) inside Shizuku user service
  UsageRepo.java              UsageStats / UsageEvents queries
  Utils.java                  sizes, dates, durations, storage stats
app/libs/                     vendored Shizuku API 13.1.5 (MIT)
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
