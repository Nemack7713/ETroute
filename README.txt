ETroute Adapted Starter
=======================

Files:
- etroute.py: hardened stdlib-only starter runtime
- tools/verify_proot_s_mode.py: PRoot -S drift and binary identity verifier
- docs/ETROUTE_CONTEXT.md: consolidated design and verification policy
- tests/test_etroute.py: initial unit tests

Run tests:
    cd ETroute_adapted
    python -m unittest discover -s tests -v

Show CLI:
    python etroute.py --help

Install a rootfs:
    python etroute.py install debian \
      --tarball /path/debian-rootfs.tar.xz \
      --sha256 <64-hex-digest> \
      --version stable \
      --arch aarch64

Open an emulated-root login:
    python etroute.py run debian --mode login-root

Run a package command under the explicit package-safe policy:
    python etroute.py run debian --mode package -- apt-get update

Verify the selected PRoot binary against the explicit -S policy:
    python tools/verify_proot_s_mode.py \
      --rootfs "$PREFIX/var/lib/etroute/containers/debian/rootfs" \
      --expected-sha256 <known-good-proot-sha256> \
      --json-out proot-s-bind-audit.json

FIRST-ACTIVATION DIAGNOSTIC
---------------------------
Run the bundled offline guest diagnostic after the guest has Python 3:

  python etroute.py diagnose debian --caller manual --kernel-release 5.15.0-et --strict

For ETumax capture:

  python etroute.py diagnose debian --caller etumax --kernel-release 5.15.0-et --strict

The launcher sets HOME=/root so the activation marker remains inside the
selected rootfs at /root/.etroute_activation_marker. It does not use the
Termux host HOME. The diagnostic reports live /dev, /proc, /sys, /tmp and
optional /run/shm visibility. ETROUTE_CALLER is only a launcher declaration;
ETumax must confirm that stdout was actually captured and parsed.

Strict diagnostic exit codes:
  10 required guest bind visibility failed
  11 expected kernel release did not match
  12 activation marker could not be persisted

ETUMAX BRIDGE (v6)
------------------
ETumax should invoke the stable subprocess bridge rather than importing ETroute
internals:

  python tools/etumax_bridge.py debian \
    --kernel-release 5.15.0-et \
    --json-out evidence/etumax-etroute-handshake.json

The bridge emits exactly one JSON envelope. `ok: true` means all of the
following were verified: ETroute returned zero, stdout was exactly one JSON
object, schema 1 was recognized, required binds were visible, any requested
kernel release matched, the guest declared ETumax as caller, and environment
and session correlation values matched. Exit 20 means launch/timeout failure;
exit 21 means contract or diagnostic failure.

REPRODUCIBLE RELEASE AND STATUS (v7)
------------------------------------
`ETROUTE_STATUS.json` is the authoritative evidence classification. Local
compile/tests may be VERIFIED_LOCAL; Android and real-device behavior remains
EXTERNAL_UNVERIFIABLE until raw target-device evidence is captured.

Build one clean release from the canonical source tree:

  python tools/build_release.py \
    --output release/ETroute_adapted_starter_v7.zip \
    --release-manifest release/ETROUTE_RELEASE.json

The builder compiles required modules, runs the unit suite, excludes bytecode
and transient release output, creates a deterministic ZIP, and writes the final
archive SHA-256 to the sidecar release manifest. The archive hash is not stored
inside the archive because that would be self-referential.

Android first-device validation
-------------------------------
Run two independent ETumax bridge handshakes and retain raw evidence:

    python tools/android_device_validation.py debian \
      --kernel-release 5.15.0-et \
      --evidence-dir evidence/android

A PASS requires both bridge runs, required bind visibility, matching kernel
release, persistent activation state on the second run, and distinct validated
ETumax session identifiers. The command writes a report and SHA256SUMS.txt.
A local implementation or unit-test result does not promote Android status;
only evidence produced on the target device can do that.
