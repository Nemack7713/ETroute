# ETroute / ETumax Apktool RuntimePack Assimilation

## Purpose

Integrate the useful Android/Termux compatibility ideas demonstrated by
`h4ck3r0/Apktool-termux` into ETroute/ETumax without making Termux, PRoot,
interactive installers, or mutable global package state part of the production
execution path.

This document is an architectural integration specification. The validated
NativeSupervisor/JNI boundary remains frozen.

## Source-derived behavior worth preserving

The reviewed Apktool-termux repository currently provides these useful ideas:

- dependency probing before installation;
- Java availability through OpenJDK 17 in Termux;
- `aapt` availability;
- automatic discovery of the latest official Apktool GitHub release;
- download of the official Apktool JAR;
- download of the official Linux wrapper;
- Termux-specific wrapper/shebang normalization;
- a post-install `apktool -version` verification;
- a simple recovery path for missing Java.

Its README also suggests `proot -0` for some Android/Java failures. ETroute
must NOT assimilate that behavior because PRoot is excluded from the production
execution path.

The Termux project additionally installs UI-only tools such as `toilet` and
`lolcat`; ETroute does not need those dependencies.

## Licensing boundary

`h4ck3r0/Apktool-termux` is GPL-3.0.

The upstream `iBotPeaches/Apktool` repository is Apache-2.0. The official
Linux wrapper is also Apache-2.0.

Therefore ETroute should assimilate behavior and compatibility knowledge rather
than copy the GPL installer scripts into ETroute core. Where practical, acquire
the official Apktool release artifact directly from upstream and record its
release identity and digest in ETroute evidence.

No third-party source should be vendored into ETroute core until its license,
notices, and redistribution requirements have been explicitly reviewed.

## ETroute-native design

The functionality becomes an optional RuntimePack family:

```
apktool/
  manifest.json
  artifacts/
    apktool_<version>.jar
  evidence/
    upstream-release.json
    hashes.json
    admission.json
```

Java is not hidden inside the Apktool pack. It is represented as a separately
verified dependency:

```
java-runtime/
  <generation>/

apktool/
  <generation>/
```

An Apktool ToolDescriptor references the admitted Java RuntimePack generation.

The production invocation should normally avoid a shell wrapper entirely:

```
<absolute-java> <validated-java-options> -jar <absolute-apktool-jar> <args...>
```

This preserves the useful intent of the upstream wrapper while keeping
PreparedLaunch explicit and avoiding PATH-dependent execution.

## Admission pipeline

```
DISCOVER
  -> ACQUIRE
  -> HASH
  -> VERIFY SOURCE METADATA
  -> VERIFY JAR
  -> VERIFY JAVA DEPENDENCY
  -> VERIFY OPTIONAL AAPT/AAPT2 DEPENDENCY
  -> SMOKE TEST
  -> VERIFIED
  -> USER TRUST APPROVAL
  -> ADMITTED
  -> IMMUTABLE GENERATION PUBLISHED
```

No installer script may modify a published generation in place.

A new upstream release creates a new candidate generation. Existing sessions
retain leases on their original generation until they terminate and finalize.

## Required RuntimePack metadata

At minimum:

```json
{
  "schemaVersion": 1,
  "packId": "org.etroute.apktool",
  "version": "<upstream version>",
  "generationId": "<content-derived identity>",
  "provenance": "EXTERNAL_SIGNED_OR_VERIFIED",
  "source": {
    "repository": "iBotPeaches/Apktool",
    "releaseTag": "<tag>",
    "assetName": "<jar>",
    "assetSha256": "<sha256>"
  },
  "dependencies": [
    {
      "packId": "org.etroute.java",
      "minimumMajor": 17
    }
  ],
  "tools": [
    {
      "toolId": "apktool",
      "kind": "JAVA_JAR",
      "artifact": "artifacts/<jar>"
    }
  ]
}
```

The canonical manifest plus verified artifact hashes determines GenerationId.

## Capability surface

ETumax should expose operations rather than arbitrary shell strings:

- `apktool.version`
- `apktool.decode`
- `apktool.build`
- `apktool.framework.install`
- `apktool.framework.list`
- `apktool.framework.remove`
- `apktool.inspect`

The initial release should prioritize `version`, `decode`, `build`, and
read-only inspection.

Inputs must come through the ETroute session input boundary and outputs must be
written under the session output boundary.

## Safety and filesystem model

Each invocation receives an ETroute-owned session:

```
workspace/input/
workspace/tmp/
workspace/output/
diagnostics/
```

Decoded projects and rebuilt APKs are output artifacts, never runtime-pack
state.

Framework files/cache, if needed, must live in an ETroute-owned versioned cache
outside the immutable RuntimePack generation and must have explicit ownership,
hashing, and cleanup policy.

The pack must not write to Termux `$PREFIX`, shared storage, or arbitrary
application directories.

## Java compatibility adaptation

The Apktool-termux project solves Java availability using Termux
`pkg install openjdk-17`.

ETroute instead needs a Java capability resolver:

1. resolve an already admitted compatible Java RuntimePack;
2. verify absolute executable path;
3. verify Java major version;
4. verify Android ABI/API compatibility of native JVM components;
5. establish Java home/library environment in the parent;
6. smoke-test `java -version`;
7. only then allow the Apktool ToolDescriptor to become executable.

No automatic package-manager installation occurs in the execution path.

## AAPT/AAPT2 adaptation

Apktool-termux installs Termux `aapt`. The official Apktool wrapper also adds
its own directory to PATH so an adjacent `aapt2` can be found.

ETroute should model these as explicit optional tool dependencies rather than
relying on PATH:

```
ToolDescriptor(apktool)
  -> Java ToolDescriptor
  -> optional AAPT/AAPT2 ToolDescriptor
```

If Apktool can complete a requested operation without an external AAPT/AAPT2
dependency, none is injected.

## Release/update behavior

Do not reproduce the Termux script's "always install latest directly into
$PREFIX/bin" behavior.

Instead:

```
check upstream
  -> report candidate version
  -> acquire to staging
  -> verify digest/source
  -> execute smoke tests
  -> mark VERIFIED
  -> request explicit trust/admission
  -> publish new immutable generation
```

Updates are therefore reversible and do not affect running sessions.

## Smoke tests

A candidate generation cannot be admitted until all applicable tests pass:

- Java probe;
- `apktool --version` or equivalent;
- decode a known-good minimal APK fixture;
- verify decoded manifest/resources are present;
- rebuild the fixture;
- verify rebuilt output is a ZIP/APK;
- preserve stdout/stderr and exit classification;
- hash all produced evidence;
- reject writes outside ETroute workspace/cache policy.

For final Android validation, repeat the smoke test on the physical arm64
device through the existing NativeSupervisor path.

## ETumax integration

ETumax requests a capability, not a binary path:

```
ETumax
  -> "apktool.decode"
  -> RuntimePackRegistry
  -> acquire GenerationLease
  -> resolve ToolDescriptor
  -> PreparedLaunch
  -> NativeSupervisor
  -> RunReport
  -> RuntimeSessionFinalizer
  -> release GenerationLease
```

RunReport must identify the exact Apktool and Java GenerationIds used.

## What is deliberately not assimilated

- PRoot fallback;
- `proot -0`;
- implicit `pkg install`;
- mutable installation into `$PREFIX/bin`;
- interactive menu UI;
- decorative dependencies;
- PATH search in the child;
- unverified latest-version replacement;
- installation-time browser launching.

## Initial implementation order

1. RuntimePack manifest / GenerationId / ToolDescriptor / VerifiedRuntimePack.
2. Generation leases and immutable publication.
3. Java capability/runtime-pack resolver.
4. Apktool candidate acquisition and upstream metadata verification.
5. Apktool ToolDescriptor and smoke test.
6. ETumax capability routing.
7. RunReport + transactional session finalization.
8. Physical-device regression gate.
9. Only after the above, optional framework-cache management and richer
   Apktool operations.

## Current upstream observation

At the time this specification was written, the official Apktool GitHub latest
release API reported v3.0.3 and published a SHA-256 digest for its JAR asset.
This value is evidence for the observed candidate only and must not be treated
as a permanent "latest" pin. Future updates must pass the same admission
pipeline.
