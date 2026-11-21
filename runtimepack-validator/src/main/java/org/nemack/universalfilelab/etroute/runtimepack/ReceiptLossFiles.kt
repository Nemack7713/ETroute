package org.nemack.universalfilelab.etroute.runtimepack

import java.io.File

internal object ReceiptLossFiles {
    fun diagnostic(filesDirectory: File, sessionId: String, name: String): File {
        check(sessionId.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))) { "Invalid retained session identifier" }
        check(name in setOf("stdout.log", "stderr.log", "probe-envelope.json", "probe-payload.bin", "native-result.txt")) {
            "Unknown retained diagnostic name"
        }
        // Android may alias the application root (/data/user/0 and /data/data).
        // Canonicalize that trusted root first, then reject links introduced below it.
        val root = filesDirectory.canonicalFile
        val file = File(root, "etroute/sessions/$sessionId/diagnostics/$name").absoluteFile
        check(file == file.canonicalFile) { "Retained diagnostic has a symlink below the app root: $name" }
        check(file.isFile) { "Retained diagnostic is missing: $name" }
        return file
    }
}
