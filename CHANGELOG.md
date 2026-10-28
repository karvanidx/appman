# Changelog

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
