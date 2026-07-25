# ETroute Context and Runtime Policy

## Purpose

ETroute is a stdlib-first, rootless PRoot environment manager for Android and Linux-compatible hosts. It installs, validates, launches, configures, backs up, restores, and manages Linux root filesystems without obtaining host root privileges.

PRoot guest root is an emulated identity. It is not Android or Linux host root, and ETroute is not a kernel-enforced security sandbox.

## Adapted runtime decisions

### Rootfs installation is not package installation

Archive installation performs: checksum verification, safe staged extraction, rootfs validation, manifest creation, and atomic activation. It does not require PRoot.

Guest package work is a separate execution mode used later by profiles and package-manager commands.

### Explicit execution modes

- `package`: faked guest root plus ETroute's fixed package-safe bind policy; dangerous host identity-file destinations are rejected.
- `login-root`: faked guest root with only validated caller binds.
- `login-user`: host-like identity with only validated caller binds.

A Boolean `fakeroot` switch is intentionally not used because identity emulation and bind policy are separate security decisions.

### `-S` equivalence policy

ETroute keeps the package-safe bind set as auditable Python data. `tools/verify_proot_s_mode.py` compares that policy against the selected pinned PRoot binary's observed `-S` behavior.

The verification evidence must include the exact executable SHA-256. Version text is a descriptive secondary label.

### Binds

Binds use a structured `BindMount` model and support `HOST:GUEST!`. The trailing `!` requests no dereference of a symlinked guest destination. Guest destinations must be absolute and traversal-free, source paths must exist, and duplicate guest targets are rejected.

### Process cleanup

`--kill-on-exit` is capability-detected from the selected PRoot binary before use. ETroute must still add persistent session/process-tree tracking before service profiles are considered complete.

### Kernel release

Kernel-release faking is optional and disabled by default. It should be enabled only for a documented compatibility requirement.

## Incorporated hardening

- ETroute naming is complete in the supplied starter.
- Environment names are validated.
- SHA-256 values are normalized and validated.
- Installation is staged and atomically activated.
- Existing conflicting installations are rejected unless `--replace` is explicit.
- Archive path traversal and device nodes are rejected by this starter.
- A usable shell is required before activation.
- Manifests have a schema version and are written atomically.
- Package mode rejects host `/etc/passwd`, `/etc/group`, `/etc/shadow`, and `/etc/gshadow` overlays.
- An unset `HOME` fails closed in both package policy construction and PRoot verification.
- PRoot binary SHA-256 is the primary CI identity pin.

## Deferred work

- full Linux-rootfs archive compatibility policy for legitimate special files and links;
- native backup and restore;
- service/session process-tree ledger;
- distribution package-manager detection;
- profile plan/apply/validate/rollback engine;
- OCI or remote image retrieval;
- QEMU `-q` cross-architecture execution;
- Android APK control plane through Kotlin, foreground services, Storage Access Framework, Android Keystore, and authenticated local IPC.

## Recommended next milestone

Implement package-manager detection and a dry-run profile planner, then add the first Nginx profile using `ExecutionMode.PACKAGE`. Do not allow a profile to construct raw PRoot arguments independently.

First-activation diagnostic
---------------------------
ETroute now includes tools/first_activation_diagnostic.py and a `diagnose`
command. The host launcher binds the diagnostic into the guest, runs it with
guest Python 3, and sets HOME=/root so activation state is stored in the
rootfs rather than in Termux's host home. The script is offline and stdlib-only.

The diagnostic verifies live visibility of /dev, /proc, /sys and /tmp, reports
/run/shm separately because it can be absent on some guests, records CPU,
memory, disk, Python and uname information, and compares uname.release with an
optional expected --kernel-release value.

A guest process cannot independently prove that ETumax captured its stdout.
Therefore `--caller etumax` is reported as caller-declared, not as a completed
handshake. ETumax completes the integration check only after parsing valid JSON
and checking the guest process exit code.

ETumax integration contract (v6)
--------------------------------
ETumax integrates through tools/etumax_bridge.py, a one-shot stdlib subprocess
adapter. This is intentionally narrower than a daemon/API and is appropriate
for the first real-device activation. The bridge generates request/session
correlation identifiers, invokes `etroute diagnose --caller etumax --strict`,
requires exactly one JSON document on stdout, validates schema and identity,
and emits a separate bridge envelope. Guest self-reporting alone is not enough
to establish the link; the host-side bridge completes the proof by validating
what it captured. A Unix-socket service remains a later milestone after this
contract is proven on-device.

Android evidence gate
---------------------
The first device milestone uses tools/android_device_validation.py rather than
a persistent service. It executes the public ETumax bridge twice, stores both
raw envelopes, verifies activation-marker persistence, bind visibility, kernel
release, and session correlation, then writes ANDROID_DEVICE_VALIDATION.json
and SHA256SUMS.txt. The evidence directory is transient and excluded from
release archives. Device status remains DEVICE_PENDING until this harness is
run on the intended Android target and the retained report passes review.
