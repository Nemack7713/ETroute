package org.nemack.universalfilelab.etroute

import java.io.File

data class Aapt2SmokePlan(
    val versionLaunch: PreparedLaunch,
    val compileLaunch: PreparedLaunch,
    val linkLaunch: PreparedLaunch,
    val aapt2GenerationId: GenerationId,
    val compiledArchive: File,
    val linkedApk: File
)

class Aapt2SmokePlanner {
    fun plan(
        session: RuntimeSessionPaths,
        aapt2: Aapt2RuntimeBinding,
        resourceDirectory: File,
        manifestFile: File,
        androidJar: File,
        requestId: String,
        originTag: String,
        timeoutMs: Long = 60_000,
        terminateGraceMs: Long = 500
    ): Aapt2SmokePlan {
        require(timeoutMs > 0) { "timeoutMs must be greater than zero" }

        val sessionRoot = session.root.canonicalFile
        val resources = resourceDirectory.canonicalFile
        val manifest = manifestFile.canonicalFile
        val platformJar = androidJar.canonicalFile

        require(isWithin(resources, sessionRoot) && resources.isDirectory) {
            "resourceDirectory must be an existing directory inside the ETroute session"
        }
        require(isWithin(manifest, sessionRoot) && manifest.isFile) {
            "manifestFile must be an existing file inside the ETroute session"
        }
        require(platformJar.isFile) {
            "androidJar must be an existing file"
        }

        val compiled = File(session.tmp, "aapt2-compiled.zip").canonicalFile
        val linked = File(session.output, "aapt2-smoke.apk").canonicalFile
        val executable = aapt2.aapt2Tool.artifact.canonicalPath

        fun launch(
            suffix: String,
            arguments: List<String>,
            stdoutName: String,
            stderrName: String
        ): PreparedLaunch =
            PreparedLaunch(
                executable = executable,
                arguments = arguments,
                environment = mapOf(
                    "HOME" to session.tmp.path,
                    "TMPDIR" to session.tmp.path,
                    "ETROUTE_SESSION_ID" to session.sessionId
                ),
                workingDirectory = session.tmp.path,
                stdoutPath = File(session.diagnostics, stdoutName).path,
                stderrPath = File(session.diagnostics, stderrName).path,
                timeoutMs = timeoutMs,
                terminateGraceMs = terminateGraceMs,
                limits = NativeResourceLimits(
                    cpuSeconds = 0,
                    maxOpenFiles = 4096,
                    maxFileBytes = 1L * 1024L * 1024L * 1024L,
                    maxAddressSpaceBytes = 0
                ),
                sessionId = session.sessionId,
                requestId = "$requestId:$suffix",
                originTag = originTag
            )

        val versionLaunch = launch(
            suffix = "version",
            arguments = listOf("version"),
            stdoutName = "aapt2-version.stdout.log",
            stderrName = "aapt2-version.stderr.log"
        )

        val compileLaunch = launch(
            suffix = "compile",
            arguments = listOf(
                "compile",
                "--dir",
                resources.path,
                "-o",
                compiled.path
            ),
            stdoutName = "aapt2-compile.stdout.log",
            stderrName = "aapt2-compile.stderr.log"
        )

        val linkLaunch = launch(
            suffix = "link",
            arguments = listOf(
                "link",
                "-o",
                linked.path,
                "-I",
                platformJar.path,
                "--manifest",
                manifest.path,
                compiled.path
            ),
            stdoutName = "aapt2-link.stdout.log",
            stderrName = "aapt2-link.stderr.log"
        )

        return Aapt2SmokePlan(
            versionLaunch = versionLaunch,
            compileLaunch = compileLaunch,
            linkLaunch = linkLaunch,
            aapt2GenerationId = aapt2.aapt2Tool.generationId,
            compiledArchive = compiled,
            linkedApk = linked
        )
    }
}

private fun isWithin(child: File, parent: File): Boolean {
    val childPath = child.path
    val parentPath = parent.path
    return childPath == parentPath || childPath.startsWith("$parentPath${File.separator}")
}
