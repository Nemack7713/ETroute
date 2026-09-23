package org.nemack.universalfilelab.etroute

import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class GenerationId(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "GenerationId must be a lowercase SHA-256 hex digest"
        }
    }

    override fun toString(): String = value
}

enum class RuntimePackProvenance {
    BUILTIN_APK,
    EXTERNAL_SIGNED,
    EXTERNAL_VERIFIED
}

enum class RuntimePackToolKind {
    NATIVE_EXECUTABLE,
    JAVA_JAR
}

data class RuntimePackSource(
    val repository: String,
    val revision: String,
    val assetName: String,
    val assetSha256: String
) {
    init {
        require(repository.isNotBlank()) { "source repository cannot be blank" }
        require(revision.isNotBlank()) { "source revision cannot be blank" }
        require(assetName.isNotBlank()) { "source assetName cannot be blank" }
        requireSha256("source assetSha256", assetSha256)
    }
}

data class RuntimePackArtifact(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val executable: Boolean = false
) {
    init {
        requireRelativePath("artifact path", path)
        requireSha256("artifact sha256", sha256)
        require(sizeBytes >= 0) { "artifact sizeBytes cannot be negative" }
    }
}

data class RuntimePackDependency(
    val packId: String,
    val requiredGenerationId: GenerationId? = null
) {
    init {
        requirePackId(packId)
    }
}

data class ToolDescriptor(
    val toolId: String,
    val kind: RuntimePackToolKind,
    val artifactPath: String
) {
    init {
        requireIdentifier("toolId", toolId)
        requireRelativePath("tool artifactPath", artifactPath)
    }
}

data class RuntimePackManifest(
    val schemaVersion: Int = 1,
    val packId: String,
    val version: String,
    val provenance: RuntimePackProvenance,
    val minApi: Int = 24,
    val abis: Set<String> = emptySet(),
    val source: RuntimePackSource? = null,
    val artifacts: List<RuntimePackArtifact>,
    val dependencies: List<RuntimePackDependency> = emptyList(),
    val tools: List<ToolDescriptor>
) {
    init {
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "unsupported RuntimePack schemaVersion=$schemaVersion"
        }
        requirePackId(packId)
        require(version.isNotBlank()) { "RuntimePack version cannot be blank" }
        require(minApi >= 1) { "RuntimePack minApi must be positive" }
        require(artifacts.isNotEmpty()) { "RuntimePack must declare at least one artifact" }
        require(tools.isNotEmpty()) { "RuntimePack must declare at least one tool" }

        val artifactPaths = artifacts.map { it.path }
        require(artifactPaths.distinct().size == artifactPaths.size) {
            "RuntimePack artifact paths must be unique"
        }

        val toolIds = tools.map { it.toolId }
        require(toolIds.distinct().size == toolIds.size) {
            "RuntimePack toolIds must be unique"
        }

        val artifactSet = artifactPaths.toSet()
        tools.forEach { tool ->
            require(tool.artifactPath in artifactSet) {
                "Tool ${tool.toolId} references undeclared artifact ${tool.artifactPath}"
            }
        }

        val dependencyIds = dependencies.map { it.packId }
        require(dependencyIds.distinct().size == dependencyIds.size) {
            "RuntimePack dependency packIds must be unique"
        }
        require(packId !in dependencyIds) { "RuntimePack cannot depend on itself" }

        abis.forEach { abi ->
            require(abi.isNotBlank()) { "RuntimePack ABI cannot be blank" }
        }
    }

    fun canonicalBytes(): ByteArray = buildString {
        append("{")
        append("\"schemaVersion\":").append(schemaVersion).append(",")
        append("\"packId\":").append(jsonString(packId)).append(",")
        append("\"version\":").append(jsonString(version)).append(",")
        append("\"provenance\":").append(jsonString(provenance.name)).append(",")
        append("\"minApi\":").append(minApi).append(",")

        append("\"abis\":[")
        abis.toSortedSet().forEachIndexed { index, abi ->
            if (index > 0) append(",")
            append(jsonString(abi))
        }
        append("],")

        append("\"source\":")
        if (source == null) {
            append("null")
        } else {
            append("{")
            append("\"repository\":").append(jsonString(source.repository)).append(",")
            append("\"revision\":").append(jsonString(source.revision)).append(",")
            append("\"assetName\":").append(jsonString(source.assetName)).append(",")
            append("\"assetSha256\":").append(jsonString(source.assetSha256))
            append("}")
        }
        append(",")

        append("\"artifacts\":[")
        artifacts.sortedBy { it.path }.forEachIndexed { index, artifact ->
            if (index > 0) append(",")
            append("{")
            append("\"path\":").append(jsonString(artifact.path)).append(",")
            append("\"sha256\":").append(jsonString(artifact.sha256)).append(",")
            append("\"sizeBytes\":").append(artifact.sizeBytes).append(",")
            append("\"executable\":").append(artifact.executable)
            append("}")
        }
        append("],")

        append("\"dependencies\":[")
        dependencies.sortedBy { it.packId }.forEachIndexed { index, dependency ->
            if (index > 0) append(",")
            append("{")
            append("\"packId\":").append(jsonString(dependency.packId)).append(",")
            append("\"requiredGenerationId\":")
            append(
                dependency.requiredGenerationId
                    ?.let { jsonString(it.value) }
                    ?: "null"
            )
            append("}")
        }
        append("],")

        append("\"tools\":[")
        tools.sortedBy { it.toolId }.forEachIndexed { index, tool ->
            if (index > 0) append(",")
            append("{")
            append("\"toolId\":").append(jsonString(tool.toolId)).append(",")
            append("\"kind\":").append(jsonString(tool.kind.name)).append(",")
            append("\"artifactPath\":").append(jsonString(tool.artifactPath))
            append("}")
        }
        append("]")
        append("}")
    }.toByteArray(Charsets.UTF_8)

    fun generationId(): GenerationId =
        GenerationId(sha256(canonicalBytes()))

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

data class RuntimePackPlatform(
    val apiLevel: Int,
    val supportedAbis: Set<String>
) {
    init {
        require(apiLevel >= 1) { "apiLevel must be positive" }
        require(supportedAbis.none { it.isBlank() }) { "supportedAbis cannot contain blank values" }
    }

    companion object {
        fun currentAndroid(): RuntimePackPlatform =
            RuntimePackPlatform(
                apiLevel = Build.VERSION.SDK_INT,
                supportedAbis = Build.SUPPORTED_ABIS.toSet()
            )
    }
}

data class VerifiedRuntimePack(
    val manifest: RuntimePackManifest,
    val generationId: GenerationId,
    val root: File,
    val canonicalManifestSha256: String
) {
    init {
        require(root.isDirectory) { "VerifiedRuntimePack root must be a directory" }
        requireSha256("canonicalManifestSha256", canonicalManifestSha256)
        require(generationId.value == canonicalManifestSha256) {
            "generationId must equal canonicalManifestSha256"
        }
    }

    fun tool(toolId: String): ToolDescriptor =
        manifest.tools.firstOrNull { it.toolId == toolId }
            ?: error("RuntimePack ${manifest.packId} has no tool '$toolId'")

    fun artifactFile(path: String): File {
        requireRelativePath("artifact path", path)
        val candidate = File(root, path).canonicalFile
        require(isWithin(candidate, root.canonicalFile)) {
            "artifact escaped RuntimePack root: $path"
        }
        return candidate
    }
}

class RuntimePackVerificationException(message: String) : IllegalArgumentException(message)

class RuntimePackVerifier(
    private val platform: RuntimePackPlatform = RuntimePackPlatform.currentAndroid()
) {
    fun verify(root: File, manifest: RuntimePackManifest): VerifiedRuntimePack {
        val canonicalRoot = root.canonicalFile
        if (!canonicalRoot.isDirectory) {
            throw RuntimePackVerificationException(
                "RuntimePack root does not exist: ${canonicalRoot.path}"
            )
        }

        if (platform.apiLevel < manifest.minApi) {
            throw RuntimePackVerificationException(
                "RuntimePack ${manifest.packId} requires API ${manifest.minApi}; " +
                    "device API is ${platform.apiLevel}"
            )
        }

        if (manifest.abis.isNotEmpty() &&
            manifest.abis.intersect(platform.supportedAbis).isEmpty()
        ) {
            throw RuntimePackVerificationException(
                "RuntimePack ${manifest.packId} ABI mismatch: " +
                    "pack=${manifest.abis.sorted()} device=${platform.supportedAbis.sorted()}"
            )
        }

        val artifactsByPath = manifest.artifacts.associateBy { it.path }
        manifest.artifacts.forEach { artifact ->
            val file = resolveWithin(canonicalRoot, artifact.path)
            if (!file.isFile) {
                throw RuntimePackVerificationException(
                    "RuntimePack artifact missing: ${artifact.path}"
                )
            }

            if (file.length() != artifact.sizeBytes) {
                throw RuntimePackVerificationException(
                    "RuntimePack artifact size mismatch: ${artifact.path}"
                )
            }

            val actualHash = sha256(file)
            if (actualHash != artifact.sha256.lowercase(Locale.ROOT)) {
                throw RuntimePackVerificationException(
                    "RuntimePack artifact hash mismatch: ${artifact.path}"
                )
            }

            if (artifact.executable && !file.canExecute()) {
                throw RuntimePackVerificationException(
                    "RuntimePack artifact is not executable: ${artifact.path}"
                )
            }
        }

        manifest.tools.forEach { tool ->
            val artifact = artifactsByPath.getValue(tool.artifactPath)
            when (tool.kind) {
                RuntimePackToolKind.NATIVE_EXECUTABLE -> {
                    if (!artifact.executable) {
                        throw RuntimePackVerificationException(
                            "Native tool ${tool.toolId} must reference an executable artifact"
                        )
                    }
                }

                RuntimePackToolKind.JAVA_JAR -> {
                    if (!tool.artifactPath.lowercase(Locale.ROOT).endsWith(".jar")) {
                        throw RuntimePackVerificationException(
                            "Java tool ${tool.toolId} must reference a .jar artifact"
                        )
                    }
                }
            }
        }

        val manifestHash = sha256(manifest.canonicalBytes())
        return VerifiedRuntimePack(
            manifest = manifest,
            generationId = GenerationId(manifestHash),
            root = canonicalRoot,
            canonicalManifestSha256 = manifestHash
        )
    }

    private fun resolveWithin(root: File, relativePath: String): File {
        val candidate = File(root, relativePath).canonicalFile
        if (!isWithin(candidate, root)) {
            throw RuntimePackVerificationException(
                "RuntimePack artifact escaped pack root: $relativePath"
            )
        }
        return candidate
    }
}

enum class RuntimePackTrustDecision {
    APPROVED,
    REJECTED
}

data class RuntimePackTrustApproval(
    val decision: RuntimePackTrustDecision,
    val approvalId: String,
    val decidedAtEpochMs: Long
) {
    init {
        requireIdentifier("approvalId", approvalId)
        require(decidedAtEpochMs >= 0) { "decidedAtEpochMs cannot be negative" }
    }
}

data class AdmittedRuntimePack(
    val verified: VerifiedRuntimePack,
    val trustApproval: RuntimePackTrustApproval
) {
    init {
        require(trustApproval.decision == RuntimePackTrustDecision.APPROVED) {
            "RuntimePack cannot be admitted without explicit approval"
        }
    }

    val manifest: RuntimePackManifest
        get() = verified.manifest

    val generationId: GenerationId
        get() = verified.generationId
}

data class ResolvedTool(
    val packId: String,
    val generationId: GenerationId,
    val descriptor: ToolDescriptor,
    val artifact: File
)

class RuntimePackRegistry {
    private data class PackGenerationKey(
        val packId: String,
        val generationId: GenerationId
    )

    private val lock = Any()
    private val generations = mutableMapOf<PackGenerationKey, AdmittedRuntimePack>()
    private val active = mutableMapOf<String, GenerationId>()
    private val leaseCounts = mutableMapOf<PackGenerationKey, Int>()

    fun publish(pack: AdmittedRuntimePack, activate: Boolean = true) {
        synchronized(lock) {
            validateDependenciesLocked(pack)

            val key = PackGenerationKey(pack.manifest.packId, pack.generationId)
            val existing = generations[key]
            require(existing == null || existing == pack) {
                "RuntimePack generation identity collision for ${pack.manifest.packId}:${pack.generationId}"
            }
            generations[key] = pack
            if (activate) active[pack.manifest.packId] = pack.generationId
        }
    }

    fun activate(packId: String, generationId: GenerationId) {
        requirePackId(packId)
        synchronized(lock) {
            val key = PackGenerationKey(packId, generationId)
            require(generations.containsKey(key)) {
                "RuntimePack generation is not published: $packId:$generationId"
            }
            active[packId] = generationId
        }
    }

    fun activeGeneration(packId: String): GenerationId? {
        requirePackId(packId)
        return synchronized(lock) { active[packId] }
    }

    fun acquire(
        packId: String,
        generationId: GenerationId? = null
    ): GenerationLease {
        requirePackId(packId)
        return synchronized(lock) {
            val resolvedGeneration = generationId
                ?: active[packId]
                ?: error("RuntimePack has no active generation: $packId")
            val key = PackGenerationKey(packId, resolvedGeneration)
            val pack = generations[key]
                ?: error("RuntimePack generation is not published: $packId:$resolvedGeneration")
            leaseCounts[key] = (leaseCounts[key] ?: 0) + 1
            GenerationLease(
                pack = pack,
                release = { release(key) }
            )
        }
    }

    fun leaseCount(packId: String, generationId: GenerationId): Int {
        requirePackId(packId)
        return synchronized(lock) {
            leaseCounts[PackGenerationKey(packId, generationId)] ?: 0
        }
    }

    fun retire(packId: String, generationId: GenerationId): Boolean {
        requirePackId(packId)
        return synchronized(lock) {
            val key = PackGenerationKey(packId, generationId)
            if (active[packId] == generationId) return@synchronized false
            if ((leaseCounts[key] ?: 0) > 0) return@synchronized false
            leaseCounts.remove(key)
            generations.remove(key) != null
        }
    }

    fun publishedGenerations(packId: String): List<GenerationId> {
        requirePackId(packId)
        return synchronized(lock) {
            generations.keys
                .filter { it.packId == packId }
                .map { it.generationId }
                .sortedBy { it.value }
        }
    }

    private fun validateDependenciesLocked(pack: AdmittedRuntimePack) {
        pack.manifest.dependencies.forEach { dependency ->
            val matches = generations.keys.filter { it.packId == dependency.packId }
            if (dependency.requiredGenerationId == null) {
                require(matches.isNotEmpty()) {
                    "RuntimePack dependency is not published: ${dependency.packId}"
                }
            } else {
                val key = PackGenerationKey(
                    dependency.packId,
                    dependency.requiredGenerationId
                )
                require(generations.containsKey(key)) {
                    "RuntimePack dependency generation is not published: " +
                        "${dependency.packId}:${dependency.requiredGenerationId}"
                }
            }
        }
    }

    private fun release(key: PackGenerationKey) {
        synchronized(lock) {
            val current = leaseCounts[key] ?: return
            if (current <= 1) {
                leaseCounts.remove(key)
            } else {
                leaseCounts[key] = current - 1
            }
        }
    }
}

class GenerationLease internal constructor(
    val pack: AdmittedRuntimePack,
    private val release: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun resolveTool(toolId: String): ResolvedTool {
        check(!closed.get()) { "GenerationLease is closed" }
        val descriptor = pack.verified.tool(toolId)
        return ResolvedTool(
            packId = pack.manifest.packId,
            generationId = pack.generationId,
            descriptor = descriptor,
            artifact = pack.verified.artifactFile(descriptor.artifactPath)
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            release()
        }
    }
}

private fun requirePackId(value: String) {
    require(
        value.matches(
            Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        )
    ) {
        "invalid RuntimePack packId: $value"
    }
}

private fun requireIdentifier(name: String, value: String) {
    require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
        "invalid $name: $value"
    }
}

private fun requireRelativePath(name: String, value: String) {
    require(value.isNotBlank()) { "$name cannot be blank" }
    require(!value.startsWith('/')) { "$name must be relative" }
    require('\\' !in value) { "$name must use '/' separators" }
    val parts = value.split('/')
    require(parts.none { it.isBlank() || it == "." || it == ".." }) {
        "$name contains an unsafe path segment"
    }
}

private fun requireSha256(name: String, value: String) {
    require(value.matches(Regex("[0-9A-Fa-f]{64}"))) {
        "$name must be a SHA-256 hex digest"
    }
}

private fun isWithin(child: File, parent: File): Boolean {
    val childPath = child.path
    val parentPath = parent.path
    return childPath == parentPath || childPath.startsWith("$parentPath${File.separator}")
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
        digest.digest().toHex()
    }

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
    append('"')
}
