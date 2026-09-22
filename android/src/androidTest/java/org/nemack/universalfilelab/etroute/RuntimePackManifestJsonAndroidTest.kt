package org.nemack.universalfilelab.etroute

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimePackManifestJsonAndroidTest {

    @Test
    fun candidateManifestRoundTripsCanonicalIdentity() {
        val manifest = RuntimePackManifest(
            packId = "org.etroute.apktool",
            version = "3.0.3",
            provenance = RuntimePackProvenance.EXTERNAL_VERIFIED,
            minApi = 24,
            abis = emptySet(),
            source = RuntimePackSource(
                repository = "iBotPeaches/Apktool",
                revision = "v3.0.3",
                assetName = "apktool_3.0.3.jar",
                assetSha256 = "a".repeat(64)
            ),
            artifacts = listOf(
                RuntimePackArtifact(
                    path = "artifacts/apktool_3.0.3.jar",
                    sha256 = "a".repeat(64),
                    sizeBytes = 15478013,
                    executable = false
                )
            ),
            dependencies = listOf(
                RuntimePackDependency(
                    packId = "org.etroute.java",
                    requiredGenerationId = null
                )
            ),
            tools = listOf(
                ToolDescriptor(
                    toolId = "apktool",
                    kind = RuntimePackToolKind.JAVA_JAR,
                    artifactPath = "artifacts/apktool_3.0.3.jar"
                )
            )
        )

        val canonical = RuntimePackManifestJson.renderCanonical(manifest)
        val parsed = RuntimePackManifestJson.parse(canonical)

        assertEquals(manifest.generationId(), parsed.generationId())
        assertEquals(manifest.packId, parsed.packId)
        assertEquals(manifest.version, parsed.version)
        assertEquals("org.etroute.java", parsed.dependencies.single().packId)
        assertEquals("apktool", parsed.tools.single().toolId)
    }

    @Test
    fun unknownManifestFieldIsRejected() {
        val text = """
            {
              "schemaVersion":1,
              "packId":"org.etroute.test",
              "version":"1",
              "provenance":"EXTERNAL_VERIFIED",
              "minApi":24,
              "abis":[],
              "source":null,
              "artifacts":[
                {
                  "path":"artifacts/tool.jar",
                  "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "sizeBytes":1,
                  "executable":false
                }
              ],
              "dependencies":[],
              "tools":[
                {
                  "toolId":"tool",
                  "kind":"JAVA_JAR",
                  "artifactPath":"artifacts/tool.jar"
                }
              ],
              "unexpected":true
            }
        """.trimIndent()

        var rejected = false
        try {
            RuntimePackManifestJson.parse(text)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }
}
