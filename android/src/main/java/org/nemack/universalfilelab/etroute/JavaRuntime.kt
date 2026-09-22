package org.nemack.universalfilelab.etroute

import java.io.File

data class JavaRuntimeBinding(
    val lease: GenerationLease,
    val javaTool: ResolvedTool,
    val javaHome: File
) : AutoCloseable {
    init {
        require(javaTool.descriptor.kind == RuntimePackToolKind.NATIVE_EXECUTABLE) {
            "Java runtime tool must be a native executable"
        }
        require(javaTool.artifact.isFile && javaTool.artifact.canExecute()) {
            "Java runtime executable is not executable: ${javaTool.artifact.path}"
        }
    }

    override fun close() {
        lease.close()
    }
}

class JavaRuntimeResolver(
    private val registry: RuntimePackRegistry,
    private val javaPackId: String = DEFAULT_JAVA_PACK_ID,
    private val javaToolId: String = DEFAULT_JAVA_TOOL_ID
) {
    fun resolveFor(consumer: AdmittedRuntimePack): JavaRuntimeBinding {
        val dependency = consumer.manifest.dependencies
            .firstOrNull { it.packId == javaPackId }
            ?: error(
                "RuntimePack ${consumer.manifest.packId} does not declare Java dependency $javaPackId"
            )

        val lease = registry.acquire(
            packId = javaPackId,
            generationId = dependency.requiredGenerationId
        )

        try {
            val javaTool = lease.resolveTool(javaToolId)
            require(javaTool.descriptor.kind == RuntimePackToolKind.NATIVE_EXECUTABLE) {
                "Java runtime tool $javaToolId must be NATIVE_EXECUTABLE"
            }
            val javaHome = inferJavaHome(javaTool.artifact)
            return JavaRuntimeBinding(
                lease = lease,
                javaTool = javaTool,
                javaHome = javaHome
            )
        } catch (t: Throwable) {
            lease.close()
            throw t
        }
    }

    private fun inferJavaHome(javaExecutable: File): File {
        val bin = javaExecutable.parentFile
            ?: error("Java executable has no parent directory")
        require(bin.name == "bin") {
            "Java executable must live under <java-home>/bin: ${javaExecutable.path}"
        }
        return bin.parentFile?.canonicalFile
            ?: error("Java executable has no Java home parent")
    }

    companion object {
        const val DEFAULT_JAVA_PACK_ID = "org.etroute.java"
        const val DEFAULT_JAVA_TOOL_ID = "java"
    }
}

data class JavaJarLaunchPlan(
    val preparedLaunch: PreparedLaunch,
    val javaGenerationId: GenerationId,
    val jarGenerationId: GenerationId,
    val jarToolId: String
)

class JavaJarLaunchPlanner {
    fun plan(
        session: RuntimeSessionPaths,
        java: JavaRuntimeBinding,
        jarTool: ResolvedTool,
        arguments: List<String>,
        requestId: String,
        originTag: String,
        timeoutMs: Long,
        terminateGraceMs: Long = 500,
        jvmArguments: List<String> = DEFAULT_JVM_ARGUMENTS
    ): JavaJarLaunchPlan {
        require(jarTool.descriptor.kind == RuntimePackToolKind.JAVA_JAR) {
            "Jar tool ${jarTool.descriptor.toolId} must be JAVA_JAR"
        }
        require(jarTool.artifact.isFile) {
            "Jar artifact does not exist: ${jarTool.artifact.path}"
        }
        require(timeoutMs > 0) { "timeoutMs must be greater than zero" }

        val stdout = File(session.diagnostics, "stdout.log")
        val stderr = File(session.diagnostics, "stderr.log")

        val launch = PreparedLaunch(
            executable = java.javaTool.artifact.canonicalPath,
            arguments = buildList {
                addAll(jvmArguments)
                add("-jar")
                add(jarTool.artifact.canonicalPath)
                addAll(arguments)
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

        return JavaJarLaunchPlan(
            preparedLaunch = launch,
            javaGenerationId = java.javaTool.generationId,
            jarGenerationId = jarTool.generationId,
            jarToolId = jarTool.descriptor.toolId
        )
    }

    companion object {
        val DEFAULT_JVM_ARGUMENTS = listOf(
            "-Dfile.encoding=utf-8",
            "-Djdk.util.zip.disableZip64ExtraFieldValidation=true",
            "-Djdk.nio.zipfs.allowDotZipEntry=true"
        )
    }
}
