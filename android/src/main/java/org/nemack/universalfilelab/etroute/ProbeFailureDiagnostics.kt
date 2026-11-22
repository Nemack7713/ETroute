package org.nemack.universalfilelab.etroute

// Diagnostic text only: never an evidence envelope, receipt or launch permit.
internal object ProbeFailureDiagnostics {
    const val MAX_BYTES = 4096
    fun describe(phase: String, error: Exception, observed: NativeRunResult?, stopped: Boolean,
        destroyed: Boolean, interrupted: Boolean): String {
        val text = buildString {
            appendLine("runnerFailurePhase=${phase.take(64)}")
            appendLine("ownerStopRequested=$stopped activityDestroyed=$destroyed threadInterrupted=$interrupted")
            appendLine("nativeReturnObserved=${observed != null}")
            if (observed != null) appendLine("observedNativeStage=${observed.stage} exit=${observed.exitCode} signal=${observed.signal} " +
                "errno=${observed.spawnErrno} timedOut=${observed.timedOut} durationMs=${observed.durationMs}")
            var cause: Throwable? = error
            repeat(3) { index ->
                val current = cause ?: return@repeat
                appendLine("cause[$index]=${current.javaClass.name.take(128)}: ${current.message?.take(256)}")
                current.stackTrace.take(2).forEach { appendLine("at ${it.toString().take(192)}") }
                cause = current.cause?.takeUnless { it === current }
            }
        }
        val result = StringBuilder()
        var bytes = 0
        val points = text.codePoints().iterator()
        while (points.hasNext()) {
            val cp = points.nextInt()
            val size = when { cp <= 0x7f -> 1; cp <= 0x7ff -> 2; cp <= 0xffff -> 3; else -> 4 }
            if (bytes + size > MAX_BYTES) break
            result.appendCodePoint(cp); bytes += size
        }
        return result.toString()
    }
}
