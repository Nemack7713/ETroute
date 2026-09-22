package org.nemack.universalfilelab.etroute

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

fun interface IdempotentSessionExportSink {
    fun export(outputDirectory: File, idempotencyKey: String): String?
}

data class SessionFinalizationReceipt(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val outputSha256: String,
    val exportedTo: String?,
    val finalizedAtEpochMs: Long
) {
    init {
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "unsupported finalization receipt schemaVersion=$schemaVersion"
        }
        require(sessionId.isNotBlank()) { "sessionId cannot be blank" }
        require(outputSha256.matches(Regex("[0-9a-f]{64}"))) {
            "outputSha256 must be a lowercase SHA-256 digest"
        }
        require(finalizedAtEpochMs >= 0) { "finalizedAtEpochMs cannot be negative" }
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

data class TransactionalFinalizationResult(
    val receipt: SessionFinalizationReceipt,
    val cleanup: SessionFinalizationResult,
    val reusedReceipt: Boolean
)

class TransactionalSessionFinalizer(
    private val finalizer: RuntimeSessionFinalizer = RuntimeSessionFinalizer()
) {
    fun finalize(
        request: SessionFinalizationRequest,
        journal: SessionJournalFile,
        exportSink: IdempotentSessionExportSink? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): TransactionalFinalizationResult {
        val paths = request.paths
        val receiptFile = File(paths.root, RECEIPT_NAME)
        val existingReceipt = readReceipt(receiptFile)

        val current = journal.initialize(paths.sessionId, nowEpochMs)
        if (current.state == SessionJournalState.FINALIZED) {
            require(existingReceipt != null) {
                "FINALIZED session is missing finalization receipt"
            }
            val cleanup = finalizer.finalize(
                request.copy(exportSink = null)
            )
            return TransactionalFinalizationResult(
                receipt = existingReceipt,
                cleanup = cleanup,
                reusedReceipt = true
            )
        }

        journal.transition(
            sessionId = paths.sessionId,
            next = SessionJournalState.FINALIZING,
            nowEpochMs = nowEpochMs
        )

        val outputHash = existingReceipt?.outputSha256 ?: hashDirectory(paths.output)
        val exportedTo = if (existingReceipt != null) {
            existingReceipt.exportedTo
        } else {
            val key = "${paths.sessionId}:$outputHash"
            exportSink?.export(paths.output, key)
        }

        val receipt = existingReceipt ?: SessionFinalizationReceipt(
            sessionId = paths.sessionId,
            outputSha256 = outputHash,
            exportedTo = exportedTo,
            finalizedAtEpochMs = nowEpochMs
        ).also { writeReceipt(receiptFile, it) }

        val cleanup = finalizer.finalize(
            request.copy(exportSink = null)
        )

        check(cleanup.cleaned) {
            "session cleanup did not reach a clean state"
        }

        journal.transition(
            sessionId = paths.sessionId,
            next = SessionJournalState.FINALIZED,
            nowEpochMs = nowEpochMs
        )

        return TransactionalFinalizationResult(
            receipt = receipt,
            cleanup = cleanup,
            reusedReceipt = existingReceipt != null
        )
    }

    private fun hashDirectory(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (!directory.exists()) return digest.digest().toHex()

        val root = directory.canonicalFile
        root.walkTopDown()
            .filter { it.isFile }
            .map { it.canonicalFile }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                digest.update(relative.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
                digest.update(file.length().toString().toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.update(0)
            }

        return digest.digest().toHex()
    }

    private fun readReceipt(file: File): SessionFinalizationReceipt? {
        if (!file.isFile && !File(file.path + ".bak").isFile) return null
        val atomic = AtomicFile(file)
        val json = atomic.openRead()
            .bufferedReader(StandardCharsets.UTF_8)
            .use { JSONObject(it.readText()) }

        return SessionFinalizationReceipt(
            schemaVersion = json.getInt("schemaVersion"),
            sessionId = json.getString("sessionId"),
            outputSha256 = json.getString("outputSha256"),
            exportedTo = if (json.isNull("exportedTo")) null else json.getString("exportedTo"),
            finalizedAtEpochMs = json.getLong("finalizedAtEpochMs")
        )
    }

    private fun writeReceipt(file: File, receipt: SessionFinalizationReceipt) {
        file.parentFile?.let { parent ->
            check(parent.mkdirs() || parent.isDirectory) {
                "Unable to create finalization receipt parent: ${parent.path}"
            }
        }

        val json = JSONObject().apply {
            put("schemaVersion", receipt.schemaVersion)
            put("sessionId", receipt.sessionId)
            put("outputSha256", receipt.outputSha256)
            if (receipt.exportedTo == null) {
                put("exportedTo", JSONObject.NULL)
            } else {
                put("exportedTo", receipt.exportedTo)
            }
            put("finalizedAtEpochMs", receipt.finalizedAtEpochMs)
        }.toString()

        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(json.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (t: Throwable) {
            atomic.failWrite(output)
            throw t
        }
    }

    companion object {
        const val RECEIPT_NAME = "finalization-receipt.json"
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }
