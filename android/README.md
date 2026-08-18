# ETroute Android v0.1 Foundation

This directory starts the native Android ETroute control plane without replacing the repository's existing Python/proot validation work.

## v0.1 scope

- Kotlin-only Android application.
- API 36 compile/target, API 24 minimum.
- Room entities for devices, jobs, releases, and audit events.
- Android Keystore-backed encrypted configuration store.
- Storage Access Framework document gateway.
- `DeviceManager` abstraction with no ADB transport yet.
- `UpdateManager` abstraction with no network updater yet.
- `RuntimeBridge` contract for future ETumax IPC, with Python intentionally absent from this APK milestone.

## Security boundaries

- No secrets are embedded in resources or `BuildConfig`.
- No broad external-storage permission is requested.
- No `MANAGE_EXTERNAL_STORAGE` permission.
- No Termux dependency.
- No Python runtime dependency.
- No network permission in v0.1.
- ETroute and ETumax must communicate through an explicit bridge rather than reading each other's private files.

## Build

Use JDK 17 and a Gradle wrapper compatible with Android Gradle Plugin 8.13.2. From this directory after the wrapper is present:

```sh
./gradlew assembleDebug
```

The next build-system task is to add the Gradle wrapper and CI build job so GitHub Actions can produce the first debug APK.

## Milestone sequence

1. v0.1 foundation: Room, secure configuration, SAF, bridge contracts.
2. v0.2 signed release manifest and update discovery.
3. v0.3 PackageInstaller update flow.
4. v0.4 ADB device model and transport abstraction.
5. v0.5 Android 11+ wireless-debugging pairing.
6. v0.6 shell/device information/logcat.
7. v0.7 APK installation over ADB.
8. v0.8 file push/pull.
9. v0.9 ETumax IPC implementation.
10. v1.0 first stable ETroute control plane.
