# Vendored Shizuku API jars

`api.jar`, `provider.jar`, `aidl.jar`, `shared.jar` — Shizuku API **13.1.5**
(from `dev.rikka.shizuku` on Maven Central).

- Source: https://github.com/RikkaApps/Shizuku-API
- License: MIT

They are committed here so AppMan keeps building with plain `javac` + `d8` (no Gradle,
no network). If you update them, keep all four at the same version.

AppMan uses Shizuku's **user service** API: `Shizuku.bindUserService(...)` starts
`io.github.karvanidx.appman.ShellService` in a process that runs with the ADB shell UID (2000), which is
what allows `pm clear` / `am force-stop` without root.
