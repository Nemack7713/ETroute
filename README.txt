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

Adaptive memory policy
----------------------
When no explicit native address-space cap is supplied, ETroute calculates an
advisory RLIMIT_AS from physical RAM:

  75% of physical RAM, capped at 8 GiB

The cap is intentionally generous for high-memory Android devices while still
leaving operating-system headroom. On devices below 2 GiB ETroute does not
apply the automatic address-space cap.

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

Changing this layout requires a coordinated ABI version bump.

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
Run:

  bash tools/run_android_jni_validation.sh

This builds arm64-v8a + x86_64, validates the ELF architecture, and verifies
both required JNI exports. The current repository status is recorded in
ETROUTE_STATUS.json.

PHYSICAL ANDROID FINAL GATE
---------------------------
The authoritative final test is the connected arm64 Android device:

  bash tools/run_physical_device_final_test.sh

The gate runs the Android instrumentation suite and verifies:
- System.loadLibrary
- JNI ABI v1 handshake
- successful JNI/native round trip
- execve ENOENT propagation
- chdir errno propagation
- timeout and process-tree cleanup
- 0600 stdout/stderr
- child resource limits
- ETroute system smoke output
- workspace escape rejection before JNI
- intentional output preservation
- bounded diagnostic finalization

Only a passing physical-device run should promote JNI_DEVICE_VERIFIED / final
Android status.

CODESPACES NOTES
----------------
The repository includes optional Codespaces helpers:

  tools/bootstrap_android_sdk.sh
  tools/run_android_jni_emulator_validation.sh
  tools/show_latest_jni_failure.sh
  tools/verify_android_jni_build.py

The Codespaces emulator path is optional. A host may expose /dev/kvm without
granting the Codespaces user permission to use it. This does not affect the
NDK build/link evidence or the physical-device final gate.

LEGACY FILES
------------
Older Python/PRoot-era files remain in the repository only as migration and
historical compatibility material. They are not part of the production native
execution path and must not be reintroduced into NativeSupervisor execution.
