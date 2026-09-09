package org.nemack.universalfilelab.etroute

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** ETroute-owned app-private session layout. */
data class RuntimeSessionPaths(
    val sessionId: String,
    val root: File,
    val input: File,
    val tmp: File,
    val output: File,
    val diagnostics: File
)

class EtRouteWorkspaceManager(context: Context) {
    private val sessionsRoot = File(context.applicationContext.filesDir, "etroute/sessions").canonicalFile

    fun create(sessionId: String): RuntimeSessionPaths {
        require(sessionId.isNotBlank()) { "sessionId cannot be blank" }
        require(sessionId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "sessionId contains unsupported characters"
        }

        val root = File(sessionsRoot, sessionId).canonicalFile
        require(isWithin(root, sessionsRoot)) { "session path escaped ETroute sessions root" }

        val input = File(root, "workspace/input").canonicalFile
        val tmp = File(root, "workspace/tmp").canonicalFile
        val output = File(root, "workspace/output").canonicalFile
        val diagnostics = File(root, "diagnostics").canonicalFile

        listOf(input, tmp, output, diagnostics).forEach { dir ->
            check(dir.mkdirs() || dir.isDirectory) { "Unable to create ${dir.path}" }
        }

        return RuntimeSessionPaths(
            sessionId = sessionId,
            root = root,
            input = input,
            tmp = tmp,
            output = output,
            diagnostics = diagnostics
        )
    }

    private fun isWithin(child: File, parent: File): Boolean {
        val childPath = child.path
        val parentPath = parent.path
        return childPath == parentPath || childPath.startsWith("$parentPath${File.separator}")
    }
}

/**
 * Canonicalizes and validates every filesystem-sensitive launch field before JNI.
 * The post-fork child therefore never performs policy decisions or path discovery.
 */
class PreparedLaunchValidator(
    context: Context,
    private val session: RuntimeSessionPaths
) {
    private val appContext = context.applicationContext
    private val allowedExecutableRoots: List<File> = buildList {
        add(File("/system/bin").canonicalFile)
        add(File("/system/xbin").canonicalFile)
        add(File(appContext.applicationInfo.nativeLibraryDir).canonicalFile)
        add(File(appContext.filesDir, "etroute/runtime-packs").canonicalFile)
    }

    fun validate(launch: PreparedLaunch): PreparedLaunch {
        require(launch.sessionId == session.sessionId) { "PreparedLaunch session mismatch" }

        val executable = File(launch.executable).canonicalFile
        require(executable.isFile) { "Executable does not exist: ${executable.path}" }
        require(executable.canExecute()) { "Executable is not executable: ${executable.path}" }
        require(allowedExecutableRoots.any { isWithin(executable, it) }) {
            "Executable is outside approved ETroute/system roots: ${executable.path}"
        }

        val cwd = File(launch.workingDirectory).canonicalFile
        require(cwd.isDirectory) { "Working directory does not exist: ${cwd.path}" }
        require(isWithin(cwd, session.root)) {
            "Working directory escaped ETroute session: ${cwd.path}"
        }

        val stdout = File(launch.stdoutPath).canonicalFile
        val stderr = File(launch.stderrPath).canonicalFile
        require(isWithin(stdout, session.diagnostics)) { "stdout must stay under diagnostics" }
        require(isWithin(stderr, session.diagnostics)) { "stderr must stay under diagnostics" }
        require(stdout != stderr) { "stdout and stderr must use separate files" }

        validateEnvironment(launch.environment)

        return launch.copy(
            executable = executable.path,
            workingDirectory = cwd.path,
            stdoutPath = stdout.path,
            stderrPath = stderr.path
        )
    }

    private fun validateEnvironment(environment: Map<String, String>) {
        require(environment.size <= 128) { "Environment contains too many entries" }
        environment.forEach { (key, value) ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid environment key: $key" }
            require('\u0000' !in key && '\u0000' !in value) { "Environment contains NUL" }
            require(value.length <= 32_768) { "Environment value is too large: $key" }
        }
    }

    private fun isWithin(child: File, parent: File): Boolean {
        val childPath = child.path
        val parentPath = parent.path
        return childPath == parentPath || childPath.startsWith("$parentPath${File.separator}")
    }
}

data class EtRouteSessionRun(
    val paths: RuntimeSessionPaths,
    val launch: PreparedLaunch,
    val result: NativeRunResult
)

/** Kotlin orchestration directly above the frozen JNI supervisor boundary. */
class EtRouteSessionRunner(
    context: Context,
    private val supervisor: JniNativeSupervisor = JniNativeSupervisor()
) {
    private val appContext = context.applicationContext
    private val workspaces = EtRouteWorkspaceManager(appContext)

    fun run(launch: PreparedLaunch): EtRouteSessionRun {
        val paths = workspaces.create(launch.sessionId)
        val prepared = PreparedLaunchValidator(appContext, paths).validate(launch)

        val result = supervisor.run(prepared)
        Log.i(
            LOG_TAG,
            "session=${prepared.sessionId} request=${prepared.requestId} " +
                "origin=${prepared.originTag} stage=${result.stage} errno=${result.spawnErrno} " +
                "exit=${result.exitCode} signal=${result.signal} timeout=${result.timedOut} " +
                "durationMs=${result.durationMs}"
        )

        return EtRouteSessionRun(paths, prepared, result)
    }

    /** Deterministic device smoke which exercises JNI without depending on RuntimePack Python. */
    fun runSystemSmoke(
        sessionId: String = "smoke-${UUID.randomUUID()}",
        timeoutMs: Long = 10_000
    ): EtRouteSessionRun {
        val paths = workspaces.create(sessionId)
        val stdout = File(paths.diagnostics, "stdout.log")
        val stderr = File(paths.diagnostics, "stderr.log")
        val artifact = File(paths.output, "etroute-smoke.txt")
        val command = "printf ETROUTE_ANDROID_SMOKE_OK; printf ETROUTE_ANDROID_SMOKE_OK > '${artifact.path}'"

        val launch = PreparedLaunch(
            executable = "/system/bin/sh",
            arguments = listOf("-c", command),
            environment = mapOf(
                "PATH" to "/system/bin",
                "HOME" to paths.tmp.path,
                "TMPDIR" to paths.tmp.path,
                "ETROUTE_SESSION_ID" to sessionId
            ),
            workingDirectory = paths.tmp.path,
            stdoutPath = stdout.path,
            stderrPath = stderr.path,
            timeoutMs = timeoutMs,
            terminateGraceMs = 500,
            limits = NativeResourceLimits(
                cpuSeconds = 0,
                maxOpenFiles = 1024,
                maxFileBytes = 512L * 1024L * 1024L
            ),
            sessionId = sessionId,
            requestId = "smoke-${UUID.randomUUID()}",
            originTag = "EtRouteSessionRunner:system-smoke"
        )

        return run(launch)
    }

    companion object {
        private const val LOG_TAG = "ETrouteNative"
    }
}

fun interface SessionExportSink {
    fun export(outputDirectory: File): String?
}

data class SessionFinalizationRequest(
    val paths: RuntimeSessionPaths,
    val preserveOutput: Boolean = true,
    val exportSink: SessionExportSink? = null,
    val retainDiagnostics: Boolean = false,
    val diagnosticNames: Set<String> = setOf(
        "etroute-final.json",
        "crash.log",
        "stdout.log",
        "stderr.log"
    ),
    val maxDiagnosticBytesPerFile: Long = 2L * 1024L * 1024L
)

data class SessionFinalizationResult(
    val sessionId: String,
    val outputPreserved: Boolean,
    val exportedTo: String?,
    val cleaned: Boolean,
    val retainedDiagnostics: List<String>
)

class RuntimeSessionFinalizer {
    fun finalize(request: SessionFinalizationRequest): SessionFinalizationResult {
        require(request.maxDiagnosticBytesPerFile >= 0) { "maxDiagnosticBytesPerFile cannot be negative" }

        val exportedTo = request.exportSink?.export(request.paths.output)

        request.paths.input.deleteRecursively()
        request.paths.tmp.deleteRecursively()

        if (!request.preserveOutput) {
            request.paths.output.deleteRecursively()
        }

        val retained = if (request.retainDiagnostics) {
            retainBoundedDiagnostics(request)
        } else {
            request.paths.diagnostics.deleteRecursively()
            emptyList()
        }

        val cleaned = !request.paths.input.exists() &&
            !request.paths.tmp.exists() &&
            (request.preserveOutput || !request.paths.output.exists())

        return SessionFinalizationResult(
            sessionId = request.paths.sessionId,
            outputPreserved = request.preserveOutput && request.paths.output.isDirectory,
            exportedTo = exportedTo,
            cleaned = cleaned,
            retainedDiagnostics = retained
        )
    }

    private fun retainBoundedDiagnostics(request: SessionFinalizationRequest): List<String> {
        if (!request.paths.diagnostics.isDirectory) return emptyList()

        request.paths.diagnostics.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || file.name !in request.diagnosticNames) {
                file.deleteRecursively()
            } else if (file.length() > request.maxDiagnosticBytesPerFile) {
                file.writeBytes(readPrefix(file, request.maxDiagnosticBytesPerFile))
            }
        }

        return request.paths.diagnostics.listFiles().orEmpty()
            .filter { it.isFile }
            .map { it.name }
            .sorted()
    }

    private fun readPrefix(file: File, maxBytes: Long): ByteArray {
        if (maxBytes <= 0) return ByteArray(0)
        val bounded = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val output = ByteArrayOutputStream(bounded.coerceAtMost(64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var remaining = bounded

        file.inputStream().use { input ->
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }

        return output.toByteArray()
    }
}
