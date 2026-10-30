# Changelog

## 1.6
- About version is read from the package (no more hardcoded v1.4)
- Export now asks for confirmation, then offers **Open Downloads** / **Share zip**
- **Running now** includes background processes through Shizuku
- Backup completion can open Downloads
- Shorter, clearer README and release notes

## 1.5
- Batch Shizuku actions on multi-select: force stop / clear cache / clear data
- Running now filter
- Export package zip: base + split APKs, native libs, `info.txt`, `libs.csv`
- Uninstall options: Via Shizuku (silent, live log) or Android dialog
- Clickable Pi / DeepSeek credits
- GitHub Actions APK build

## 1.4
- Cache clear no longer waits for a callback (some Android 16 ROMs never send it)
- Cooldowns, tooltips, native-libs viewer, analytics redesign, session history

## 1.3.1
- Android 16 QPR transaction codes from the device framework
- `clearApplicationUserData` boolean argument fix; wait for observer

## 1.3
- Shizuku system-service binder proxy (fixes stuck user service)

## 1.2
- New package `io.github.karvanidx.appman`
- Status dot, operation log, recent sort, analytics, larger fonts

## 1.1
- Shizuku power tools (no root)

## 1.0
- App list, search, filters, multi-select, backup, share, uninstall, CSV
