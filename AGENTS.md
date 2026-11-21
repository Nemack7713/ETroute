# ETroute integration work

Preserve unrelated work. Inspect the branch, interfaces and existing tests before editing. Keep changes scoped and document behavioral changes with their implementation.

## Runtime boundary

- Production Python uses the genuine ETroute NativeSupervisor with an absolute executable and explicit environment. No Chaquopy, PRoot or silent fallback.
- General scripts remain disabled. A fixed probe executes Python but does not grant general execution or RuntimePack admission.
- Start requires trusted preflight, explicit fixed-scope approval and an atomically consumed one-use claim before dispatch. Never reconstruct authorization from pasted reports.
- Stop targets the owner’s request, is idempotent and independent of capability expiry or evidence. Signal native cancellation explicitly.
- Confirm termination before destructive cleanup. Retain uncertain sessions and bounded diagnostics; never silently restart.
- Schema validation is separate from successful probe outcome, retention and authorization. Unsigned evidence is not attestation.

## Current phase

User-reported API 35 device results pass startup/import and ST01–ST05/ST07. User-reported ST08 completion and lifecycle evidence both passed, including replay rejection. ST06 genuine native uncertainty remains open; a debug receipt-loss simulation tests owner retention and retry policy without manufacturing a native termination failure. ST08 connects fresh native observations through the existing coordinator, bounded parser and semantic validator; historical phone reports are regression fixtures only.

ST08 phone confirmation is complete. Verify ST06 owner-side receipt-loss retention on-device while keeping genuine native uncertainty explicitly open before ETuMAX native AAR integration, streaming/recovery and full physical-device acceptance. Default enablement needs a separate final change with an opt-out after those gates. Do not merge this stack or enable general scripts as part of ST08.

## Android tools and verification

The Android CLI skills workflow verifies installation and uploads an artifact; jobs and Codespaces do not share its filesystem automatically. Do not assume installed skills are present locally. When using Android CLI, inspect its version/help. Read the relevant installed SKILL.md before applicable UI work. Existing validation scripts retain their pinned Gradle/SDK/NDK setup; installing skills does not replace build, JNI or device tests.

Use the installed android-cli guidance for CLI work, adaptive guidance for adaptive UI, and jetpack-compose-performance guidance for Compose changes. The validator currently uses Android Views, so do not migrate it to Compose as an unrelated change. Never run an agent from this CI job or give it secrets.

Run cumulative library/app JVM tests, native/JNI checks, APK and instrumentation compilation, packaged inventory verification and docs checks. Label instrumentation compilation separately from execution. Include what the change unblocks and remaining device gates in each PR. Stacked branch rebasing and merging are explicit operations; do not assume GitHub automatically rebases or merges a stack.

ETuMAX is a separate repository: inspect its actual identity and contracts before making cross-repository edits.
