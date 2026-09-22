package org.nemack.universalfilelab.etroute

import org.json.JSONArray
import org.json.JSONObject

object RuntimePackManifestJson {
    fun parse(text: String): RuntimePackManifest {
        val root = JSONObject(text)
        requireExactKeys(
            root,
            setOf(
                "schemaVersion",
                "packId",
                "version",
                "provenance",
                "minApi",
                "abis",
                "source",
                "artifacts",
                "dependencies",
                "tools"
            ),
            "RuntimePack manifest"
        )

        return RuntimePackManifest(
            schemaVersion = root.getInt("schemaVersion"),
            packId = root.getString("packId"),
            version = root.getString("version"),
            provenance = RuntimePackProvenance.valueOf(root.getString("provenance")),
            minApi = root.getInt("minApi"),
            abis = parseStringSet(root.getJSONArray("abis"), "abis"),
            source = if (root.isNull("source")) {
                null
            } else {
                parseSource(root.getJSONObject("source"))
            },
            artifacts = parseArtifacts(root.getJSONArray("artifacts")),
            dependencies = parseDependencies(root.getJSONArray("dependencies")),
            tools = parseTools(root.getJSONArray("tools"))
        )
    }

    fun renderCanonical(manifest: RuntimePackManifest): String =
        manifest.canonicalBytes().toString(Charsets.UTF_8)

    private fun parseSource(value: JSONObject): RuntimePackSource {
        requireExactKeys(
            value,
            setOf("repository", "revision", "assetName", "assetSha256"),
            "RuntimePack source"
        )
        return RuntimePackSource(
            repository = value.getString("repository"),
            revision = value.getString("revision"),
            assetName = value.getString("assetName"),
            assetSha256 = value.getString("assetSha256")
        )
    }

    private fun parseArtifacts(values: JSONArray): List<RuntimePackArtifact> =
        List(values.length()) { index ->
            val value = values.getJSONObject(index)
            requireExactKeys(
                value,
                setOf("path", "sha256", "sizeBytes", "executable"),
                "RuntimePack artifact[$index]"
            )
            RuntimePackArtifact(
                path = value.getString("path"),
                sha256 = value.getString("sha256"),
                sizeBytes = value.getLong("sizeBytes"),
                executable = value.getBoolean("executable")
            )
        }

    private fun parseDependencies(values: JSONArray): List<RuntimePackDependency> =
        List(values.length()) { index ->
            val value = values.getJSONObject(index)
            requireExactKeys(
                value,
                setOf("packId", "requiredGenerationId"),
                "RuntimePack dependency[$index]"
            )
            RuntimePackDependency(
                packId = value.getString("packId"),
                requiredGenerationId = if (value.isNull("requiredGenerationId")) {
                    null
                } else {
                    GenerationId(value.getString("requiredGenerationId"))
                }
            )
        }

    private fun parseTools(values: JSONArray): List<ToolDescriptor> =
        List(values.length()) { index ->
            val value = values.getJSONObject(index)
            requireExactKeys(
                value,
                setOf("toolId", "kind", "artifactPath"),
                "RuntimePack tool[$index]"
            )
            ToolDescriptor(
                toolId = value.getString("toolId"),
                kind = RuntimePackToolKind.valueOf(value.getString("kind")),
                artifactPath = value.getString("artifactPath")
            )
        }

    private fun parseStringSet(values: JSONArray, name: String): Set<String> {
        val result = linkedSetOf<String>()
        repeat(values.length()) { index ->
            val value = values.getString(index)
            require(result.add(value)) {
                "$name contains duplicate value: $value"
            }
        }
        return result
    }

    private fun requireExactKeys(
        value: JSONObject,
        allowed: Set<String>,
        name: String
    ) {
        val actual = buildSet {
            val iterator = value.keys()
            while (iterator.hasNext()) add(iterator.next())
        }

        val unknown = actual - allowed
        require(unknown.isEmpty()) {
            "$name contains unknown fields: ${unknown.sorted().joinToString()}"
        }

        val missing = allowed - actual
        require(missing.isEmpty()) {
            "$name is missing fields: ${missing.sorted().joinToString()}"
        }
    }
}
