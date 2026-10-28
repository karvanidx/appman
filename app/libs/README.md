# Vendored Shizuku API jars

`api.jar`, `provider.jar`, `aidl.jar`, `shared.jar` — Shizuku API **13.1.5**
(from `dev.rikka.shizuku` on Maven Central).

- Source: https://github.com/RikkaApps/Shizuku-API
- License: MIT

They are committed here so AppMan keeps building with plain `javac` + `d8` (no Gradle,
no network). If you update them, keep all four at the same version.

AppMan uses Shizuku's **system-service binder proxy**: `ShizukuBinderWrapper` +
`SystemServiceHelper` proxy `IPackageManager` / `IActivityManager` calls as the ADB shell
identity. AppMan is built with `android:debuggable="true"` so the `ServiceManager` and
AIDL transaction-code reflection used by `SystemServiceHelper` is allowed. No Shizuku
user service is required.
