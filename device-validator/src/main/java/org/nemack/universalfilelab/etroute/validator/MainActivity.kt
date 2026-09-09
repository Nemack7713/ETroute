package org.nemack.universalfilelab.etroute.validator

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.nemack.universalfilelab.etroute.EtRouteSessionRunner
import org.nemack.universalfilelab.etroute.EtRouteWorkspaceManager
import org.nemack.universalfilelab.etroute.JniNativeSupervisor
import org.nemack.universalfilelab.etroute.NativeResourceLimits
import org.nemack.universalfilelab.etroute.NativeSpawnStage
import org.nemack.universalfilelab.etroute.PreparedLaunch
import org.nemack.universalfilelab.etroute.PreparedLaunchValidator
import org.nemack.universalfilelab.etroute.RuntimeSessionFinalizer
import org.nemack.universalfilelab.etroute.SessionFinalizationRequest
import java.io.File
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var runButton: Button
    private lateinit var copyButton: Button
    private var latestReport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.BLACK)
        }

        val title = TextView(this).apply {
            text = "ETroute Device Validator"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val subtitle = TextView(this).apply {
            text = "Final physical-device gate for JNI ABI v1 and NativeSupervisor"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(16))
        }

        runButton = Button(this).apply {
            text = "RUN ETROUTE TEST"
            setOnClickListener { runValidation() }
        }

        copyButton = Button(this).apply {
            text = "COPY REPORT"
            isEnabled = false
            setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("ETroute validation", latestReport))
            }
        }

        status = TextView(this).apply {
            text = "Ready. Tap RUN ETROUTE TEST."
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(16), 0, dp(16))
        }

        content.addView(title)
        content.addView(subtitle)
        content.addView(runButton)
        content.addView(copyButton)
        content.addView(status)

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
    }

    private fun runValidation() {
        runButton.isEnabled = false
        copyButton.isEnabled = false
        status.text = "Running ETroute physical-device validation…"

        Thread {
            val report = try {
                runSuite()
            } catch (error: Throwable) {
                buildString {
                    appendLine("ETROUTE_DEVICE_VALIDATION=FAILED")
                    appendLine("fatal=${error::class.java.name}")
                    appendLine("message=${error.message}")
                    appendLine()
                    appendLine(error.stackTraceToString())
                }
            }

            latestReport = report
            persistReport(report)

            runOnUiThread {
                status.text = report
                runButton.isEnabled = true
                copyButton.isEnabled = true
            }
        }.start()
    }

    private fun runSuite(): String {
        val lines = mutableListOf<String>()
        var failures = 0

        fun step(name: String, block: () -> String) {
            try {
                val detail = block()
                lines += "[PASS] $name${if (detail.isBlank()) "" else " — $detail"}"
            } catch (error: Throwable) {
                failures += 1
                lines += "[FAIL] $name — ${error::class.java.simpleName}: ${error.message}"
            }
        }

        val memory = memoryPolicy()
        val supervisor = JniNativeSupervisor()
        val workspaceManager = EtRouteWorkspaceManager(applicationContext)
        val finalizer = RuntimeSessionFinalizer()
        val nativePolicyVersion = supervisor.policyVersionForValidation()
        val inheritedAs = supervisor.addressSpaceLimitForValidation()

        lines += "ETroute Physical Device Validation"
        lines += "device=${Build.MANUFACTURER} ${Build.MODEL}"
        lines += "android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}"
        lines += "abis=${Build.SUPPORTED_ABIS.joinToString()}"
        lines += "ramTotalMiB=${memory.totalBytes / MIB}"
        lines += "ramAvailableMiB=${memory.availableBytes / MIB}"
        lines += "advisoryAddressSpaceMiB=${if (memory.advisoryBytes == 0L) "uncapped" else memory.advisoryBytes / MIB}"
        lines += "memoryPolicy=75% physical RAM; 1 GiB floor; 8 GiB ceiling; telemetry-only"
        lines += "nativePolicyVersion=$nativePolicyVersion"
        lines += "parentRlimitAsErrno=${inheritedAs.errno}"
        lines += "parentRlimitAsSoft=${formatRlimit(inheritedAs.soft)}"
        lines += "parentRlimitAsHard=${formatRlimit(inheritedAs.hard)}"
        lines += ""

        step("JNI ABI v1 handshake") {
            val abi = supervisor.abiVersionForValidation()
            check(abi == 1L) { "expected ABI 1, got $abi" }
            check(nativePolicyVersion == 2L) {
                "expected native policy 2 (RLIMIT_AS opt-in), got $nativePolicyVersion; stale native library/APK suspected"
            }
            "abi=$abi policy=$nativePolicyVersion"
        }

        var smokeSessionRoot: File? = null
        step("NativeSupervisor success round-trip") {
            val run = EtRouteSessionRunner(applicationContext, supervisor).runSystemSmoke(timeoutMs = 15_000)
            smokeSessionRoot = run.paths.root
            val stderr = readDiagnostic(File(run.paths.diagnostics, "stderr.log"))
            check(run.result.succeeded) {
                "result=${run.result} stderr=${stderr.ifBlank { "<empty>" }}"
            }
            val stdout = File(run.paths.diagnostics, "stdout.log").readText()
            val artifact = File(run.paths.output, "etroute-smoke.txt")
            check("ETROUTE_ANDROID_SMOKE_OK" in stdout) { "stdout marker missing; stderr=$stderr" }
            check(artifact.isFile && "ETROUTE_ANDROID_SMOKE_OK" in artifact.readText()) {
                "output artifact missing or invalid; stderr=$stderr"
            }

            val finalized = finalizer.finalize(
                SessionFinalizationRequest(
                    paths = run.paths,
                    preserveOutput = true,
                    retainDiagnostics = true,
                    maxDiagnosticBytesPerFile = 4L * MIB
                )
            )
            check(finalized.cleaned) { "session finalization did not clean ephemeral paths" }
            check(finalized.outputPreserved) { "workspace/output was not preserved" }
            "stage=${run.result.stage} exit=${run.result.exitCode} output=${artifact.path}"
        }

        step("EXECVE failure preserves stage/errno") {
            val paths = workspaceManager.create("execfail-${UUID.randomUUID()}")
            try {
                val result = supervisor.run(
                    launch(
                        paths = paths,
                        executable = "/system/bin/etroute-does-not-exist",
                        arguments = emptyList(),
                        timeoutMs = 3_000,
                        origin = "device-validator:exec-failure"
                    )
                )
                check(result.stage == NativeSpawnStage.EXECVE) { "stage=${result.stage}" }
                check(result.spawnErrno != 0) { "errno was not preserved" }
                "stage=${result.stage} errno=${result.spawnErrno} exit=${result.exitCode}"
            } finally {
                finalizer.finalize(SessionFinalizationRequest(paths, preserveOutput = false))
            }
        }

        step("Timeout kills process group") {
            val paths = workspaceManager.create("timeout-${UUID.randomUUID()}")
            try {
                val result = supervisor.run(
                    launch(
                        paths = paths,
                        executable = "/system/bin/sh",
                        arguments = listOf("-c", "trap '' TERM; sleep 5"),
                        timeoutMs = 350,
                        terminateGraceMs = 150,
                        origin = "device-validator:timeout"
                    )
                )
                val stderr = readDiagnostic(File(paths.diagnostics, "stderr.log"))
                check(result.timedOut) { "timedOut=false result=$result stderr=${stderr.ifBlank { "<empty>" }}" }
                check(result.stage == NativeSpawnStage.TIMEOUT_KILL) { "stage=${result.stage} stderr=$stderr" }
                "stage=${result.stage} signal=${result.signal} durationMs=${result.durationMs}"
            } finally {
                finalizer.finalize(
                    SessionFinalizationRequest(
                        paths = paths,
                        preserveOutput = false,
                        retainDiagnostics = true,
                        maxDiagnosticBytesPerFile = 4L * MIB
                    )
                )
            }
        }

        step("PreparedLaunch rejects workspace escape before JNI") {
            val paths = workspaceManager.create("escape-${UUID.randomUUID()}")
            try {
                val candidate = launch(
                    paths = paths,
                    executable = "/system/bin/sh",
                    arguments = listOf("-c", "true"),
                    timeoutMs = 2_000,
                    origin = "device-validator:workspace-escape",
                    workingDirectory = "/system"
                )
                val rejected = runCatching {
                    PreparedLaunchValidator(applicationContext, paths).validate(candidate)
                }.isFailure
                check(rejected) { "workspace escape unexpectedly passed validation" }
                "rejected=true"
            } finally {
                finalizer.finalize(SessionFinalizationRequest(paths, preserveOutput = false))
            }
        }

        step("Advisory maximum memory policy") {
            if (memory.totalBytes >= 2L * GIB) {
                check(memory.advisoryBytes in 1L * GIB..8L * GIB) {
                    "advisory=${memory.advisoryBytes}"
                }
            } else {
                check(memory.advisoryBytes == 0L) { "small-device policy should be uncapped" }
            }
            if (memory.advisoryBytes == 0L) "uncapped telemetry" else "${memory.advisoryBytes / MIB} MiB telemetry only"
        }

        lines += ""
        lines += "smokeSessionRoot=${smokeSessionRoot?.path ?: "n/a"}"
        lines += "reportPath=${reportFile().path}"
        lines += if (failures == 0) {
            "ETROUTE_DEVICE_VALIDATION=PASSED"
        } else {
            "ETROUTE_DEVICE_VALIDATION=FAILED failures=$failures"
        }

        return lines.joinToString("\n")
    }

    private fun launch(
        paths: org.nemack.universalfilelab.etroute.RuntimeSessionPaths,
        executable: String,
        arguments: List<String>,
        timeoutMs: Long,
        origin: String,
        terminateGraceMs: Long = 500,
        workingDirectory: String = paths.tmp.path
    ): PreparedLaunch = PreparedLaunch(
        executable = executable,
        arguments = arguments,
        environment = mapOf(
            "PATH" to "/system/bin",
            "HOME" to paths.tmp.path,
            "TMPDIR" to paths.tmp.path,
            "ETROUTE_SESSION_ID" to paths.sessionId
        ),
        workingDirectory = workingDirectory,
        stdoutPath = File(paths.diagnostics, "stdout.log").path,
        stderrPath = File(paths.diagnostics, "stderr.log").path,
        timeoutMs = timeoutMs,
        terminateGraceMs = terminateGraceMs,
        limits = NativeResourceLimits(
            cpuSeconds = 0,
            maxOpenFiles = 4096,
            maxFileBytes = 1L * GIB,
            maxAddressSpaceBytes = 0
        ),
        sessionId = paths.sessionId,
        requestId = UUID.randomUUID().toString(),
        originTag = origin
    )

    private fun readDiagnostic(file: File): String {
        if (!file.isFile) return ""
        return runCatching {
            val bytes = file.inputStream().use { input ->
                input.readNBytes(MAX_INLINE_DIAGNOSTIC_BYTES)
            }
            bytes.toString(Charsets.UTF_8).trim().replace('\n', ' ')
        }.getOrElse { "<unable-to-read:${it::class.java.simpleName}>" }
    }

    private fun formatRlimit(value: Long): String = when (value) {
        -1L -> "RLIM_INFINITY"
        else -> "$value bytes"
    }

    private data class MemoryPolicy(
        val totalBytes: Long,
        val availableBytes: Long,
        val advisoryBytes: Long
    )

    private fun memoryPolicy(): MemoryPolicy {
        val manager = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val total = info.totalMem
        val advisory = if (total < 2L * GIB) {
            0L
        } else {
            (total - total / 4L).coerceIn(1L * GIB, 8L * GIB)
        }
        return MemoryPolicy(total, info.availMem, advisory)
    }

    private fun persistReport(report: String) {
        runCatching {
            val file = reportFile()
            file.parentFile?.mkdirs()
            file.writeText(report)
        }
    }

    private fun reportFile(): File = File(filesDir, "etroute/device-validation/latest-report.txt")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIB = 1024L * 1024L
        private const val GIB = 1024L * MIB
        private const val MAX_INLINE_DIAGNOSTIC_BYTES = 8 * 1024
    }
}
