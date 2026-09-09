ETroute Native Runtime
======================

ETroute is a non-root Android execution/runtime layer built around a C++20
NativeSupervisor and a Kotlin orchestration layer. The production execution
path does not use PRoot.

CURRENT EXECUTION PATH
----------------------

  Android / Kotlin
      -> PreparedLaunchValidator
      -> EtRouteSessionRunner
      -> JniNativeSupervisor
      -> libetroute_native_supervisor.so
      -> fork
      -> child-side syscall-only setup
      -> execve(absolute executable)

The native supervisor owns:
- CLOEXEC child error-pipe reporting
- real spawn errno + stage propagation
- process-group ownership
- SIGTERM -> grace -> SIGKILL timeout escalation
- stdout/stderr mode 0600
- RLIMIT_CPU
- RLIMIT_NOFILE
- RLIMIT_FSIZE
- adaptive RLIMIT_AS
- absolute execve with no post-fork PATH search

ADVISORY MEMORY POLICY
----------------------
When maxAddressSpaceBytes is zero, ETroute uses a high-but-bounded automatic
address-space allowance:

  75% of visible physical RAM
  minimum 1 GiB
  maximum 8 GiB
  no automatic RLIMIT_AS below 2 GiB physical RAM

This is intentionally an address-space limit, not a request to consume that
amount of resident memory. Android remains free to apply its own process and
system memory policy. Callers may provide an explicit lower cap when a workload
requires tighter control.

The default device-validation workload also uses:
- maxOpenFiles = 4096
- maxFileBytes = 1 GiB
- cpuSeconds = 0 (no ETroute CPU-time cap)

ANDROID TOOLCHAIN
-----------------
- compileSdk 36
- targetSdk 36
- minSdk 24
- Java/JVM 17
- Android Gradle Plugin 8.13.2
- Gradle 8.13
- NDK 27.0.12077973
- CMake 3.22.1
- C++20
- build ABIs: arm64-v8a, x86_64

JNI ABI
-------
JNI result ABI v1 is frozen at seven fields:

  0 ABI version
  1 exit code
  2 signal
  3 timed out
  4 duration ms
  5 spawn errno
  6 spawn stage

Changing this result layout requires a coordinated ABI version bump.

SESSION MODEL
-------------
ETroute owns an app-private per-session layout:

  etroute/sessions/<session>/
      workspace/input/
      workspace/tmp/
      workspace/output/
      diagnostics/

PreparedLaunchValidator canonicalizes filesystem paths before JNI, restricts
executables to approved system/native/runtime-pack roots, prevents working-
directory escape, and requires stdout/stderr to remain under diagnostics.

RuntimeSessionFinalizer:
- removes input/tmp ephemeral state
- preserves workspace/output by default
- optionally exports intentional output through SessionExportSink
- retains only explicitly approved diagnostic files
- bounds retained diagnostic size
- never exports the entire mutable session tree automatically

BUILD/LINK VALIDATION
---------------------
Build-only final code revalidation:

  ETROUTE_BUILD_ONLY=1 bash tools/run_android_jni_validation.sh

This builds the JNI library, Android instrumentation APK, and the physical-
device validator APK, then verifies arm64-v8a + x86_64 ELF/JNI outputs.

Create an easy-to-install validator APK:

  bash tools/build_device_validator.sh

Expected copy:

  dist/ETroute-Device-Validator-debug.apk

PHYSICAL ANDROID FINAL GATE
---------------------------
The preferred final test is now one tap on the physical Android device:

1. Build ETroute-Device-Validator-debug.apk.
2. Install it on the arm64 Android device.
3. Open "ETroute Validator".
4. Tap "RUN ETROUTE TEST".
5. Tap "COPY REPORT" if any step fails and return that report for correction.

The app verifies:
- System.loadLibrary + JNI ABI v1
- successful NativeSupervisor round trip
- execve failure stage/errno preservation
- timeout/process-group termination
- workspace escape rejection before JNI
- output preservation + session finalization
- advisory maximum memory policy

For an ADB-connected device, the deeper instrumentation gate remains:

  bash tools/run_physical_device_final_test.sh

Only the physical-device pass should promote the project to final Android
runtime verified status.

CODESPACES NOTES
----------------
The repository includes local/manual helpers:

  tools/bootstrap_android_sdk.sh
  tools/run_android_jni_validation.sh
  tools/build_device_validator.sh
  tools/run_android_jni_emulator_validation.sh
  tools/run_physical_device_final_test.sh
  tools/show_latest_jni_failure.sh
  tools/verify_android_jni_build.py

GitHub Actions remain off by default. The Codespaces emulator path is optional;
a host may expose /dev/kvm without granting the Codespaces user permission to
use it. This does not affect NDK build/link evidence or the physical-device gate.

LEGACY FILES
------------
Older Python/PRoot-era files remain in the repository only as migration and
historical compatibility material. They are not part of the production native
execution path and must not be reintroduced into NativeSupervisor execution.
