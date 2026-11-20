# RuntimePack preflight v1

This is a read-only Kotlin preflight component. It never admits or launches a runtime. Its best outcome is READY_FOR_DEVICE_PROBE and every report has launchAuthorized=false.

## Trust boundary

PinnedRuntimePack records must come from trusted application configuration (for example reviewed, app-bundled expected inventory), not from the candidate directory or an unverified downloaded manifest. The verifier snapshots these records. This first phase does not implement signature verification, remote metadata parsing, archive extraction, installation or rollback protection.

RuntimePackPlatform.fromAndroid binds API, the process's ABI, nativeLibraryDir and the app-owned runtime-pack directory. Executables and required native libraries are expected under nativeLibraryDir. Payloads are data under filesDir/etroute/runtime-packs/<pack-id>. The verifier rejects overlapping native/payload roots. A writable payload executable is not promoted to an approved executable.

## Checks

- Pinned identity, bounded spec fields, relative paths, SHA-256 format and supported 64-bit ABI.
- Explicit minimum/maximum Android API and process ABI match.
- Exact payload file inventory; unexpected files, missing files and symlinks reject.
- SHA-256 of required native and payload files with bounded total hashing and entry count.
- ELF64 little-endian identity and machine type for every required native file.
- Executable permission on the pinned native entry point.

Other app-owned native files are allowed in the shared nativeLibraryDir; only the pack's declared native dependencies are checked. The payload inventory is exact. ELF architecture checks cannot prove Bionic compatibility or that a shared object is a valid executable. APK extraction/installation strategy must still be verified on-device.

## Use

Construct RuntimePackPreflight with trusted pins and RuntimePackPlatform.fromAndroid(context). Call verify(packId) on a worker thread. Keep the installation stable and app-owned throughout inspection. A rejection must not trigger Chaquopy, PRoot, or another fallback.

This verifier is not an atomic file-to-exec handoff and cannot defend concurrent replacement by an adversarial producer. Revalidation, immutable installation lifetime and binding evidence to the actual launch are required before using it as part of admission. It does not establish a sandbox.

A future controlled probe must run only after the applicable execution authorization and preflight checks pass. Probe startup, Python identity, required imports, cancellation, timeout, loader compatibility and cleanup on the target device. Bind those results to the exact inventory and device/build. Keep bytecode caches and dynamic installation outside the immutable payload tree.

## Verification

JVM tests cover clean fixtures (which still require a probe), unknown identity, payload/native tampering, unexpected and missing files, wrong ABI/API, wrong ELF architecture despite a matching expected digest, path traversal, symlinked payload/root, byte limits and mutation of caller-owned pins. Fixtures are synthetic ELF headers, not executable Python distributions. CI compiles the Android modules and runs these tests. Physical Python execution remains unverified.

The existing docs-site simulator remains Planned: this partial preflight is not full RuntimePack admission. Source and tests are reviewed independently from the documentation model.
