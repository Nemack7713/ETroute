# Integration review v1

Review branch combines these published heads without changing main or the original PRs:

- Coordinator/preflight/evidence: 8465340c4febb632b5439c866aa2eebce528e477 (PR #14 and ancestors).
- Native cancellation/validator: 65110be6e3e5c7ef449060821285f35dba58b987 (PR #6).
- Documentation: a697055efaffdc2c8dbc369615a09e9ead8bbce7 (PR #13 and ancestors).

New integration is NativeStopController and its real Request.cancel binding in the Android
device-validator Activity. No start capability or evidence gate restricts owner Stop.
The controller signals active requests once, latches Stop before attachment, closes on
Activity destruction, rejects replacing active work and blocks reuse on unconfirmed
termination. The Activity also consults uncertainty before finalizing session files.
Stopped and destroyed sessions retain files conservatively even after confirmed exit.

This is a combined review branch, not registered GitHub stack metadata or an automatic
rebase promise. The existing PRs remain draft/unmerged. The combined PR targets main so
all included changes can be reviewed and tested together; it is not a request to merge
without review. No Python execution default changes.

Runtime inventory inspected tracked files for ELF, ZIP and gzip magic. The only archive
candidates were ETroute_Cancellation_Patch.zip (11 source entries) and ETroute_Terminal_UI.zip
(2 source entries). Neither listed Python/native runtime assets. No packaged Python runtime
was identified; this inventory is not cryptographic RuntimePack admission.

Local checks: native ABI/smoke/cancellation regression script and 21 Python tests passed.
The documentation checks and Android/JNI/JVM cumulative results are recorded in the PR.
Eight new JUnit 4 tests use fake request handles and real multithreaded races; actual JNI
crossing is separately covered by host CI, not those fake-based tests. Instrumentation APK
compilation is not physical-device instrumentation execution.

Remaining gates: obtain a genuine compatible Python RuntimePack; bind actual Android
preflight policy/device identity and a fixed native probe runner; integrate cancellation
with that runner/coordinator; enforce streaming/disk bounds; persistent session recovery;
run physical-device Python acceptance. NativeStopController does not itself delete files,
prove process termination or stop an arbitrary session by identifier.
