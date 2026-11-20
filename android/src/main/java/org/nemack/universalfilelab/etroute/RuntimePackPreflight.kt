package org.nemack.universalfilelab.etroute

import java.io.File
import java.security.MessageDigest

// Construct from trusted application configuration, never from the candidate pack itself.
data class PinnedRuntimePack(
    val id: String,
    val version: String,
    val pythonVersion: String,
    val abi: String,
    val minApi: Int,
    val maxApi: Int,
    val executable: String,
    val nativeSha256: Map<String, String>,
    val payloadSha256: Map<String, String>
)

data class RuntimePackPlatform(
    val api: Int,
    val processAbi: String,
    val nativeLibraryDirectory: File,
    val payloadPacksDirectory: File
) {
    companion object {
        fun fromAndroid(context: android.content.Context): RuntimePackPlatform {
            val app = context.applicationContext
            val abis = if (android.os.Process.is64Bit()) android.os.Build.SUPPORTED_64_BIT_ABIS
                else android.os.Build.SUPPORTED_32_BIT_ABIS
            return RuntimePackPlatform(android.os.Build.VERSION.SDK_INT, abis.firstOrNull().orEmpty(),
                File(app.applicationInfo.nativeLibraryDir), File(app.filesDir, "etroute/runtime-packs"))
        }
    }
}

enum class RuntimePackCheckStatus { REJECTED, READY_FOR_DEVICE_PROBE }
enum class RuntimePackCheckReason {
    UNKNOWN_PACK, INVALID_PINNED_SPEC, UNSUPPORTED_ABI, UNSUPPORTED_API,
    INVALID_PATH, MISSING_FILE, INVENTORY_MISMATCH, HASH_MISMATCH,
    INVALID_ELF, LIMIT_EXCEEDED, IO_FAILURE, DEVICE_PROBE_REQUIRED
}

data class RuntimePackPreflightReport(
    val packId: String,
    val status: RuntimePackCheckStatus,
    val reason: RuntimePackCheckReason,
    val api: Int,
    val processAbi: String,
    val packVersion: String? = null,
    val pythonVersion: String? = null,
    val measuredSha256: Map<String, String> = emptyMap()
) {
    // Preflight is never an admission token, even when all file checks pass.
    val launchAuthorized: Boolean get() = false
}

// Read-only checks of a stable, app-owned installation. Does not extract, chmod, probe or launch.
class RuntimePackPreflight(
    pinnedPacks: Map<String, PinnedRuntimePack>,
    private val platform: RuntimePackPlatform,
    private val maxEntries: Int = 4096,
    private val maxBytes: Long = 512L * 1024L * 1024L
) {
    private val pins = pinnedPacks.mapValues { (_, spec) ->
        spec.copy(nativeSha256 = spec.nativeSha256.toMap(), payloadSha256 = spec.payloadSha256.toMap())
    }

    init { require(maxEntries in 1..100_000); require(maxBytes in 1..(4L * 1024L * 1024L * 1024L)) }

    private class Rejected(val reason: RuntimePackCheckReason) : Exception()
    private fun reject(reason: RuntimePackCheckReason): Nothing = throw Rejected(reason)

    fun verify(packId: String): RuntimePackPreflightReport {
        val spec = pins[packId]
        fun report(reason: RuntimePackCheckReason, measured: Map<String, String> = emptyMap()) =
            RuntimePackPreflightReport(
                packId = packId,
                status = if (reason == RuntimePackCheckReason.DEVICE_PROBE_REQUIRED)
                    RuntimePackCheckStatus.READY_FOR_DEVICE_PROBE else RuntimePackCheckStatus.REJECTED,
                reason = reason, api = platform.api, processAbi = platform.processAbi,
                packVersion = spec?.version, pythonVersion = spec?.pythonVersion,
                measuredSha256 = measured.toMap()
            )
        if (spec == null) return report(RuntimePackCheckReason.UNKNOWN_PACK)
        return try {
            validateSpec(packId, spec)
            if (platform.processAbi != spec.abi) reject(RuntimePackCheckReason.UNSUPPORTED_ABI)
            if (platform.api < 26 || platform.api !in spec.minApi..spec.maxApi) reject(RuntimePackCheckReason.UNSUPPORTED_API)
            val nativeRoot = platform.nativeLibraryDirectory.canonicalFile
            val packsRoot = platform.payloadPacksDirectory.canonicalFile
            if (!nativeRoot.isDirectory || !packsRoot.isDirectory) reject(RuntimePackCheckReason.MISSING_FILE)
            if (nativeRoot == packsRoot || nativeRoot.path.startsWith(packsRoot.path + File.separator) ||
                packsRoot.path.startsWith(nativeRoot.path + File.separator)) reject(RuntimePackCheckReason.INVALID_PATH)
            val payloadRoot = child(packsRoot, packId)
            if (!payloadRoot.isDirectory) reject(RuntimePackCheckReason.MISSING_FILE)
            val actual = inventory(payloadRoot)
            if (actual != spec.payloadSha256.keys) reject(RuntimePackCheckReason.INVENTORY_MISMATCH)
            val measured = linkedMapOf<String, String>()
            var bytes = 0L
            fun verifyFile(root: File, relative: String, expected: String, native: Boolean) {
                val file = child(root, relative)
                if (!file.isFile) reject(RuntimePackCheckReason.MISSING_FILE)
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        if (bytes > maxBytes) reject(RuntimePackCheckReason.LIMIT_EXCEEDED)
                        digest.update(buffer, 0, count)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (hash != expected) reject(RuntimePackCheckReason.HASH_MISMATCH)
                if (native) verifyElf(file, spec.abi)
                measured["${if (native) "native" else "payload"}/$relative"] = hash
            }
            spec.nativeSha256.toSortedMap().forEach { (path, hash) -> verifyFile(nativeRoot, path, hash, true) }
            spec.payloadSha256.toSortedMap().forEach { (path, hash) -> verifyFile(payloadRoot, path, hash, false) }
            if (!child(nativeRoot, spec.executable).canExecute()) reject(RuntimePackCheckReason.INVALID_PATH)
            report(RuntimePackCheckReason.DEVICE_PROBE_REQUIRED, measured)
        } catch (error: Rejected) {
            report(error.reason)
        } catch (_: java.io.IOException) {
            report(RuntimePackCheckReason.IO_FAILURE)
        } catch (_: SecurityException) {
            report(RuntimePackCheckReason.IO_FAILURE)
        }
    }

    private fun validateSpec(packId: String, spec: PinnedRuntimePack) {
        if (!packId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) || spec.id != packId ||
            spec.version.isBlank() || spec.version.length > 128 ||
            !spec.pythonVersion.matches(Regex("3\\.[0-9]{1,2}(\\.[0-9]{1,3})?")) ||
            spec.abi !in setOf("arm64-v8a", "x86_64") || spec.minApi < 24 || spec.maxApi < spec.minApi ||
            spec.nativeSha256.isEmpty() || spec.payloadSha256.isEmpty() ||
            spec.nativeSha256.size.toLong() + spec.payloadSha256.size > maxEntries ||
            spec.executable !in spec.nativeSha256) reject(RuntimePackCheckReason.INVALID_PINNED_SPEC)
        (spec.nativeSha256.entries + spec.payloadSha256.entries).forEach { (path, hash) ->
            if (!safeRelative(path) || !hash.matches(Regex("[a-f0-9]{64}")))
                reject(RuntimePackCheckReason.INVALID_PINNED_SPEC)
        }
    }

    private fun safeRelative(path: String): Boolean = path.length in 1..256 &&
        !path.startsWith('/') && '\\' !in path && '\u0000' !in path &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } && path.split('/').size <= 32

    private fun child(root: File, relative: String): File {
        if (!safeRelative(relative)) reject(RuntimePackCheckReason.INVALID_PATH)
        val file = File(root, relative).absoluteFile
        // A canonical/absolute difference rejects symlinks, including intermediate directories.
        if (file.canonicalFile != file || !file.path.startsWith(root.path + File.separator))
            reject(RuntimePackCheckReason.INVALID_PATH)
        return file
    }

    @android.annotation.TargetApi(26)
    private fun inventory(root: File): Set<String> {
        val found = mutableSetOf<String>()
        val directories = ArrayDeque<Pair<File, Int>>()
        directories.add(root to 0)
        var count = 0
        try {
            while (directories.isNotEmpty()) {
                val (directory, depth) = directories.removeFirst()
                // Stream entries rather than allocating File.listFiles() for an untrusted directory.
                java.nio.file.Files.newDirectoryStream(directory.toPath()).use { children ->
                    for (path in children) {
                        count++
                        if (count > maxEntries || depth >= 32) reject(RuntimePackCheckReason.LIMIT_EXCEEDED)
                        val relative = path.toFile().relativeTo(root).invariantSeparatorsPath
                        val checked = child(root, relative)
                        when {
                            checked.isDirectory -> directories.add(checked to depth + 1)
                            checked.isFile -> found.add(relative)
                            else -> reject(RuntimePackCheckReason.INVALID_PATH)
                        }
                    }
                }
            }
        } catch (error: java.nio.file.DirectoryIteratorException) {
            throw error.cause ?: java.io.IOException("Directory iteration failed", error)
        }
        return found
    }

    private fun verifyElf(file: File, abi: String) {
        val header = ByteArray(64)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) reject(RuntimePackCheckReason.INVALID_ELF)
                offset += count
            }
        }
        fun byte(index: Int) = header[index].toInt() and 255
        val machine = byte(18) or (byte(19) shl 8)
        val type = byte(16) or (byte(17) shl 8)
        if (byte(0) != 127 || byte(1) != 69 || byte(2) != 76 || byte(3) != 70 ||
            byte(4) != 2 || byte(5) != 1 || byte(6) != 1 || type !in setOf(2, 3) ||
            machine != if (abi == "arm64-v8a") 183 else 62) reject(RuntimePackCheckReason.INVALID_ELF)
    }
}
