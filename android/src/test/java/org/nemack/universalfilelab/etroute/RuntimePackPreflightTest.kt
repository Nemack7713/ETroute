package org.nemack.universalfilelab.etroute

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimePackPreflightTest {
    @get:Rule val temp = TemporaryFolder()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private data class Fixture(val native: File, val packs: File, val payload: File, val spec: PinnedRuntimePack) {
        fun verifier(specOverride: PinnedRuntimePack = spec, api: Int = 35, abi: String = "arm64-v8a", bytes: Long = 4096) =
            RuntimePackPreflight(mapOf("python-test" to specOverride), RuntimePackPlatform(api, abi, native, packs), maxBytes = bytes)
    }
    private fun fixture(machine: Int = 183): Fixture {
        val native = temp.newFolder("native")
        val packs = temp.newFolder("packs")
        val payload = File(packs, "python-test").apply { mkdirs() }
        val elf = ByteArray(64).apply {
            this[0] = 127; this[1] = 69; this[2] = 76; this[3] = 70
            this[4] = 2; this[5] = 1; this[6] = 1; this[16] = 3
            this[18] = machine.toByte(); this[19] = (machine shr 8).toByte()
        }
        File(native, "libpython-worker.so").apply { writeBytes(elf); assertTrue(setExecutable(true, true)) }
        val data = "fixture stdlib identity".toByteArray()
        File(payload, "stdlib.zip").writeBytes(data)
        return Fixture(native, packs, payload, PinnedRuntimePack(
            "python-test", "fixture-1", "3.13.9", "arm64-v8a", 24, 35, "libpython-worker.so",
            mapOf("libpython-worker.so" to sha(elf)), mapOf("stdlib.zip" to sha(data))
        ))
    }
    @Test fun matchingFilesRequireProbeAndNeverAuthorizeLaunch() {
        val f = fixture(); val report = f.verifier().verify("python-test")
        assertEquals(RuntimePackCheckStatus.READY_FOR_DEVICE_PROBE, report.status)
        assertEquals(RuntimePackCheckReason.DEVICE_PROBE_REQUIRED, report.reason)
        assertEquals(2, report.measuredSha256.size)
        assertFalse(report.launchAuthorized)
    }
    @Test fun unknownIdentityIsRejected() {
        assertEquals(RuntimePackCheckReason.UNKNOWN_PACK, fixture().verifier().verify("not-pinned").reason)
    }
    @Test fun payloadTamperingIsRejected() {
        val f = fixture(); File(f.payload, "stdlib.zip").appendText("tampered")
        assertEquals(RuntimePackCheckReason.HASH_MISMATCH, f.verifier().verify("python-test").reason)
    }
    @Test fun nativeTamperingIsRejected() {
        val f = fixture(); File(f.native, "libpython-worker.so").appendText("tampered")
        assertEquals(RuntimePackCheckReason.HASH_MISMATCH, f.verifier().verify("python-test").reason)
    }
    @Test fun unexpectedPayloadFileIsRejected() {
        val f = fixture(); File(f.payload, "injected.py").writeText("raise RuntimeError()")
        assertEquals(RuntimePackCheckReason.INVENTORY_MISMATCH, f.verifier().verify("python-test").reason)
    }
    @Test fun missingPayloadFileIsRejected() {
        val f = fixture(); assertTrue(File(f.payload, "stdlib.zip").delete())
        assertEquals(RuntimePackCheckReason.INVENTORY_MISMATCH, f.verifier().verify("python-test").reason)
    }
    @Test fun mismatchedAbiIsRejected() {
        val f = fixture()
        assertEquals(RuntimePackCheckReason.UNSUPPORTED_ABI, f.verifier(abi = "x86_64").verify("python-test").reason)
    }
    @Test fun apiWithoutBoundedDirectoryEnumerationIsRejected() {
        val f = fixture()
        assertEquals(RuntimePackCheckReason.UNSUPPORTED_API, f.verifier(api = 25).verify("python-test").reason)
    }
    @Test fun unsupportedApiIsRejected() {
        val f = fixture()
        assertEquals(RuntimePackCheckReason.UNSUPPORTED_API, f.verifier(api = 36).verify("python-test").reason)
    }
    @Test fun trustedHashDoesNotMakeWrongElfArchitectureCompatible() {
        val f = fixture(machine = 62)
        assertEquals(RuntimePackCheckReason.INVALID_ELF, f.verifier().verify("python-test").reason)
    }
    @Test fun traversalInPinnedConfigurationIsRejected() {
        val f = fixture()
        val bad = f.spec.copy(executable = "../escape", nativeSha256 = mapOf("../escape" to "0".repeat(64)))
        assertEquals(RuntimePackCheckReason.INVALID_PINNED_SPEC, f.verifier(bad).verify("python-test").reason)
    }
    @Test fun payloadSymlinkIsRejectedEvenWithExpectedDigest() {
        val f = fixture(); val original = File(f.payload, "stdlib.zip"); val external = temp.newFile("outside")
        external.writeBytes(original.readBytes()); assertTrue(original.delete())
        Files.createSymbolicLink(original.toPath(), external.toPath())
        assertEquals(RuntimePackCheckReason.INVALID_PATH, f.verifier().verify("python-test").reason)
    }
    @Test fun packRootSymlinkIsRejected() {
        val f = fixture(); val alternate = temp.newFolder("alternate")
        assertTrue(f.payload.deleteRecursively())
        Files.createSymbolicLink(f.payload.toPath(), alternate.toPath())
        assertEquals(RuntimePackCheckReason.INVALID_PATH, f.verifier().verify("python-test").reason)
    }
    @Test fun hashingBudgetRejectsOversizeInput() {
        val f = fixture()
        assertEquals(RuntimePackCheckReason.LIMIT_EXCEEDED, f.verifier(bytes = 8).verify("python-test").reason)
    }
    @Test fun verifierSnapshotsCallerOwnedPins() {
        val f = fixture(); val hashes = f.spec.payloadSha256.toMutableMap()
        val verifier = f.verifier(f.spec.copy(payloadSha256 = hashes))
        hashes.clear()
        assertEquals(RuntimePackCheckStatus.READY_FOR_DEVICE_PROBE, verifier.verify("python-test").status)
    }
}
