package org.nemack.universalfilelab.etroute

import android.content.Context
import com.google.gson.JsonParser

// Read-only mapping from this APK's source snapshot. Never fetches remote or user-supplied source paths.
object SourceErrorContext {
    private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
    fun describe(context: Context, error: Throwable): String = try {
        val raw = context.assets.open("diagnostics/source-context-v1.json").use { input ->
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                check(output.size() + count <= MAX_INDEX_BYTES)
                output.write(chunk, 0, count)
            }
            output.toString("UTF-8")
        }
        val root = JsonParser.parseString(raw).asJsonObject
        check(root.get("schemaVersion").asInt == 1)
        val files = root.getAsJsonArray("files")
        check(files.size() <= 128)
        val sources = files.map { element ->
            val source = element.asJsonObject
            DiagnosticSource(source.get("path").asString, source.get("package").asString,
                source.get("file").asString, source.get("sha256").asString,
                source.getAsJsonArray("lines").map { it.asString })
        }
        val causes = mutableListOf<Throwable>()
        var current: Throwable? = error
        repeat(3) { current?.let { value -> causes += value; current = value.cause?.takeUnless { it === value } } }
        var snippet: String? = null
        for (cause in causes.asReversed()) {
            for ((index, frame) in cause.stackTrace.take(32).withIndex()) {
                snippet = SourceSnippet.render(sources, frame, index == 0)
                if (snippet != null) break
            }
            if (snippet != null) {
                snippet = "SOURCE_EXCEPTION_TYPE=${cause.javaClass.name.take(128)}\n$snippet"
                break
            }
        }
        snippet?.also { check(it.toByteArray(Charsets.UTF_8).size <= 6144) } ?: "SOURCE_CONTEXT_UNAVAILABLE: no unambiguous source location in this APK (native/framework/synthetic/unknown line)."
    } catch (_: Exception) {
        "SOURCE_CONTEXT_UNAVAILABLE: bundled source index could not be read or validated."
    }
}
