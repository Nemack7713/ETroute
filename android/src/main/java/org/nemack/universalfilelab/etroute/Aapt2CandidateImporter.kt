package org.nemack.universalfilelab.etroute

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class Aapt2SourcePolicy(
    val repository: String,
    val commit: String,
    val path: String,
    val gitBlobSha1: String,
    val sizeBytes: Long,
    val minApi: Int
) {
    init {
        require(repository.isNotBlank()) { "repository cannot be blank" }
        require(commit.matches(Regex("[0-9a-f]{40}"))) { "commit must be lowercase Git SHA-1" }
        require(path.isNotBlank()) { "path cannot be blank" }
        require(gitBlobSha1.matches(Regex("[0-9a-f]{40}"))) {
            "gitBlobSha1 must be lowercase Git blob SHA-1"
        }
        require(sizeBytes > 0) { "sizeBytes must be positive" }
        require(minApi > 0) { "minApi must be positive" }
    }

    companion object {
        val RENDIIX_ANDROID_12_ARM64 = Aapt2SourcePolicy(
            repository = "rendiix/termux-aapt",
            commit = "8d981d41c1228e0ea2733e6d4bfc039d182d1b0f",
            path = "prebuilt-binary-android-12+/arm64/aapt2",
            gitBlobSha1 = "317ef4b90fef2e5921901447a8463c268fb19bd5",
            sizeBytes = 3_498_816,
            minApi = 31
        )
    }
}

data class ImportedAapt2Candidate(
    val root: File,
    val manifest: RuntimePackManifest,
    val verified: VerifiedRuntimePack,
    val sourcePolicy: Aapt2SourcePolicy,
    val sha256: String,
    val evidenceFile: File,
    val reusedExistingCandidate: Boolean
)

class Aapt2CandidateImporter(
    context: Context,
    private val sourcePolicy: Aapt2SourcePolicy =
        Aapt2SourcePolicy.RENDIIX_ANDROID_12_ARM64,
    private val verifier: RuntimePackVerifier = RuntimePackVerifier()
) {
    private val appContext = context.applicationContext
    private val candidatesRoot =
        File(appContext.filesDir, "etroute/runtime-pack-candidates/aapt2").canonicalFile

    fun importFromUri(uri: Uri): ImportedAapt2Candidate {
        val stream = appContext.contentResolver.openInputStream(uri)
            ?: error("Unable to open AAPT2 candidate URI")
        return stream.use { importFromStream(it) }
    }

    fun importFromStream(input: InputStream): ImportedAapt2Candidate {
        check(candidatesRoot.mkdirs() || candidatesRoot.isDirectory) {
            "Unable to create AAPT2 candidate root"
        }

        val staging = File(
            candidatesRoot,
            ".staging-${UUID.randomUUID()}"
        ).canonicalFile
        require(isWithin(staging, candidatesRoot)) {
            "AAPT2 staging path escaped candidate root"
        }
        check(staging.mkdirs()) { "Unable to create AAPT2 staging directory" }

        try {
            val artifact = File(staging, "artifacts/aapt2").canonicalFile
            require(isWithin(artifact, staging)) {
                "AAPT2 artifact path escaped staging root"
            }
            check(artifact.parentFile!!.mkdirs()) {
                "Unable to create AAPT2 artifact directory"
            }

            val hashes = copyAndHash(input, artifact)
            require(hashes.bytesWritten == sourcePolicy.sizeBytes) {
                "AAPT2 source size mismatch: expected=${sourcePolicy.sizeBytes} " +
                    "actual=${hashes.bytesWritten}"
            }
            require(hashes.gitBlobSha1 == sourcePolicy.gitBlobSha1) {
                "AAPT2 source Git blob mismatch"
            }

            verifyElf64Aarch64Pie(artifact)
            check(artifact.setExecutable(true, true)) {
                "Unable to mark staged AAPT2 executable"
            }

            val manifest = RuntimePackManifest(
                packId = Aapt2RuntimeResolver.DEFAULT_AAPT2_PACK_ID,
                version = sourcePolicy.commit,
                provenance = RuntimePackProvenance.EXTERNAL_VERIFIED,
                minApi = sourcePolicy.minApi,
                abis = setOf("arm64-v8a"),
                source = RuntimePackSource(
                    repository = sourcePolicy.repository,
                    revision = sourcePolicy.commit,
                    assetName = "aapt2",
                    assetSha256 = hashes.sha256
                ),
                artifacts = listOf(
                    RuntimePackArtifact(
                        path = "artifacts/aapt2",
                        sha256 = hashes.sha256,
                        sizeBytes = hashes.bytesWritten,
                        executable = true
                    )
                ),
                dependencies = emptyList(),
                tools = listOf(
                    ToolDescriptor(
                        toolId = Aapt2RuntimeResolver.DEFAULT_AAPT2_TOOL_ID,
                        kind = RuntimePackToolKind.NATIVE_EXECUTABLE,
                        artifactPath = "artifacts/aapt2"
                    )
                )
            )

            val verified = verifier.verify(staging, manifest)
            val destination = File(
                candidatesRoot,
                verified.generationId.value
            ).canonicalFile
            require(isWithin(destination, candidatesRoot)) {
                "AAPT2 candidate destination escaped candidate root"
            }

            if (destination.exists()) {
                val existing = verifier.verify(destination, manifest)
                staging.deleteRecursively()
                val evidence = File(destination, EVIDENCE_NAME)
                if (!evidence.isFile) {
                    writeEvidence(evidence, existing, hashes)
                }
                return ImportedAapt2Candidate(
                    root = destination,
                    manifest = manifest,
                    verified = existing,
                    sourcePolicy = sourcePolicy,
                    sha256 = hashes.sha256,
                    evidenceFile = evidence,
                    reusedExistingCandidate = true
                )
            }

            val evidence = File(staging, EVIDENCE_NAME)
            writeEvidence(evidence, verified, hashes)

            if (!staging.renameTo(destination)) {
                if (!destination.exists()) {
                    error("Unable to atomically publish AAPT2 candidate staging")
                }
                val existing = verifier.verify(destination, manifest)
                staging.deleteRecursively()
                return ImportedAapt2Candidate(
                    root = destination,
                    manifest = manifest,
                    verified = existing,
                    sourcePolicy = sourcePolicy,
                    sha256 = hashes.sha256,
                    evidenceFile = File(destination, EVIDENCE_NAME),
                    reusedExistingCandidate = true
                )
            }

            val promoted = verifier.verify(destination, manifest)
            return ImportedAapt2Candidate(
                root = destination,
                manifest = manifest,
                verified = promoted,
                sourcePolicy = sourcePolicy,
                sha256 = hashes.sha256,
                evidenceFile = File(destination, EVIDENCE_NAME),
                reusedExistingCandidate = false
            )
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    fun publishApproved(
        candidate: ImportedAapt2Candidate,
        trustApproval: RuntimePackTrustApproval
    ): RuntimePackPublication {
        require(trustApproval.decision == RuntimePackTrustDecision.APPROVED) {
            "AAPT2 publication requires explicit user trust approval"
        }
        return RuntimePackPublisher(appContext, verifier).publish(
            candidateRoot = candidate.root,
            manifest = candidate.manifest,
            trustApproval = trustApproval
        )
    }

    private data class CopyHashes(
        val bytesWritten: Long,
        val sha256: String,
        val gitBlobSha1: String
    )

    private fun copyAndHash(input: InputStream, destination: File): CopyHashes {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val gitSha1 = MessageDigest.getInstance("SHA-1")
        gitSha1.update(
            "blob ${sourcePolicy.sizeBytes}\u0000".toByteArray(StandardCharsets.US_ASCII)
        )

        var written = 0L
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                written += count
                require(written <= sourcePolicy.sizeBytes) {
                    "AAPT2 source exceeded pinned size ${sourcePolicy.sizeBytes}"
                }
                sha256.update(buffer, 0, count)
                gitSha1.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
            output.flush()
        }

        return CopyHashes(
            bytesWritten = written,
            sha256 = sha256.digest().toHex(),
            gitBlobSha1 = gitSha1.digest().toHex()
        )
    }

    private fun verifyElf64Aarch64Pie(file: File) {
        val header = ByteArray(20)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                require(count > 0) { "AAPT2 ELF header is truncated" }
                offset += count
            }
        }

        require(
            header[0] == 0x7f.toByte() &&
                header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte()
        ) {
            "AAPT2 candidate is not ELF"
        }
        require(header[4].toInt() and 0xff == 2) {
            "AAPT2 candidate must be ELF64"
        }
        require(header[5].toInt() and 0xff == 1) {
            "AAPT2 candidate must be little-endian"
        }

        val elfType = u16le(header, 16)
        val machine = u16le(header, 18)
        require(elfType == 3) {
            "AAPT2 candidate must be PIE/ET_DYN; e_type=$elfType"
        }
        require(machine == 183) {
            "AAPT2 candidate must be AArch64; e_machine=$machine"
        }
    }

    private fun writeEvidence(
        file: File,
        verified: VerifiedRuntimePack,
        hashes: CopyHashes
    ) {
        val payload = JSONObject().apply {
            put("schemaVersion", 1)
            put("kind", "aapt2-candidate-import")
            put("status", "IMPORTED_IDENTITY_VERIFIED")
            put("packId", verified.manifest.packId)
            put("generationId", verified.generationId.value)
            put("sha256", hashes.sha256)
            put("gitBlobSha1", hashes.gitBlobSha1)
            put("sizeBytes", hashes.bytesWritten)
            put("sourceRepository", sourcePolicy.repository)
            put("sourceCommit", sourcePolicy.commit)
            put("sourcePath", sourcePolicy.path)
            put("minApi", sourcePolicy.minApi)
            put("abi", "arm64-v8a")
            put("elf64", true)
            put("elfMachine", "AARCH64")
            put("trustGranted", false)
            put("published", false)
            put("runtimeSmokeVerified", false)
        }

        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(payload.toString(2).toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (t: Throwable) {
            atomic.failWrite(output)
            throw t
        }
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private fun isWithin(child: File, parent: File): Boolean {
        val childPath = child.path
        val parentPath = parent.path
        return childPath == parentPath ||
            childPath.startsWith("$parentPath${File.separator}")
    }

    companion object {
        private const val EVIDENCE_NAME = "candidate-import.json"
    }
}
