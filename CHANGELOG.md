# Changelog

## 1.4
- Cache clear no longer times out: the system clears it but never sends the completion
  callback on this ROM, so the result is "requested" and sizes refresh afterwards
- Power buttons disable while an operation runs; Force stop / Clear cache cool down for
  3 seconds, Clear data stays disabled until the details dialog is reopened
- Long-press tooltips on toolbar, selection, details and Shizuku buttons
- Native libraries viewer (ABI, name, size, total, copy list)
- Usage analytics redesigned: segmented Today / 7 days / 30 days + summary card
- Tap an analytics row for **session history** (opened at, closed at, duration, ongoing)
- Diagnostics now include the AppMan version

## 1.3.1
- Android 16 QPR compatibility: transaction codes are now taken from this device's
  `framework.jar` as a fallback (`forceStopPackage=85`, `deleteApplicationCacheFilesAsUser=98`,
  `clearApplicationUserData=81`)
- Clear data now uses `IActivityManager.clearApplicationUserData(String, boolean,
  IPackageDataObserver, int)` — the extra `keepState` boolean was the missing argument
- Wait for the system's `IPackageDataObserver` callback, so "Completed" means the clear
  actually finished

## 1.3
- Fixed "Starting shell service…" hanging: switched from Shizuku user service to the
  classic system-service binder proxy (`IPackageManager` / `IActivityManager`)
- App is built `debuggable` so hidden `ServiceManager` / transaction-code reflection works
- Shizuku status self-test shows binders, transaction codes and Shizuku UID
- Power tools are enabled as soon as Shizuku is authorized (no service start step)
- Operation log now reports binder-call results

## 1.2
- Renamed package to `io.github.karvanidx.appman`
- Shizuku status dot in the app bar (green = shell service ready)
- Shizuku status screen with diagnostics, Restart shell service, Test shell (`id`)
- Power tools (force stop / clear cache / clear data) are disabled until Shizuku is ready
- Live operation-log dialog with timestamps, command, exit code and output
- Sort by **Recent** (last used) using usage access
- **Usage analytics** screen: Today / 7 days / 30 days, screen time, launches, last used
- Larger fonts and touch targets
- About screen credits (Pi agent + DeepSeek) and MIT license

## 1.1
- Shizuku integration: force stop, clear cache, clear data (no root)
- Shizuku user service (`ShellService`) running as shell UID 2000
- Shizuku status menu entry

## 1.0
- App list, search, filters, sort, multi-select
- APK backup (including split APKs), share, uninstall, CSV export
- Usage access for data/cache sizes
