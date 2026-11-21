package org.nemack.universalfilelab.etroute.runtimepack

import java.io.File
import org.nemack.universalfilelab.etroute.NativeRunResult

internal object ManualProbeDiagnostics {
    const val MAX_BYTES = 8192
    data class Output(val text: String, val truncated: Boolean)
    fun read(file: File): Output {
        if (!file.isFile) return Output("<missing diagnostic file>", true)
        val bytes = ByteArray(MAX_BYTES + 1)
        val count = file.inputStream().use { input ->
            var total = 0
            while (total < bytes.size) {
                val read = input.read(bytes, total, bytes.size - total)
                if (read < 0) break
                total += read
            }
            total
        }
        return Output(String(bytes, 0, minOf(count, MAX_BYTES), Charsets.UTF_8), count > MAX_BYTES)
    }
    fun passed(result: NativeRunResult, stdout: Output, stderr: Output, stopped: Boolean): Boolean =
        result.succeeded && !stopped && !stdout.truncated && !stderr.truncated &&
            stdout.text.lineSequence().any { it.startsWith("ETROUTE_PYTHON_PROBE_OK ") }
}
