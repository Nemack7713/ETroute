package org.nemack.universalfilelab.etroute.runtimepack

import android.content.Context
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvidenceHandoffTest {
    private fun runButton(id: Int, expected: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "latest-probe-evidence.txt")
        val previous = if (file.exists()) file.readText() else ""
        ActivityScenario.launch(EvidenceActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.findViewById<Button>(id)
                assertTrue(button.isEnabled); button.performClick()
            }
            val deadline = System.nanoTime() + 60_000_000_000L
            var report = previous
            while (System.nanoTime() < deadline) {
                report = if (file.exists()) file.readText() else ""
                if (report != previous) break
                Thread.sleep(50)
            }
            assertTrue(report, report.startsWith(expected))
            assertTrue(report, report.contains("replayBlocked=true"))
            assertTrue(report, report.contains("generalScriptExecutionEnabled=false"))
        }
    }
    @Test fun freshCompletionReachesRealCoordinatorAndValidator() = runButton(android.R.id.button1, "ST08_COMPLETE=PASS")
    @Test fun actualActivityDestroyRecordsUnsuccessfulRetainedEvidence() = runButton(android.R.id.button2, "ST08_LIFECYCLE=PASS")
}
