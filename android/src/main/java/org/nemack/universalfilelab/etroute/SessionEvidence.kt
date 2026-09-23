package org.nemack.universalfilelab.etroute

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class SessionEvidenceKind(val fileName: String) {
    APK_INVENTORY_BEFORE("apk-inventory-before.json"),
    APK_INVENTORY_AFTER("apk-inventory-after.json"),
    APK_INVENTORY_COMPARISON("apk-inventory-comparison.json")
}

data class SessionEvidenceArtifact(
    val kind: SessionEvidenceKind,
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long
) {
    init {
        require(relativePath.isNotBlank()) { "relativePath cannot be blank" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "sha256 must be lowercase SHA-256"
        }
        require(sizeBytes >= 0) { "sizeBytes cannot be negative" }
    }
}

data class SessionEvidenceManifest(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val artifacts: List<SessionEvidenceArtifact>,
    val createdAtEpochMs: Long
) {
    init {
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "unsupported evidence schemaVersion=$schemaVersion"
        }
        require(sessionId.isNotBlank()) { "sessionId cannot be blank" }
        require(createdAtEpochMs >= 0) { "createdAtEpochMs cannot be negative" }
        require(artifacts.map { it.kind }.distinct().size == artifacts.size) {
            "duplicate evidence kinds are not allowed"
        }
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

data class SessionEvidenceAttachment(
    val manifest: SessionEvidenceManifest,
    val manifestFile: File,
    val manifestSha256: String
)

class SessionEvidenceStore(
    private val paths: RuntimeSessionPaths
) {
    private val evidenceRoot = File(paths.root, EVIDENCE_DIRECTORY).canonicalFile
    private val receiptFile =
        File(paths.root, TransactionalSessionFinalizer.RECEIPT_NAME).canonicalFile

    fun attachApkInventoryEvidence(
        beforeInventory: File,
        afterInventory: File,
        comparison: File,
        nowEpochMs: Long = System.currentTimeMillis()
    ): SessionEvidenceAttachment {
        require(!receiptFile.exists()) {
            "cannot attach APK inventory evidence after finalization receipt exists"
        }

        val inputs = listOf(
            SessionEvidenceKind.APK_INVENTORY_BEFORE to beforeInventory,
            SessionEvidenceKind.APK_INVENTORY_AFTER to afterInventory,
            SessionEvidenceKind.APK_INVENTORY_COMPARISON to comparison
        )

        val artifacts = inputs.map { (kind, source) ->
            val canonicalSource = source.canonicalFile
            require(canonicalSource.isFile) {
                "evidence source does not exist: ${canonicalSource.path}"
            }
            validateJsonObject(canonicalSource)

            check(evidenceRoot.mkdirs() || evidenceRoot.isDirectory) {
                "unable to create evidence directory: ${evidenceRoot.path}"
            }

            val destination = File(evidenceRoot, kind.fileName).canonicalFile
            require(isWithin(destination, evidenceRoot)) {
                "evidence destination escaped session evidence root"
            }
            require(!destination.exists()) {
                "evidence artifact already exists: ${destination.path}"
            }

            copyAtomically(canonicalSource, destination)
            SessionEvidenceArtifact(
                kind = kind,
                relativePath = destination.relativeTo(paths.root.canonicalFile)
                    .invariantSeparatorsPath,
                sha256 = sha256(destination),
                sizeBytes = destination.length()
            )
        }

        val manifest = SessionEvidenceManifest(
            sessionId = paths.sessionId,
            artifacts = artifacts.sortedBy { it.kind.name },
            createdAtEpochMs = nowEpochMs
        )
        val manifestFile = File(evidenceRoot, MANIFEST_NAME)
        writeManifest(manifestFile, manifest)

        return SessionEvidenceAttachment(
            manifest = manifest,
            manifestFile = manifestFile,
            manifestSha256 = sha256(manifestFile)
        )
    }

    private fun validateJsonObject(file: File) {
        JSONObject(file.readText(StandardCharsets.UTF_8))
    }

    private fun copyAtomically(source: File, destination: File) {
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try {
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (t: Throwable) {
            atomic.failWrite(output)
            throw t
        }
    }

    private fun writeManifest(file: File, manifest: SessionEvidenceManifest) {
        val artifacts = JSONArray()
        manifest.artifacts.forEach { artifact ->
            artifacts.put(
                JSONObject().apply {
                    put("kind", artifact.kind.name)
                    put("relativePath", artifact.relativePath)
                    put("sha256", artifact.sha256)
                    put("sizeBytes", artifact.sizeBytes)
                }
            )
        }

        val payload = JSONObject().apply {
            put("schemaVersion", manifest.schemaVersion)
            put("sessionId", manifest.sessionId)
            put("createdAtEpochMs", manifest.createdAtEpochMs)
            put("trustDecisionMade", false)
            put("artifacts", artifacts)
        }.toString(2)

        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(payload.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (t: Throwable) {
            atomic.failWrite(output)
            throw t
        }
    }

    private fun sha256(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { byte ->
                "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
            }
        }

    private fun isWithin(child: File, parent: File): Boolean {
        val childPath = child.path
        val parentPath = parent.path
        return childPath == parentPath ||
            childPath.startsWith("$parentPath${File.separator}")
    }

    companion object {
        const val EVIDENCE_DIRECTORY = "evidence"
        const val MANIFEST_NAME = "evidence-manifest.json"
    }
}
