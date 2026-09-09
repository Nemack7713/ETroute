package org.nemack.universalfilelab.etroute

enum class NativeSpawnStage(val wire: Int) {
    OK(0),
    INVALID_ARGUMENT(1),
    OPEN_STDOUT(2),
    OPEN_STDERR(3),
    ERROR_PIPE(4),
    FORK(5),
    SETPGID(6),
    RESOURCE_LIMIT(7),
    CHDIR(8),
    DUP2_STDOUT(9),
    DUP2_STDERR(10),
    EXECVE(11),
    WAITPID(12),
    TIMEOUT_KILL(13);

    companion object {
        fun fromWire(value: Int): NativeSpawnStage =
            entries.firstOrNull { it.wire == value }
                ?: error("Unknown ETroute native spawn stage: $value")
    }
}

data class NativeResourceLimits(
    val cpuSeconds: Long = 0,
    val maxOpenFiles: Long = 0,
    val maxFileBytes: Long = 0
) {
    init {
        require(cpuSeconds >= 0) { "cpuSeconds cannot be negative" }
        require(maxOpenFiles >= 0) { "maxOpenFiles cannot be negative" }
        require(maxFileBytes >= 0) { "maxFileBytes cannot be negative" }
    }
}

data class PreparedLaunch(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String,
    val stdoutPath: String,
    val stderrPath: String,
    val timeoutMs: Long = 60_000,
    val terminateGraceMs: Long = 500,
    val limits: NativeResourceLimits = NativeResourceLimits(),
    val sessionId: String,
    val requestId: String,
    val originTag: String
) {
    init {
        require(executable.startsWith('/')) { "executable must be absolute" }
        require(workingDirectory.startsWith('/')) { "workingDirectory must be absolute" }
        require(stdoutPath.startsWith('/')) { "stdoutPath must be absolute" }
        require(stderrPath.startsWith('/')) { "stderrPath must be absolute" }
        require(timeoutMs > 0) { "timeoutMs must be greater than zero" }
        require(terminateGraceMs >= 0) { "terminateGraceMs cannot be negative" }
        require(sessionId.isNotBlank()) { "sessionId cannot be blank" }
        require(requestId.isNotBlank()) { "requestId cannot be blank" }
        require(originTag.isNotBlank()) { "originTag cannot be blank" }
    }
}

data class NativeRunResult(
    val exitCode: Int?,
    val signal: Int?,
    val timedOut: Boolean,
    val durationMs: Long,
    val spawnErrno: Int,
    val stage: NativeSpawnStage
) {
    val succeeded: Boolean
        get() = stage == NativeSpawnStage.OK &&
            !timedOut &&
            spawnErrno == 0 &&
            signal == null &&
            exitCode == 0
}

class JniNativeSupervisor {

    init {
        System.loadLibrary(LIBRARY_NAME)
        val actual = nativeAbiVersion()
        require(actual == EXPECTED_ABI_VERSION) {
            "ETroute JNI ABI mismatch: expected=$EXPECTED_ABI_VERSION actual=$actual"
        }
    }

    private external fun nativeAbiVersion(): Long

    private external fun nativeRun(
        executable: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        stdoutPath: String,
        stderrPath: String,
        timeoutMs: Long,
        terminateGraceMs: Long,
        cpuSeconds: Long,
        maxOpenFiles: Long,
        maxFileBytes: Long
    ): LongArray

    fun abiVersionForValidation(): Long = nativeAbiVersion()

    fun run(launch: PreparedLaunch): NativeRunResult {
        val raw = nativeRun(
            executable = launch.executable,
            argv = arrayOf(launch.executable) + launch.arguments.toTypedArray(),
            envp = launch.environment
                .toSortedMap()
                .map { (key, value) -> "$key=$value" }
                .toTypedArray(),
            cwd = launch.workingDirectory,
            stdoutPath = launch.stdoutPath,
            stderrPath = launch.stderrPath,
            timeoutMs = launch.timeoutMs,
            terminateGraceMs = launch.terminateGraceMs,
            cpuSeconds = launch.limits.cpuSeconds,
            maxOpenFiles = launch.limits.maxOpenFiles,
            maxFileBytes = launch.limits.maxFileBytes
        )

        require(raw.size == RESULT_FIELD_COUNT) {
            "ETroute JNI result size mismatch: expected=$RESULT_FIELD_COUNT actual=${raw.size}"
        }
        require(raw[FIELD_ABI_VERSION] == EXPECTED_ABI_VERSION) {
            "ETroute JNI result ABI mismatch: expected=$EXPECTED_ABI_VERSION actual=${raw[FIELD_ABI_VERSION]}"
        }

        return NativeRunResult(
            exitCode = raw[FIELD_EXIT_CODE].toInt().takeIf { it >= 0 },
            signal = raw[FIELD_SIGNAL].toInt().takeIf { it > 0 },
            timedOut = raw[FIELD_TIMED_OUT] != 0L,
            durationMs = raw[FIELD_DURATION_MS],
            spawnErrno = raw[FIELD_SPAWN_ERRNO].toInt(),
            stage = NativeSpawnStage.fromWire(raw[FIELD_SPAWN_STAGE].toInt())
        )
    }

    companion object {
        private const val LIBRARY_NAME = "etroute_native_supervisor"
        private const val EXPECTED_ABI_VERSION = 1L
        private const val RESULT_FIELD_COUNT = 7

        private const val FIELD_ABI_VERSION = 0
        private const val FIELD_EXIT_CODE = 1
        private const val FIELD_SIGNAL = 2
        private const val FIELD_TIMED_OUT = 3
        private const val FIELD_DURATION_MS = 4
        private const val FIELD_SPAWN_ERRNO = 5
        private const val FIELD_SPAWN_STAGE = 6
    }
}
