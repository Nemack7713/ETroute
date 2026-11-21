package org.nemack.universalfilelab.etroute.runtimepack

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.nemack.universalfilelab.etroute.NativeRunResult
import org.nemack.universalfilelab.etroute.NativeSpawnStage

class ManualProbeDiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()
    private val ok = NativeRunResult(0, null, false, 1, 0, NativeSpawnStage.OK)
    private val marker = ManualProbeDiagnostics.Output("ETROUTE_PYTHON_PROBE_OK {}\n", false)
    private val empty = ManualProbeDiagnostics.Output("", false)
    @Test fun byteCeilingAndTruncationAreEnforced() {
        val file = temp.newFile(); file.writeBytes(ByteArray(8192) { 65 })
        assertFalse(ManualProbeDiagnostics.read(file).truncated)
        file.appendBytes(byteArrayOf(66)); val output = ManualProbeDiagnostics.read(file)
        assertTrue(output.truncated); assertEquals(8192, output.text.length)
    }
    @Test fun missingFileCannotPass() {
        val output = ManualProbeDiagnostics.read(File(temp.root, "missing"))
        assertTrue(output.truncated); assertFalse(ManualProbeDiagnostics.passed(ok, marker, output, false))
    }
    @Test fun successRequiresExitMarkerAndNoStopOrTruncation() {
        assertTrue(ManualProbeDiagnostics.passed(ok, marker, empty, false))
        assertFalse(ManualProbeDiagnostics.passed(ok, empty, empty, false))
        assertFalse(ManualProbeDiagnostics.passed(ok, marker, empty, true))
        assertFalse(ManualProbeDiagnostics.passed(ok.copy(exitCode = 1), marker, empty, false))
        assertFalse(ManualProbeDiagnostics.passed(ok, marker.copy(truncated = true), empty, false))
    }
    @Test fun ceilingUsesUtf8BytesRatherThanCharacterCount() {
        val file = temp.newFile(); file.writeText("é".repeat(4097))
        val output = ManualProbeDiagnostics.read(file)
        assertTrue(output.truncated); assertEquals(4096, output.text.length)
    }
    @Test fun waitAndPipeErrorsNeverClearUncertaintyEvenWithExitInformation() {
        assertTrue(ManualProbeDiagnostics.terminationConfirmed(ok))
        assertFalse(ManualProbeDiagnostics.terminationConfirmed(null))
        for (stage in listOf(NativeSpawnStage.WAITPID, NativeSpawnStage.ERROR_PIPE, NativeSpawnStage.CLEANUP_UNCONFIRMED)) {
            assertFalse(ManualProbeDiagnostics.terminationConfirmed(ok.copy(stage = stage)))
        }
        assertFalse(ManualProbeDiagnostics.terminationConfirmed(ok.copy(exitCode = null)))
    }

}
