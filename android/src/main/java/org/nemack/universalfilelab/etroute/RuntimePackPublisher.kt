package org.nemack.universalfilelab.etroute

import android.content.Context
import java.io.File
import java.util.UUID

data class RuntimePackPublication(
    val admitted: AdmittedRuntimePack,
    val reusedExistingGeneration: Boolean
)

class RuntimePackPublisher(
    context: Context,
    private val verifier: RuntimePackVerifier = RuntimePackVerifier()
) {
    private val runtimePacksRoot =
        File(context.applicationContext.filesDir, "etroute/runtime-packs").canonicalFile

    fun publish(
        candidateRoot: File,
        manifest: RuntimePackManifest,
        trustApproval: RuntimePackTrustApproval
    ): RuntimePackPublication {
        require(trustApproval.decision == RuntimePackTrustDecision.APPROVED) {
            "RuntimePack publication requires explicit trust approval"
        }

        val verifiedCandidate = verifier.verify(candidateRoot, manifest)
        val packRoot = File(runtimePacksRoot, manifest.packId).canonicalFile
        require(isWithin(packRoot, runtimePacksRoot)) {
            "RuntimePack packId escaped runtime-packs root"
        }

        val destination = File(
            packRoot,
            verifiedCandidate.generationId.value
        ).canonicalFile
        require(isWithin(destination, packRoot)) {
            "RuntimePack generation path escaped pack root"
        }

        if (destination.exists()) {
            val verifiedExisting = verifier.verify(destination, manifest)
            require(verifiedExisting.generationId == verifiedCandidate.generationId) {
                "existing RuntimePack generation identity mismatch"
            }
            return RuntimePackPublication(
                admitted = AdmittedRuntimePack(
                    verified = verifiedExisting,
                    trustApproval = trustApproval
                ),
                reusedExistingGeneration = true
            )
        }

        check(packRoot.mkdirs() || packRoot.isDirectory) {
            "Unable to create RuntimePack pack root: ${packRoot.path}"
        }

        val staging = File(
            packRoot,
            ".staging-${verifiedCandidate.generationId.value}-${UUID.randomUUID()}"
        ).canonicalFile
        require(isWithin(staging, packRoot)) {
            "RuntimePack staging path escaped pack root"
        }

        try {
            copyCandidate(candidateRoot.canonicalFile, staging, manifest)
            File(staging, CANONICAL_MANIFEST_NAME).writeBytes(manifest.canonicalBytes())

            val stagedVerified = verifier.verify(staging, manifest)
            require(stagedVerified.generationId == verifiedCandidate.generationId) {
                "RuntimePack changed while publishing"
            }

            if (!staging.renameTo(destination)) {
                if (destination.exists()) {
                    val verifiedExisting = verifier.verify(destination, manifest)
                    require(verifiedExisting.generationId == stagedVerified.generationId) {
                        "concurrent RuntimePack publication produced mismatched generation"
                    }
                    staging.deleteRecursively()
                    return RuntimePackPublication(
                        admitted = AdmittedRuntimePack(
                            verified = verifiedExisting,
                            trustApproval = trustApproval
                        ),
                        reusedExistingGeneration = true
                    )
                }
                error("Unable to atomically publish RuntimePack generation")
            }

            makeGenerationReadOnly(destination)

            val publishedVerified = verifier.verify(destination, manifest)
            require(publishedVerified.generationId == verifiedCandidate.generationId) {
                "published RuntimePack verification mismatch"
            }

            return RuntimePackPublication(
                admitted = AdmittedRuntimePack(
                    verified = publishedVerified,
                    trustApproval = trustApproval
                ),
                reusedExistingGeneration = false
            )
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun copyCandidate(
        sourceRoot: File,
        destinationRoot: File,
        manifest: RuntimePackManifest
    ) {
        check(destinationRoot.mkdirs()) {
            "Unable to create RuntimePack staging directory"
        }

        manifest.artifacts.forEach { artifact ->
            val source = File(sourceRoot, artifact.path).canonicalFile
            require(isWithin(source, sourceRoot)) {
                "candidate artifact escaped source root: ${artifact.path}"
            }
            require(source.isFile) {
                "candidate artifact missing during publication: ${artifact.path}"
            }

            val destination = File(destinationRoot, artifact.path).canonicalFile
            require(isWithin(destination, destinationRoot)) {
                "candidate artifact escaped destination root: ${artifact.path}"
            }
            destination.parentFile?.let { parent ->
                check(parent.mkdirs() || parent.isDirectory) {
                    "Unable to create RuntimePack artifact parent: ${parent.path}"
                }
            }
            source.copyTo(destination, overwrite = false)
            if (artifact.executable) {
                check(destination.setExecutable(true, true)) {
                    "Unable to mark RuntimePack artifact executable: ${artifact.path}"
                }
            }
        }
    }

    private fun makeGenerationReadOnly(root: File) {
        root.walkBottomUp().forEach { file ->
            if (file.isFile) {
                file.setWritable(false, false)
            }
        }
    }

    companion object {
        const val CANONICAL_MANIFEST_NAME = "manifest.canonical.json"
    }
}

private fun isWithin(child: File, parent: File): Boolean {
    val childPath = child.path
    val parentPath = parent.path
    return childPath == parentPath || childPath.startsWith("$parentPath${File.separator}")
}
