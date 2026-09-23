package org.nemack.universalfilelab.etroute

enum class TerminationClass {
    TERM_OK,
    EXIT_NONZERO,
    SIGNALLED,
    TIMEOUT,
    CANCELLED,
    EXEC_FAILED,
    SPAWN_FAILED,
    UNKNOWN
}

fun NativeRunResult.terminationClass(cancelled: Boolean = false): TerminationClass {
    if (cancelled) return TerminationClass.CANCELLED
    if (timedOut) return TerminationClass.TIMEOUT

    if (stage == NativeSpawnStage.EXECVE && spawnErrno != 0) {
        return TerminationClass.EXEC_FAILED
    }

    if (stage != NativeSpawnStage.OK && spawnErrno != 0) {
        return TerminationClass.SPAWN_FAILED
    }

    if (signal != null) return TerminationClass.SIGNALLED

    return when (exitCode) {
        0 -> TerminationClass.TERM_OK
        null -> TerminationClass.UNKNOWN
        else -> TerminationClass.EXIT_NONZERO
    }
}

data class RunReport(
    val sessionId: String,
    val requestId: String,
    val originTag: String,
    val termination: TerminationClass,
    val result: NativeRunResult,
    val packId: String? = null,
    val generationId: GenerationId? = null,
    val toolId: String? = null,
    val toolchainGenerations: Map<String, GenerationId> = emptyMap()
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId cannot be blank" }
        require(requestId.isNotBlank()) { "requestId cannot be blank" }
        require(originTag.isNotBlank()) { "originTag cannot be blank" }

        val toolMetadataCount = listOf(packId, generationId, toolId).count { it != null }
        require(toolMetadataCount == 0 || toolMetadataCount == 3) {
            "packId, generationId, and toolId must be supplied together"
        }
        toolchainGenerations.keys.forEach { key ->
            require(key.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
                "invalid toolchain generation key: $key"
            }
        }
    }

    companion object {
        fun from(
            launch: PreparedLaunch,
            result: NativeRunResult,
            resolvedTool: ResolvedTool? = null,
            cancelled: Boolean = false,
            toolchainGenerations: Map<String, GenerationId> = emptyMap()
        ): RunReport =
            RunReport(
                sessionId = launch.sessionId,
                requestId = launch.requestId,
                originTag = launch.originTag,
                termination = result.terminationClass(cancelled),
                result = result,
                packId = resolvedTool?.packId,
                generationId = resolvedTool?.generationId,
                toolId = resolvedTool?.descriptor?.toolId,
                toolchainGenerations = toolchainGenerations.toSortedMap()
            )
    }
}
