package org.nemack.universalfilelab.etroute.runtimepack

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.nemack.universalfilelab.etroute.FixedProbeEvidenceSession

internal object ReceiptLossRetention {
    private val names = listOf("stdout.log", "stderr.log", "probe-envelope.json", "probe-payload.bin", "native-result.txt")
    fun snapshot(context: Context, report: String) {
        val id = Regex("(?m)^session=([a-f0-9-]{36})$").find(report)?.groupValues?.get(1) ?: error("Session missing")
        val hashes = JSONObject()
        names.forEach { hashes.put(it, hash(file(context, id, it))) }
        val state = JSONObject().put("session", id).put("hashes", hashes)
        val temporary = File(context.filesDir, "receipt-loss-state.tmp")
        temporary.writeText(state.toString())
        check(temporary.renameTo(File(context.filesDir, "receipt-loss-state.json")))
    }
    fun verify(context: Context): String {
        val stateFile = File(context.filesDir, "receipt-loss-state.json")
        check(stateFile.isFile) { "Retention snapshot is missing; open SHOW LAST RUN REPORT for the initial failure" }
        check(stateFile.length() in 1..4096) { "Retention snapshot exceeds its size bound or is empty" }
        val state = JSONObject(stateFile.readText())
        val id = state.getString("session")
        val prefs = context.getSharedPreferences("probe-evidence-state", Context.MODE_PRIVATE)
        val unchanged = names.all { hash(file(context, id, it)) == state.getJSONObject("hashes").getString(it) }
        val directories = listOf("input", "tmp", "output").all {
            File(context.filesDir, "etroute/sessions/$id/workspace/$it").isDirectory
        }
        val pass = unchanged && directories && prefs.getBoolean("uncertain", false)
        return "ST06_REOPEN_RETENTION=${if (pass) "PASS" else "FAIL"}\nuncertainMarker=${prefs.getBoolean("uncertain", false)}\n" +
            "retainedFilesUnchanged=$unchanged retainedFiles=${names.size} sessionDirectoriesRetained=$directories cleanupPerformed=false\nsession=$id\n" +
            "simulation=receipt-loss\nactualNativeUncertaintyTested=false\ngeneralScriptExecutionEnabled=false"
    }
    fun verifyRetryBlocked(context: Context, candidate: Candidate.Installed): Boolean {
        val prefs = context.getSharedPreferences("probe-evidence-state", Context.MODE_PRIVATE)
        val before = prefs.getInt("attempts", 0)
        var approvalCalled = false
        val error = runCatching { FixedProbeEvidenceSession(context, candidate.spec, candidate.root).run(false,
            approved = { approvalCalled = true; true }) }.exceptionOrNull()
        return error is IllegalStateException && error.message == "Prior probe needs recovery" &&
            !approvalCalled && before == prefs.getInt("attempts", 0)
    }
    private fun file(context: Context, id: String, name: String): File {
        return ReceiptLossFiles.diagnostic(context.filesDir, id, name)
    }
    private fun hash(file: File): String {
        check(file.length() in 0..16384) { "Retained diagnostic exceeds its size bound: ${file.name}" }
        return MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
