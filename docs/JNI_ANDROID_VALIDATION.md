# ETroute Android JNI validation gate

Status vocabulary:

- `JNI_PRESENT`: JNI source and Kotlin wrapper exist.
- `JNI_NDK_BUILD_VERIFIED`: real Android NDK build produced the shared library.
- `JNI_LOAD_VERIFIED`: `System.loadLibrary("etroute_native_supervisor")` succeeded.
- `JNI_ABI_VERIFIED`: ABI v1 handshake and seven-field result contract passed.
- `JNI_ROUNDTRIP_VERIFIED`: success, exec failure, chdir failure, timeout, cwd/output and rlimit tests passed.
- `JNI_DEVICE_VERIFIED`: the above ran successfully on a supported physical device or emulator.
- `JNI_GREEN`: all required Android validation evidence has been reviewed.

Current contract (ABI v1):

1. ABI version
2. exit code
3. signal
4. timed out
5. duration ms
6. spawn errno
7. spawn stage

## Manual run

```bash
bash tools/run_android_jni_validation.sh
```

The script intentionally does not use GitHub Actions. It builds the Android module locally, lists connected targets, reports each target ABI, and runs `:android:connectedDebugAndroidTest` for `arm64-v8a` and `x86_64` targets.

If no Gradle wrapper exists but a local `gradle` command is available, the script generates a Gradle 8.7 wrapper before building.

## Instrumentation coverage

`NativeSupervisorJniTest` currently verifies:

- ABI v1 negotiation
- successful `/system/bin/sh` JNI round trip
- `EXECVE + ENOENT` propagation
- `CHDIR + ENOENT` propagation
- timeout classification and process-group cleanup evidence through `/proc`
- working-directory propagation
- stdout/stderr file mode `0600`
- child `RLIMIT_NOFILE` visibility

## Required ABI coverage

Run against both:

- `arm64-v8a` — physical device or compatible emulator
- `x86_64` — emulator

An NDK build by itself is not enough to mark JNI green. At least one real instrumentation execution must prove library loading and JNI round-trip behavior, and both required ABI targets should be validated before the gate is considered complete.

## Deliberately deferred

The following do not belong in this JNI validation slice:

- RuntimePack Python
- ETumax transport integration
- Landlock
- C++20 coroutine supervisor path
- NDK build caching

Those remain behind the JNI device-validation gate.
