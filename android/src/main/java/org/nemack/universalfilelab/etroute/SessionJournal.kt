package org.nemack.universalfilelab.etroute

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

enum class SessionJournalState {
    CREATED,
    PREPARED,
    RUNNING,
    TERMINATED,
    FINALIZING,
    FINALIZED
}

data class SessionJournalRecord(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val state: SessionJournalState,
    val requestId: String? = null,
    val termination: TerminationClass? = null,
    val packId: String? = null,
    val generationId: GenerationId? = null,
    val toolId: String? = null,
    val updatedAtEpochMs: Long
) {
    init {
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "unsupported session journal schemaVersion=$schemaVersion"
        }
        require(sessionId.isNotBlank()) { "sessionId cannot be blank" }
        require(updatedAtEpochMs >= 0) { "updatedAtEpochMs cannot be negative" }

        val toolMetadataCount = listOf(packId, generationId, toolId).count { it != null }
        require(toolMetadataCount == 0 || toolMetadataCount == 3) {
            "packId, generationId, and toolId must be supplied together"
        }
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

data class SessionRecoveryScan(
    val recoverable: List<SessionJournalRecord>,
    val corruptJournals: List<String>
)

class SessionJournalFile(private val file: File) {
    private val atomic = AtomicFile(file)

    fun read(): SessionJournalRecord? {
        if (!file.isFile && !File(file.path + ".bak").isFile) return null
        val text = atomic.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return decode(text)
    }

    fun initialize(
        sessionId: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): SessionJournalRecord {
        val existing = read()
        if (existing != null) {
            require(existing.sessionId == sessionId) {
                "session journal belongs to ${existing.sessionId}, not $sessionId"
            }
            return existing
        }

        val created = SessionJournalRecord(
            sessionId = sessionId,
            state = SessionJournalState.CREATED,
            updatedAtEpochMs = nowEpochMs
        )
        write(created)
        return created
    }

    fun transition(
        sessionId: String,
        next: SessionJournalState,
        requestId: String? = null,
        termination: TerminationClass? = null,
        resolvedTool: ResolvedTool? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): SessionJournalRecord {
        val current = initialize(sessionId, nowEpochMs)
        requireTransition(current.state, next)

        val updated = current.copy(
            state = next,
            requestId = requestId ?: current.requestId,
            termination = termination ?: current.termination,
            packId = resolvedTool?.packId ?: current.packId,
            generationId = resolvedTool?.generationId ?: current.generationId,
            toolId = resolvedTool?.descriptor?.toolId ?: current.toolId,
            updatedAtEpochMs = nowEpochMs
        )
        write(updated)
        return updated
    }

    fun write(record: SessionJournalRecord) {
        file.parentFile?.let { parent ->
            check(parent.mkdirs() || parent.isDirectory) {
                "Unable to create session journal parent: ${parent.path}"
            }
        }

        val output = atomic.startWrite()
        try {
            output.write(encode(record).toByteArray(StandardCharsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (t: Throwable) {
            atomic.failWrite(output)
            throw t
        }
    }

    private fun requireTransition(
        current: SessionJournalState,
        next: SessionJournalState
    ) {
        if (current == next) return

        val allowed = when (current) {
            SessionJournalState.CREATED ->
                setOf(
                    SessionJournalState.PREPARED,
                    SessionJournalState.TERMINATED,
                    SessionJournalState.FINALIZING
                )

            SessionJournalState.PREPARED ->
                setOf(
                    SessionJournalState.RUNNING,
                    SessionJournalState.TERMINATED,
                    SessionJournalState.FINALIZING
                )

            SessionJournalState.RUNNING ->
                setOf(
                    SessionJournalState.TERMINATED,
                    SessionJournalState.FINALIZING
                )

            SessionJournalState.TERMINATED ->
                setOf(SessionJournalState.FINALIZING)

            SessionJournalState.FINALIZING ->
                setOf(SessionJournalState.FINALIZED)

            SessionJournalState.FINALIZED ->
                emptySet()
        }

        require(next in allowed) {
            "invalid session journal transition: $current -> $next"
        }
    }

    private fun encode(record: SessionJournalRecord): String =
        JSONObject().apply {
            put("schemaVersion", record.schemaVersion)
            put("sessionId", record.sessionId)
            put("state", record.state.name)
            putNullable("requestId", record.requestId)
            putNullable("termination", record.termination?.name)
            putNullable("packId", record.packId)
            putNullable("generationId", record.generationId?.value)
            putNullable("toolId", record.toolId)
            put("updatedAtEpochMs", record.updatedAtEpochMs)
        }.toString()

    private fun decode(text: String): SessionJournalRecord {
        val json = JSONObject(text)
        val schemaVersion = json.getInt("schemaVersion")
        require(schemaVersion == SessionJournalRecord.SUPPORTED_SCHEMA_VERSION) {
            "unsupported session journal schemaVersion=$schemaVersion"
        }

        return SessionJournalRecord(
            schemaVersion = schemaVersion,
            sessionId = json.getString("sessionId"),
            state = SessionJournalState.valueOf(json.getString("state")),
            requestId = json.optionalString("requestId"),
            termination = json.optionalString("termination")
                ?.let(TerminationClass::valueOf),
            packId = json.optionalString("packId"),
            generationId = json.optionalString("generationId")
                ?.let(::GenerationId),
            toolId = json.optionalString("toolId"),
            updatedAtEpochMs = json.getLong("updatedAtEpochMs")
        )
    }
}

class SessionJournalStore(private val sessionsRoot: File) {
    private val canonicalSessionsRoot = sessionsRoot.canonicalFile

    fun journalFor(sessionId: String): SessionJournalFile {
        require(sessionId.isNotBlank()) { "sessionId cannot be blank" }
        val sessionRoot = File(canonicalSessionsRoot, sessionId).canonicalFile
        require(isWithin(sessionRoot, canonicalSessionsRoot)) {
            "session journal path escaped sessions root"
        }
        return SessionJournalFile(File(sessionRoot, JOURNAL_NAME))
    }

    fun scanRecoverable(): SessionRecoveryScan {
        if (!canonicalSessionsRoot.isDirectory) {
            return SessionRecoveryScan(emptyList(), emptyList())
        }

        val recoverable = mutableListOf<SessionJournalRecord>()
        val corrupt = mutableListOf<String>()

        canonicalSessionsRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .forEach { sessionRoot ->
                val file = File(sessionRoot, JOURNAL_NAME)
                if (!file.isFile && !File(file.path + ".bak").isFile) return@forEach

                try {
                    val record = SessionJournalFile(file).read() ?: return@forEach
                    if (record.state != SessionJournalState.FINALIZED) {
                        recoverable += record
                    }
                } catch (_: RuntimeException) {
                    corrupt += file.canonicalPath
                }
            }

        return SessionRecoveryScan(
            recoverable = recoverable.sortedBy { it.sessionId },
            corruptJournals = corrupt.sorted()
        )
    }

    companion object {
        const val JOURNAL_NAME = "session-journal.json"
    }
}

private fun JSONObject.putNullable(name: String, value: String?) {
    if (value == null) put(name, JSONObject.NULL) else put(name, value)
}

private fun JSONObject.optionalString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return getString(name)
}

private fun isWithin(child: File, parent: File): Boolean {
    val childPath = child.path
    val parentPath = parent.path
    return childPath == parentPath || childPath.startsWith("$parentPath${File.separator}")
}
