# ETuMAX / ETroute integration checkpoint

Base inspected: ETroute main 8a393bc. The cancellation and terminal ZIPs had been uploaded but their changes had not been installed into source. This branch installs cancellation, exposes a validated runner preparation callback, and connects the existing device validator's Stop button. It retains the validator's View UI rather than introducing Compose or Chaquopy.

## Implemented

- Single-use, request-scoped cancellation handles created before worker dispatch; early and repeated Stop are retained.
- Separate CANCELLED and CLEANUP_UNCONFIRMED native stages; timeout remains distinguishable.
- Stop reaches the active JNI request and prevents subsequent validation steps. Activity destruction requests cancellation. UI stays STOPPING until the blocking run/suite returns.
- Bounded 200-line display, 4096 characters per line, independent output scrolling, persistent controls, display-only Clear, full Copy Report and persisted diagnostics.
- Runner validates the prepared launch before exposing its request to the controller. Callbacks execute on the invoking runner thread; controllers must not block them or manipulate Views there.
- JNI build verification now requires the cancellation exports.
- Native/Python host CI, actual JVM/JNI token check, Android library/instrumentation compile and validator APK build CI. Instrumentation tests are added but not claimed executed.

## Verified locally

Native C++20 warning-as-error compilation; ABI contract; smoke success, exec failure, timeout and process-tree cleanup; prelaunch cancellation; TERM-resistant cancellation; concurrent independent run; timeout distinction. Python: 21 tests passed. No local JDK/JNI headers, Gradle, CMake, Android SDK or device: JNI/Android validation is delegated to CI and physical acceptance remains required.

## Boundaries

Process groups do not sandbox hostile scripts or contain descendants which deliberately escape their group. Leader reaping can block in kernel state. Do not finalize a cancellation/timeout session on CLEANUP_UNCONFIRMED. The validator conservatively retains diagnostics on manual Stop. Normal-completion background-descendant policy is unchanged. JNI ABI v1 result layout remains seven fields; new Kotlin requires the optional cancellation capability, so rebuild native and Kotlin together. Do not reuse the old ZIP installer over this branch.

Stop controls the native validation suite, not an installed Python worker. No Python RuntimePack was supplied or admitted in this upgrade. A passing validator or APK build is not proof of Python execution.

## ETuMAX branch reconciliation

GETmxU main and the inspected native-python-runtime-foundation-v1, runtime-neutral-python-v1 and runtime-console-ignition-v1 branch Gradle files still apply Chaquopy. Main permits an embedded-only build when the ETroute AAR is missing. These are existing implementation facts, not the desired production contract.

Required next stage: use the validated ETroute AAR and an Android-executable Python runtime, then migrate ETuMAX's controller/transport to the native backend without an embedded fallback. Establish runtime identity, ABI/API compatibility, executable installation location, startup/import probe, streaming, cancellation, restart and physical acceptance before removing the working embedded implementation. Do not blindly merge historical branches: inspect their individual contract and lifecycle changes.

No GETmxU changes or historical branch merges are included here. No new Chaquopy dependency is introduced. Changes are on an integration branch for review; main is unchanged.

## Observed ETroute branch divergence

- `origin`: 0 commits unique to main; 0 unique to branch.
- `origin/agent/etroute-v0.1-foundation`: 83 commits unique to main; 18 unique to branch.
- `origin/agent/p4a-packaging-subsystem`: 78 commits unique to main; 8 unique to branch.
- `origin/agent/waydroid-policy-adapters`: 83 commits unique to main; 22 unique to branch.
- `origin/feature/actions-cache-mode-none`: 2 commits unique to main; 1 unique to branch.
- `origin/feature/runtimepack-prepared-launch`: 2 commits unique to main; 0 unique to branch.
- `origin/finalization/etroute-etumax-runtimepacks`: 2 commits unique to main; 51 unique to branch.
- `origin/fix/rlimit-as-opt-in`: 3 commits unique to main; 11 unique to branch.
- `origin/main`: 0 commits unique to main; 0 unique to branch.
- `origin/system-boundary-diagnostic`: 2 commits unique to main; 1 unique to branch.
