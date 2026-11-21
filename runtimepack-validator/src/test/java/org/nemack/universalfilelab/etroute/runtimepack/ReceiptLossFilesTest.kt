package org.nemack.universalfilelab.etroute.runtimepack

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiptLossFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun diagnostic(root: File): File = File(root, "etroute/sessions/$id/diagnostics/stdout.log")
        .also { it.parentFile.mkdirs(); it.writeText("retained") }

    @Test fun trustedAppRootAliasResolvesWithoutRejectingRetainedFiles() {
        val real = temporary.newFolder("actual-files").canonicalFile
        val expected = diagnostic(real)
        val alias = File(temporary.root, "android-files-alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val oldPath = File(alias, "etroute/sessions/$id/diagnostics/stdout.log")
        assertNotEquals(oldPath.absoluteFile, oldPath.canonicalFile)
        assertEquals(expected, ReceiptLossFiles.diagnostic(alias, id, "stdout.log"))
        assertEquals("retained", expected.readText())
    }
    @Test fun symlinkBelowCanonicalAppRootStillRejects() {
        val root = temporary.newFolder("app-files").canonicalFile
        val outside = temporary.newFolder("outside").canonicalFile
        File(outside, "stdout.log").writeText("outside")
        val parent = File(root, "etroute/sessions/$id").also { it.mkdirs() }
        Files.createSymbolicLink(File(parent, "diagnostics").toPath(), outside.toPath())
        try { ReceiptLossFiles.diagnostic(root, id, "stdout.log"); fail("escape accepted") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("symlink below")) }
    }
    @Test fun missingDiagnosticHasActionableReason() {
        try { ReceiptLossFiles.diagnostic(temporary.root, id, "stderr.log"); fail("missing accepted") }
        catch (error: IllegalStateException) { assertEquals("Retained diagnostic is missing: stderr.log", error.message) }
    }
    @Test fun invalidSessionOrDiagnosticNameCannotEscape() {
        for ((session, name) in listOf("../session" to "stdout.log", id to "../stdout.log")) {
            try { ReceiptLossFiles.diagnostic(temporary.root, session, name); fail("invalid path accepted") }
            catch (_: IllegalStateException) { }
        }
    }
}
