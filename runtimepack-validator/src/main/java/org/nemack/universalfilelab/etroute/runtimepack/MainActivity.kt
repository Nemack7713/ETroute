package org.nemack.universalfilelab.etroute.runtimepack

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.nemack.universalfilelab.etroute.PinnedRuntimePack
import org.nemack.universalfilelab.etroute.RuntimePackPlatform
import org.nemack.universalfilelab.etroute.RuntimePackPreflight
import java.io.File

// Feasibility APK: manual install/preflight only. Starting Python is instrumentation-only.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val status = TextView(this).apply { text = "Python 3.14.4 arm64 candidate. No automatic execution." }
        val button = Button(this).apply {
            text = "INSTALL PAYLOAD AND CHECK PREFLIGHT"
            setOnClickListener {
                isEnabled = false
                Thread {
                    val result = runCatching {
                        val candidate = Candidate.installAndInspect(applicationContext)
                        "${candidate.report.status}\nreason=${candidate.report.reason}\nlaunchAuthorized=${candidate.report.launchAuthorized}\nfiles=${candidate.report.measuredSha256.size}\nNo Python was launched."
                    }.getOrElse { "CHECK_FAILED: ${it.message?.take(512)}" }
                    runOnUiThread { status.text = result; isEnabled = true }
                }.start()
            }
        }
        layout.addView(button)
        layout.addView(ScrollView(this).apply { addView(status) })
        setContentView(layout)
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
