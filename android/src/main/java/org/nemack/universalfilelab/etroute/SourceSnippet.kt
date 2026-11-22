package org.nemack.universalfilelab.etroute

internal data class DiagnosticSource(val path: String, val packageName: String, val fileName: String,
    val sha256: String, val lines: List<String>)

internal object SourceSnippet {
    fun render(sources: List<DiagnosticSource>, frame: StackTraceElement, origin: Boolean): String? {
        if (frame.lineNumber < 1 || frame.fileName == null) return null
        val matches = sources.filter { it.fileName == frame.fileName && it.packageName == frame.className.substringBeforeLast('.') }
        if (matches.size != 1) return null
        val source = matches.single()
        val line = frame.lineNumber
        if (line > source.lines.size) return null
        fun code(index: Int, boundary: String): String = source.lines.getOrNull(index)?.let {
            if (it.length > 512) it.take(512) + " [line truncated]" else it
        } ?: boundary
        return buildString {
            appendLine("SOURCE_CONTEXT kind=${if (origin) "EXCEPTION_LOCATION" else "APPLICATION_CALL_SITE"}")
            appendLine("${source.path}:$line sourceSha256=${source.sha256}")
            if (!origin) appendLine("Original exception location is outside the bundled source; middle line is the application call site.")
            appendLine("  ${line - 1} | ${code(line - 2, "[start of file]")}")
            appendLine("> $line | ${code(line - 1, "[unavailable]")}")
            append("  ${line + 1} | ${code(line, "[end of file]")}")
        }
    }
}
