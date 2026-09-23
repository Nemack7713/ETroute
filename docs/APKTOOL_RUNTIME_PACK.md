# ETroute / ETumax Apktool + AAPT2 RuntimePack Assimilation

## Purpose

Use `rendiix/termux-apktool` as the Android-native compatibility reference
for Apktool-related resource tooling while keeping ETroute/ETumax independent
from Termux, PRoot, package-manager mutation, and mutable global tool state.

The validated NativeSupervisor/JNI boundary remains frozen.

## Replacement reference

The earlier `h4ck3r0/Apktool-termux` reference is superseded for ETroute
architecture work by:

`https://github.com/rendiix/termux-apktool`

The reviewed repository demonstrates these useful Android-specific ideas:

- Apktool operation on Termux without PRoot or root;
- architecture-specific packages for aarch64, arm, i686, and x86_64;
- Android-native resource tooling packaged together with Apktool;
- a maintainer-described process for compiling `aapt` from source by
  translating Android.bp build information into a CMake build;
- direct package installation as a practical Android-host distribution model.

The repository currently carries an Apktool 2.8.2 Termux snapshot and its last
code push is from 2023. ETroute therefore treats it as an Android-native
compatibility/build reference, not as the authoritative current Apktool release
source.

## Source-of-truth split

ETroute uses separate authorities for separate concerns:

```
rendiix/termux-apktool
    -> Android-native compatibility/build reference

iBotPeaches/Apktool
    -> Apktool JAR and Apktool release identity

AOSP / reproducibly validated Android-native build
    -> AAPT2 implementation source

ETroute RuntimePack admission
    -> final executable trust decision
```

No third-party installer script is executed as part of the ETroute production
path.

## Licensing boundary

The replacement `rendiix/termux-apktool` repository declares Apache-2.0.
The official `iBotPeaches/Apktool` repository is also Apache-2.0.

ETroute still prefers assimilation over copying:

- preserve attribution and source provenance;
- acquire or build artifacts independently;
- hash and verify every admitted artifact;
- do not copy installer behavior that mutates Termux `$PREFIX`;
- do not silently inherit repository package versions.

## RuntimePack family

The Android Apktool capability is a composition of independent generations:

```
org.etroute.java
    <Java Generation J>

org.etroute.aapt2
    <AAPT2 Generation R>

org.etroute.apktool
    <Apktool Generation A>
        requires Java J
        requires AAPT2 R
```

The three packs remain independently replaceable and independently verified.

## Apktool RuntimePack

```
apktool/
  <GenerationId>/
    manifest.canonical.json
    artifacts/
      apktool_<version>.jar
```

Normal execution avoids a shell wrapper:

```
<absolute-java>
  <validated JVM options>
  -jar
  <absolute verified apktool.jar>
  <apktool args>
  --aapt
  <absolute admitted aapt2>
```

No PATH lookup is required in the child.

## AAPT2 RuntimePack

AAPT2 is a first-class RuntimePack, not an incidental helper:

```
aapt2/
  <GenerationId>/
    manifest.canonical.json
    artifacts/
      aapt2
```

Its ToolDescriptor is:

```
packId = org.etroute.aapt2
toolId = aapt2
kind = NATIVE_EXECUTABLE
abi = arm64-v8a
```

A candidate is not admissible merely because it runs in Termux.

Required evidence includes:

```
ELF class
ELF endianness
e_machine
program interpreter
DT_NEEDED inventory
SHA-256
file size
Android API policy
executable permission
runtime version/probe
resource compile smoke test
resource link smoke test
```

For the current physical target, the production lane is arm64-v8a.

## Admission pipeline

```
DISCOVER / BUILD
  -> STAGE
  -> HASH
  -> VERIFY SOURCE METADATA
  -> VERIFY ARTIFACT FORMAT
  -> VERIFY ABI
  -> INVENTORY NATIVE LINKAGE
  -> VERIFY JAVA DEPENDENCY
  -> VERIFY AAPT2 DEPENDENCY
  -> RUNTIME PROBE
  -> APKTOOL VERSION PROBE
  -> DECODE SMOKE TEST
  -> RESOURCE COMPILE/LINK SMOKE TEST
  -> REBUILD SMOKE TEST
  -> VERIFIED
  -> USER TRUST APPROVAL
  -> ADMITTED
  -> IMMUTABLE GENERATION PUBLISHED
```

Technical verification remains separate from user trust approval.

## Apktool manifest dependency model

A candidate Apktool manifest declares both dependencies:

```json
{
  "dependencies": [
    {
      "packId": "org.etroute.java",
      "requiredGenerationId": null
    },
    {
      "packId": "org.etroute.aapt2",
      "requiredGenerationId": null
    }
  ]
}
```

Before production admission, those dependencies should be pinned to exact
GenerationIds.

A production RunReport must identify all three generations:

```
apktoolGeneration = A
javaGeneration = J
aapt2Generation = R
```

## Capability surface

ETumax exposes capabilities rather than arbitrary shell strings:

- `apktool.version`
- `apktool.decode`
- `apktool.build`
- `apktool.inspect`
- `apktool.framework.install`
- `apktool.framework.list`
- `apktool.framework.remove`

The initial release prioritizes version, decode, build, and read-only
inspection.

## Filesystem model

Each invocation remains confined to an ETroute session:

```
workspace/input/
workspace/tmp/
workspace/output/
diagnostics/
```

Decoded projects and rebuilt APKs are output artifacts, never RuntimePack state.

Framework state must not mutate the Apktool generation. If framework support is
enabled, it lives in a separately managed cache such as:

```
filesDir/etroute/caches/apktool-frameworks/<framework-identity>/
```

Framework-cache evidence should include source hash, package ID/tag, Apktool
GenerationId, and creation time.

## Java compatibility

Java remains a separate RuntimePack.

ETroute verifies:

1. admitted Java generation;
2. absolute Java executable;
3. Java home;
4. runtime version probe;
5. Android ABI/API compatibility of native JVM components;
6. successful `java -version`;
7. successful `java -jar apktool.jar --version`.

Java requirements are treated as version-tested compatibility evidence rather
than being hard-coded only from installer assumptions.

## AAPT2 build/research lane

The rendiix maintainer stated that their Android-native aapt work was compiled
from source using CMake after translating Android.bp build information.

ETroute preserves that as a reproducibility research lane:

```
AOSP resource-tool sources
    -> Android.bp dependency analysis
    -> controlled CMake/Android-native build
    -> arm64-v8a aapt2
    -> ELF/linkage validation
    -> RuntimePack candidate
    -> trust/admission
```

The old rendiix 2.8.2 package may be used as a compatibility reference or
controlled comparison artifact, but it is not automatically promoted to the
production RuntimePack.

## Post-build separation

Apktool rebuild output is not considered a final installable release artifact.

The wider Android artifact pipeline remains:

```
Apktool rebuild
    -> unsigned APK
    -> zipalign -P 16
    -> signing
    -> zipalign -c -P 16
    -> signature verification
    -> final artifact evidence
```

Zipalign and signing remain separate capabilities from Apktool.

## Smoke tests

Before admission, all applicable tests must pass:

- Java runtime probe;
- AAPT2 runtime/version probe;
- AAPT2 minimal resource compile;
- AAPT2 minimal resource link;
- Apktool version probe using the admitted Java generation;
- decode known-good minimal APK fixture;
- verify decoded manifest/resources;
- rebuild fixture while injecting admitted AAPT2;
- verify rebuilt output is a valid ZIP/APK;
- preserve stdout/stderr and termination classification;
- hash all evidence;
- reject writes outside ETroute workspace/cache policy.

Final Android acceptance repeats the end-to-end test on the physical arm64
device through NativeSupervisor.

## ETumax integration

```
ETumax
  -> apktool.decode / apktool.build
  -> acquire Apktool GenerationLease
  -> acquire Java GenerationLease
  -> acquire AAPT2 GenerationLease
  -> resolve ToolDescriptors
  -> construct PreparedLaunch
  -> NativeSupervisor
  -> RunReport
  -> transactional finalization
  -> release all generation leases
```

## Deliberately not assimilated

- PRoot or `proot -0`;
- root requirement;
- implicit `pkg install` / `apt install`;
- mutable installation into Termux `$PREFIX`;
- PATH-dependent child execution;
- direct execution of third-party install scripts;
- silent replacement by an unverified newer version;
- use of the old 2.8.2 snapshot as the production Apktool source of truth;
- combining alignment/signing acceptance into the Apktool capability.

## Implementation order from this revision

1. AAPT2 first-class RuntimePack resolver.
2. Apktool candidate manifests declare Java + AAPT2 dependencies.
3. Android-native ELF/linkage candidate inspection for AAPT2.
4. AAPT2 compile/link smoke-test capability.
5. Apktool launch planner injects the admitted AAPT2 absolute path.
6. RunReport records Apktool + Java + AAPT2 generations.
7. Framework-cache isolation.
8. Physical arm64 decode/rebuild regression gate.
