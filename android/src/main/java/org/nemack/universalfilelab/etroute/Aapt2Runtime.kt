package org.nemack.universalfilelab.etroute

import java.io.File

data class Aapt2RuntimeBinding(
    val lease: GenerationLease,
    val aapt2Tool: ResolvedTool
) : AutoCloseable {
    init {
        require(aapt2Tool.descriptor.kind == RuntimePackToolKind.NATIVE_EXECUTABLE) {
            "AAPT2 tool must be a native executable"
        }
        require(aapt2Tool.artifact.isFile && aapt2Tool.artifact.canExecute()) {
            "AAPT2 executable is not executable: ${aapt2Tool.artifact.path}"
        }
    }

    override fun close() {
        lease.close()
    }
}

class Aapt2RuntimeResolver(
    private val registry: RuntimePackRegistry,
    private val aapt2PackId: String = DEFAULT_AAPT2_PACK_ID,
    private val aapt2ToolId: String = DEFAULT_AAPT2_TOOL_ID
) {
    fun resolveFor(consumer: AdmittedRuntimePack): Aapt2RuntimeBinding {
        val dependency = consumer.manifest.dependencies
            .firstOrNull { it.packId == aapt2PackId }
            ?: error(
                "RuntimePack ${consumer.manifest.packId} does not declare AAPT2 dependency $aapt2PackId"
            )

        val lease = registry.acquire(
            packId = aapt2PackId,
            generationId = dependency.requiredGenerationId
        )

        try {
            val tool = lease.resolveTool(aapt2ToolId)
            require(tool.descriptor.kind == RuntimePackToolKind.NATIVE_EXECUTABLE) {
                "AAPT2 tool $aapt2ToolId must be NATIVE_EXECUTABLE"
            }
            return Aapt2RuntimeBinding(
                lease = lease,
                aapt2Tool = tool
            )
        } catch (t: Throwable) {
            lease.close()
            throw t
        }
    }

    companion object {
        const val DEFAULT_AAPT2_PACK_ID = "org.etroute.aapt2"
        const val DEFAULT_AAPT2_TOOL_ID = "aapt2"
    }
}

data class ApktoolLaunchPlan(
    val preparedLaunch: PreparedLaunch,
    val apktoolGenerationId: GenerationId,
    val javaGenerationId: GenerationId,
    val aapt2GenerationId: GenerationId
)

class ApktoolLaunchPlanner {
    fun plan(
        session: RuntimeSessionPaths,
        java: JavaRuntimeBinding,
        aapt2: Aapt2RuntimeBinding,
        apktool: ResolvedTool,
        arguments: List<String>,
        requestId: String,
        originTag: String,
        timeoutMs: Long,
        terminateGraceMs: Long = 500,
        jvmArguments: List<String> = JavaJarLaunchPlanner.DEFAULT_JVM_ARGUMENTS
    ): ApktoolLaunchPlan {
        require(apktool.descriptor.kind == RuntimePackToolKind.JAVA_JAR) {
            "Apktool descriptor must be JAVA_JAR"
        }
        require(apktool.artifact.isFile) {
            "Apktool JAR does not exist: ${apktool.artifact.path}"
        }
        require(timeoutMs > 0) { "timeoutMs must be greater than zero" }

        val stdout = File(session.diagnostics, "stdout.log")
        val stderr = File(session.diagnostics, "stderr.log")

        val launch = PreparedLaunch(
            executable = java.javaTool.artifact.canonicalPath,
            arguments = buildList {
                addAll(jvmArguments)
                add("-jar")
                add(apktool.artifact.canonicalPath)
                addAll(arguments)
                add("--aapt")
                add(aapt2.aapt2Tool.artifact.canonicalPath)
            },
            environment = mapOf(
                "HOME" to session.tmp.path,
                "TMPDIR" to session.tmp.path,
                "JAVA_HOME" to java.javaHome.path,
                "ETROUTE_SESSION_ID" to session.sessionId
            ),
            workingDirectory = session.tmp.path,
            stdoutPath = stdout.path,
            stderrPath = stderr.path,
            timeoutMs = timeoutMs,
            terminateGraceMs = terminateGraceMs,
            limits = NativeResourceLimits(
                cpuSeconds = 0,
                maxOpenFiles = 4096,
                maxFileBytes = 1L * 1024L * 1024L * 1024L,
                maxAddressSpaceBytes = 0
            ),
            sessionId = session.sessionId,
            requestId = requestId,
            originTag = originTag
        )

        return ApktoolLaunchPlan(
            preparedLaunch = launch,
            apktoolGenerationId = apktool.generationId,
            javaGenerationId = java.javaTool.generationId,
            aapt2GenerationId = aapt2.aapt2Tool.generationId
        )
    }
}
