package org.nemack.universalfilelab.etroute.runtimepack

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.nemack.universalfilelab.etroute.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Separate feasibility APK. Only a deliberate button press starts the compiled-in probe.
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var inspectButton: Button
    private lateinit var probeButton: Button
    private lateinit var stopButton: Button
    private val busy = AtomicBoolean(false)
    private val owner = AtomicReference<NativeStopController<JniNativeSupervisor.Request>?>(null)
    @Volatile private var destroyed = false
    private val preferences by lazy { getSharedPreferences("manual-probe-state", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { text = "Fixed Python 3.14.4 startup/import probe. No automatic or general script execution." }
        inspectButton = Button(this).apply {
            text = "INSTALL PAYLOAD AND CHECK PREFLIGHT"
            setOnClickListener { perform(false) }
        }
        probeButton = Button(this).apply {
            text = "RUN FIXED STARTUP/IMPORT PROBE"
            setOnClickListener { perform(true) }
        }
        stopButton = Button(this).apply {
            text = "STOP"; isEnabled = false
            setOnClickListener { owner.get()?.requestStop(); text = "STOP REQUESTED" }
        }
        layout.addView(inspectButton); layout.addView(probeButton); layout.addView(stopButton)
        layout.addView(ScrollView(this).apply { addView(status) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        if (preferences.getBoolean("uncertain", false)) {
            probeButton.isEnabled = false
            status.text = "RECOVERY_REQUIRED: prior termination is uncertain. Diagnostics retained; no automatic restart."
        }
    }

    private fun perform(execute: Boolean) {
        if (destroyed || !busy.compareAndSet(false, true)) return
        if (execute && preferences.getBoolean("uncertain", false)) { busy.set(false); return }
        val control = NativeStopController<JniNativeSupervisor.Request> { it.cancel() }
        owner.set(control)
        inspectButton.isEnabled = false; probeButton.isEnabled = false
        stopButton.isEnabled = true; stopButton.text = "STOP"
        status.text = if (execute) "Rechecking preflight before fixed probe…" else "Checking preflight…"
        Thread {
            val report = try {
                val candidate = Candidate.installAndInspect(applicationContext)
                if (control.isStopped()) throw CancellationException()
                if (!execute) {
                    "${candidate.report.status}\nreason=${candidate.report.reason}\nlaunchAuthorized=false\nfiles=${candidate.report.measuredSha256.size}\nNo Python was launched."
                } else {
                    check(candidate.report.status == RuntimePackCheckStatus.READY_FOR_DEVICE_PROBE) {
                        "PREFLIGHT_REJECTED: ${candidate.report.reason}"
                    }
                    runFixed(candidate, control)
                }
            } catch (_: CancellationException) {
                "PROBE_CANCELLED_BEFORE_LAUNCH\nlaunchAuthorized=false"
            } catch (error: Throwable) {
                "PROBE_CHECK_FAILED\n${error::class.java.simpleName}: ${error.message?.take(512)}\nlaunchAuthorized=false"
            } finally {
                control.close()
                owner.compareAndSet(control, null)
                busy.set(false)
            }
            runOnUiThread {
                if (!destroyed) {
                    status.text = report
                    inspectButton.isEnabled = true
                    probeButton.isEnabled = !preferences.getBoolean("uncertain", false)
                    stopButton.isEnabled = false; stopButton.text = "STOP"
                }
            }
        }.start()
    }

    private fun runFixed(candidate: Candidate.Installed, control: NativeStopController<JniNativeSupervisor.Request>): String {
        check(!control.isStopped()) { "Stopped before launch" }
        val attempts = preferences.getInt("attempts", 0)
        check(attempts < 8) { "Manual diagnostic session ceiling reached (8); retained sessions need review." }
        check(preferences.edit().putInt("attempts", attempts + 1).commit())
        val id = "manual-probe-" + UUID.randomUUID().toString()
        val paths = EtRouteWorkspaceManager(applicationContext).create(id)
        val native = File(applicationInfo.nativeLibraryDir).canonicalFile
        val launch = PreparedLaunch(File(native, candidate.spec.executable).absolutePath,
            listOf("--probe", candidate.root.absolutePath),
            mapOf("LD_LIBRARY_PATH" to native.absolutePath, "TMPDIR" to paths.tmp.absolutePath),
            paths.root.absolutePath, File(paths.diagnostics, "stdout.log").absolutePath,
            File(paths.diagnostics, "stderr.log").absolutePath,
            timeoutMs = 10_000, limits = NativeResourceLimits(cpuSeconds = 5, maxOpenFiles = 64, maxFileBytes = 8192),
            sessionId = id, requestId = id, originTag = "manual-fixed-python-feasibility")
        var request: JniNativeSupervisor.Request? = null
        var result: NativeRunResult? = null
        // A persisted in-flight marker also rejects silent retries after process death.
        check(preferences.edit().putBoolean("uncertain", true).commit())
        val report = try {
            val completed = EtRouteSessionRunner(applicationContext).run(launch) {
                request = it
                check(control.attach(it)) { "Owner closed before native attachment" }
            }.result
            result = completed
            val stdout = ManualProbeDiagnostics.read(File(launch.stdoutPath))
            val stderr = ManualProbeDiagnostics.read(File(launch.stderrPath))
            val passed = ManualProbeDiagnostics.passed(completed, stdout, stderr, control.isStopped())
            buildString {
                appendLine(if (passed) "FIXED_PYTHON_PROBE=PASS" else "FIXED_PYTHON_PROBE=FAILED_OR_STOPPED")
                appendLine("stage=${completed.stage} exit=${completed.exitCode} signal=${completed.signal} errno=${completed.spawnErrno}")
                appendLine("timedOut=${completed.timedOut} durationMs=${completed.durationMs}")
                appendLine("stdoutTruncated=${stdout.truncated} stderrTruncated=${stderr.truncated}")
                appendLine("launchAuthorized=false (general scripts disabled)")
                appendLine("session=$id")
                appendLine("STDOUT:\n${stdout.text}")
                appendLine("STDERR:\n${stderr.text}")
            }
        } catch (error: Throwable) {
            "FIXED_PYTHON_PROBE=ERROR\n${error::class.java.simpleName}: ${error.message?.take(512)}\nsession=$id\nlaunchAuthorized=false"
        } finally {
            val confirmed = result != null && result?.stage != NativeSpawnStage.CLEANUP_UNCONFIRMED
            request?.let { control.complete(it, confirmed) }
            if (confirmed) preferences.edit().putBoolean("uncertain", false).commit()
            // No deletion: diagnostic files remain for review, including failures/uncertainty.
        }
        val bytes = report.toByteArray(Charsets.UTF_8)
        File(paths.diagnostics, "manual-probe-report.txt").writeBytes(bytes.copyOfRange(0, minOf(bytes.size, 20_000)))
        return report
    }

    override fun onDestroy() {
        destroyed = true
        owner.get()?.close()
        super.onDestroy()
    }
}

object Candidate {
    data class Installed(val spec: PinnedRuntimePack, val root: File,
        val report: org.nemack.universalfilelab.etroute.RuntimePackPreflightReport)
    fun installAndInspect(context: Context): Installed {
        val manifest = context.assets.open("runtimepack/inventory.json").bufferedReader().use { JSONObject(it.readText()) }
        fun hashes(name: String): Map<String, String> = manifest.getJSONObject(name).let { obj ->
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }
        val spec = PinnedRuntimePack(manifest.getString("id"), manifest.getString("version"),
            manifest.getString("pythonVersion"), manifest.getString("abi"), manifest.getInt("minApi"),
            manifest.getInt("maxApi"), manifest.getString("executable"), hashes("nativeSha256"), hashes("payloadSha256"))
        val platform = RuntimePackPlatform.fromAndroid(context)
        val root = File(platform.payloadPacksDirectory.canonicalFile, spec.id)
        check(root.absoluteFile == root.canonicalFile)
        if (!root.exists()) {
            check(root.mkdirs())
            spec.payloadSha256.keys.forEach { relative ->
                check(!relative.startsWith('/') && relative.split('/').none { it == ".." || it == "." || it.isEmpty() })
                val file = File(root, relative)
                check(file.absoluteFile == file.canonicalFile && file.path.startsWith(root.path + File.separator))
                check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                context.assets.open("runtimepack/payload/$relative").use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }
        return Installed(spec, root, RuntimePackPreflight(mapOf(spec.id to spec), platform).verify(spec.id))
    }
}
