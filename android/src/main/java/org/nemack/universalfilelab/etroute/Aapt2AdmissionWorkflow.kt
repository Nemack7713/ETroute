package org.nemack.universalfilelab.etroute

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class Aapt2AdmissionWorkflowStatus {
    CREATED,
    VERSION_FAILED,
    COMPILE_FAILED,
    COMPILE_OUTPUT_MISSING,
    LINK_FAILED,
    LINK_OUTPUT_MISSING,
    SMOKE_VERIFIED
}

data class Aapt2AdmissionFixture(
    val resources: File,
    val manifest: File,
    val androidJar: File
)

data class Aapt2AdmissionWorkflowResult(
    val workflowId: String,
    val status: Aapt2AdmissionWorkflowStatus,
    val aapt2GenerationId: GenerationId,
    val versionReport: RunReport?,
    val compileReport: RunReport?,
    val linkReport: RunReport?,
    val linkedApk: File?,
    val linkedApkSha256: String?,
    val evidenceFile: File
) {
    val smokeVerified: Boolean
        get() = status == Aapt2AdmissionWorkflowStatus.SMOKE_VERIFIED
}

fun interface Aapt2StepExecutor {
    fun execute(
        paths: RuntimeSessionPaths,
        launch: PreparedLaunch,
        admittedExecutable: ResolvedTool
    ): EtRouteSessionRun
}

fun interface Aapt2StepFinalizer {
    fun finalize(paths: RuntimeSessionPaths, preserveOutput: Boolean)
}

class Aapt2AdmissionWorkflow(
    context: Context,
    private val executor: Aapt2StepExecutor = defaultExecutor(context),
    private val stepFinalizer: Aapt2StepFinalizer = defaultFinalizer(context)
) {
    private val appContext = context.applicationContext
    private val workspaces = EtRouteWorkspaceManager(appContext)
    private val workflowRootBase =
        File(appContext.filesDir, "etroute/workflows").canonicalFile

    fun run(
        aapt2: Aapt2RuntimeBinding,
        fixture: Aapt2AdmissionFixture,
        workflowId: String = "aapt2-${UUID.randomUUID()}",
        requestId: String = "aapt2-admission-${UUID.randomUUID()}",
        originTag: String = "Aapt2AdmissionWorkflow",
        timeoutMs: Long = 60_000
    ): Aapt2AdmissionWorkflowResult {
        requireIdentifier("workflowId", workflowId)
        require(requestId.isNotBlank()) { "requestId cannot be blank" }
        require(originTag.isNotBlank()) { "originTag cannot be blank" }
        require(timeoutMs > 0) { "timeoutMs must be greater than zero" }

        val resources = fixture.resources.canonicalFile
        val manifest = fixture.manifest.canonicalFile
        val androidJar = fixture.androidJar.canonicalFile
        require(resources.isDirectory) { "fixture resources directory does not exist" }
        require(manifest.isFile) { "fixture manifest does not exist" }
        require(androidJar.isFile) { "fixture android.jar does not exist" }

        val workflowRoot = File(workflowRootBase, workflowId).canonicalFile
        require(isWithin(workflowRoot, workflowRootBase)) {
            "workflow path escaped ETroute workflow root"
        }
        check(workflowRoot.mkdirs() || workflowRoot.isDirectory) {
            "Unable to create AAPT2 workflow root"
        }
        val evidenceFile = File(workflowRoot, EVIDENCE_NAME)

        writeEvidence(
            evidenceFile = evidenceFile,
            workflowId = workflowId,
            status = Aapt2AdmissionWorkflowStatus.CREATED,
            aapt2 = aapt2,
            versionReport = null,
            compileReport = null,
            linkReport = null,
            linkedApk = null,
            linkedApkSha256 = null
        )

        val versionPaths = workspaces.create("$workflowId-version")
        val versionLaunch = buildLaunch(
            paths = versionPaths,
            executable = aapt2.aapt2Tool.artifact,
            arguments = listOf("version"),
            requestId = "$requestId-version",
            originTag = originTag,
            timeoutMs = timeoutMs
        )
        val versionReport = executor.execute(
            versionPaths,
            versionLaunch,
            aapt2.aapt2Tool
        ).report
        stepFinalizer.finalize(versionPaths, false)

        if (versionReport.termination != TerminationClass.TERM_OK) {
            return finish(
                evidenceFile,
                workflowId,
                Aapt2AdmissionWorkflowStatus.VERSION_FAILED,
                aapt2,
                versionReport,
                null,
                null,
                null
            )
        }

        val compilePaths = workspaces.create("$workflowId-compile")
        val compileResources = File(compilePaths.input, "res")
        copyTree(resources, compileResources)
        val compiledArchive = File(compilePaths.output, "aapt2-compiled.zip").canonicalFile
        val compileLaunch = buildLaunch(
            paths = compilePaths,
            executable = aapt2.aapt2Tool.artifact,
            arguments = listOf(
                "compile",
                "--dir",
                compileResources.canonicalPath,
                "-o",
                compiledArchive.path
            ),
            requestId = "$requestId-compile",
            originTag = originTag,
            timeoutMs = timeoutMs
        )
        val compileReport = executor.execute(
            compilePaths,
            compileLaunch,
            aapt2.aapt2Tool
        ).report

        if (compileReport.termination != TerminationClass.TERM_OK) {
            stepFinalizer.finalize(compilePaths, false)
            return finish(
                evidenceFile,
                workflowId,
                Aapt2AdmissionWorkflowStatus.COMPILE_FAILED,
                aapt2,
                versionReport,
                compileReport,
                null,
                null
            )
        }

        if (!compiledArchive.isFile || compiledArchive.length() <= 0) {
            stepFinalizer.finalize(compilePaths, false)
            return finish(
                evidenceFile,
                workflowId,
                Aapt2AdmissionWorkflowStatus.COMPILE_OUTPUT_MISSING,
                aapt2,
                versionReport,
                compileReport,
                null,
                null
            )
        }

        val linkPaths = workspaces.create("$workflowId-link")
        val linkManifest = File(linkPaths.input, "AndroidManifest.xml")
        val linkAndroidJar = File(linkPaths.input, "android.jar")
        val linkCompiled = File(linkPaths.input, "aapt2-compiled.zip")
        manifest.copyTo(linkManifest, overwrite = false)
        androidJar.copyTo(linkAndroidJar, overwrite = false)
        compiledArchive.copyTo(linkCompiled, overwrite = false)
        stepFinalizer.finalize(compilePaths, false)

        val linkedApk = File(linkPaths.output, "aapt2-smoke.apk").canonicalFile
        val linkLaunch = buildLaunch(
            paths = linkPaths,
            executable = aapt2.aapt2Tool.artifact,
            arguments = listOf(
                "link",
                "-o",
                linkedApk.path,
                "-I",
                linkAndroidJar.canonicalPath,
                "--manifest",
                linkManifest.canonicalPath,
                linkCompiled.canonicalPath
            ),
            requestId = "$requestId-link",
            originTag = originTag,
            timeoutMs = timeoutMs
        )
        val linkReport = executor.execute(
            linkPaths,
            linkLaunch,
            aapt2.aapt2Tool
        ).report

        if (linkReport.termination != TerminationClass.TERM_OK) {
            stepFinalizer.finalize(linkPaths, true)
            return finish(
                evidenceFile,
                workflowId,
                Aapt2AdmissionWorkflowStatus.LINK_FAILED,
                aapt2,
                versionReport,
                compileReport,
                linkReport,
                null
            )
        }

        if (!linkedApk.isFile || linkedApk.length() <= 0) {
            stepFinalizer.finalize(linkPaths, true)
            return finish(
                evidenceFile,
                workflowId,
                Aapt2AdmissionWorkflowStatus.LINK_OUTPUT_MISSING,
                aapt2,
                versionReport,
                compileReport,
                linkReport,
                null
            )
        }

        val linkedHash = sha256(linkedApk)
        stepFinalizer.finalize(linkPaths, true)

        val result = Aapt2AdmissionWorkflowResult(
            workflowId = workflowId,
            status = Aapt2AdmissionWorkflowStatus.SMOKE_VERIFIED,
            aapt2GenerationId = aapt2.aapt2Tool.generationId,
            versionReport = versionReport,
            compileReport = compileReport,
            linkReport = linkReport,
            linkedApk = linkedApk,
            linkedApkSha256 = linkedHash,
            evidenceFile = evidenceFile
        )
        writeEvidence(
            evidenceFile,
            workflowId,
            result.status,
            aapt2,
            versionReport,
            compileReport,
            linkReport,
            linkedApk,
            linkedHash
        )
        return result
    }

    private fun finish(
        evidenceFile: File,
        workflowId: String,
        status: Aapt2AdmissionWorkflowStatus,
        aapt2: Aapt2RuntimeBinding,
        versionReport: RunReport?,
        compileReport: RunReport?,
        linkReport: RunReport?,
        linkedApk: File?
    ): Aapt2AdmissionWorkflowResult {
        val hash = linkedApk
            ?.takeIf { it.isFile && it.length() > 0 }
            ?.let(::sha256)
        val result = Aapt2AdmissionWorkflowResult(
            workflowId = workflowId,
            status = status,
            aapt2GenerationId = aapt2.aapt2Tool.generationId,
            versionReport = versionReport,
            compileReport = compileReport,
            linkReport = linkReport,
            linkedApk = linkedApk,
            linkedApkSha256 = hash,
            evidenceFile = evidenceFile
        )
        writeEvidence(
            evidenceFile,
            workflowId,
            status,
            aapt2,
            versionReport,
            compileReport,
            linkReport,
            linkedApk,
            hash
        )
        return result
    }

    private fun buildLaunch(
        paths: RuntimeSessionPaths,
        executable: File,
        arguments: List<String>,
        requestId: String,
        originTag: String,
        timeoutMs: Long
    ): PreparedLaunch =
        PreparedLaunch(
            executable = executable.canonicalPath,
            arguments = arguments,
            environment = mapOf(
                "HOME" to paths.tmp.path,
                "TMPDIR" to paths.tmp.path,
                "ETROUTE_SESSION_ID" to paths.sessionId
            ),
            workingDirectory = paths.tmp.path,
            stdoutPath = File(paths.diagnostics, "stdout.log").path,
            stderrPath = File(paths.diagnostics, "stderr.log").path,
            timeoutMs = timeoutMs,
            terminateGraceMs = 500,
            limits = NativeResourceLimits(
                cpuSeconds = 0,
                maxOpenFiles = 4096,
                maxFileBytes = 1L * 1024L * 1024L * 1024L,
                maxAddressSpaceBytes = 0
            ),
            sessionId = paths.sessionId,
            requestId = requestId,
            originTag = originTag
        )

    private fun writeEvidence(
        evidenceFile: File,
        workflowId: String,
        status: Aapt2AdmissionWorkflowStatus,
        aapt2: Aapt2RuntimeBinding,
        versionReport: RunReport?,
        compileReport: RunReport?,
        linkReport: RunReport?,
        linkedApk: File?,
        linkedApkSha256: String?
    ) {
        val payload = JSONObject().apply {
            put("schemaVersion", 1)
            put("kind", "aapt2-admission-workflow")
            put("workflowId", workflowId)
            put("status", status.name)
            put("packId", aapt2.aapt2Tool.packId)
            put("toolId", aapt2.aapt2Tool.descriptor.toolId)
            put("generationId", aapt2.aapt2Tool.generationId.value)
            put("trustGrantedByWorkflow", false)
            put("publishedByWorkflow", false)
            put("runtimeProbePassed", versionReport?.termination == TerminationClass.TERM_OK)
            put("compileSmokePassed", compileReport?.termination == TerminationClass.TERM_OK)
            put("linkSmokePassed", linkReport?.termination == TerminationClass.TERM_OK)
            putNullable("versionTermination", versionReport?.termination?.name)
            putNullable("compileTermination", compileReport?.termination?.name)
            putNullable("linkTermination", linkReport?.termination?.name)
            putNullable("linkedApk", linkedApk?.path)
            putNullable("linkedApkSha256", linkedApkSha256)
        }

        val atomic = AtomicFile(evidenceFile)
        evidenceFile.parentFile?.let { parent ->
            check(parent.mkdirs() || parent.isDirectory) {
                "Unable to create AAPT2 workflow evidence directory"
            }
        }
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

    private fun copyTree(sourceRoot: File, destinationRoot: File) {
        val root = sourceRoot.canonicalFile
        check(destinationRoot.mkdirs() || destinationRoot.isDirectory) {
            "Unable to create workflow resource destination"
        }

        root.walkTopDown().forEach { source ->
            val canonicalSource = source.canonicalFile
            require(isWithin(canonicalSource, root)) {
                "fixture resource escaped source root: ${source.path}"
            }
            val relative = source.relativeTo(root)
            if (relative.path.isEmpty()) return@forEach

            val destination = File(destinationRoot, relative.path).canonicalFile
            require(isWithin(destination, destinationRoot.canonicalFile)) {
                "fixture resource escaped destination root"
            }
            if (source.isDirectory) {
                check(destination.mkdirs() || destination.isDirectory) {
                    "Unable to create fixture resource directory"
                }
            } else {
                destination.parentFile?.let { parent ->
                    check(parent.mkdirs() || parent.isDirectory)
                }
                source.copyTo(destination, overwrite = false)
            }
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
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

    private fun requireIdentifier(name: String, value: String) {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,95}"))) {
            "invalid $name: $value"
        }
    }

    private fun isWithin(child: File, parent: File): Boolean {
        val childPath = child.path
        val parentPath = parent.path
        return childPath == parentPath ||
            childPath.startsWith("$parentPath${File.separator}")
    }

    companion object {
        private const val EVIDENCE_NAME = "aapt2-admission.json"

        private fun defaultExecutor(context: Context): Aapt2StepExecutor {
            val runner = EtRouteSessionRunner(context.applicationContext)
            return Aapt2StepExecutor { _, launch, executable ->
                runner.run(
                    launch = launch,
                    admittedExecutable = executable,
                    reportTool = executable
                )
            }
        }

        private fun defaultFinalizer(context: Context): Aapt2StepFinalizer {
            val sessionsRoot =
                File(context.applicationContext.filesDir, "etroute/sessions")
            val journalStore = SessionJournalStore(sessionsRoot)
            val finalizer = TransactionalSessionFinalizer()
            return Aapt2StepFinalizer { paths, preserveOutput ->
                finalizer.finalize(
                    request = SessionFinalizationRequest(
                        paths = paths,
                        preserveOutput = preserveOutput,
                        retainDiagnostics = true
                    ),
                    journal = journalStore.journalFor(paths.sessionId)
                )
            }
        }
    }
}

private fun JSONObject.putNullable(name: String, value: String?) {
    if (value == null) put(name, JSONObject.NULL) else put(name, value)
}
