package org.nemack.universalfilelab.etroute.runtimepack

import android.content.Context
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// Real-device tests, compiled by CI but not represented as executed until a device runs them.
@RunWith(AndroidJUnit4::class)
class StopTimeoutAcceptanceTest {
    @Test fun fixedPreAttachmentStopRepeatedStopTimeoutAndCompletion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runner = InterruptionAcceptance(context)
        val report = try { runner.runSuite {} } finally { runner.stop() }
        for (marker in listOf("ST01_BEFORE_ATTACH=PASS", "ST02_STOP=PASS", "ST03_REPEATED_STOP=PASS", "ST04_TIMEOUT=PASS", "ST07_COMPLETE=PASS")) {
            assertTrue(report, marker in report)
        }
        assertTrue(report, "generalScriptExecutionEnabled=false" in report)
    }

    @Test fun realActivityFinishAfterWorkerReadyCancelsAndRetainsReport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "latest-acceptance.txt")
        val previous = if (file.isFile) file.readText() else ""
        ActivityScenario.launch(AcceptanceActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.findViewById<Button>(android.R.id.button2)
                assertTrue("Lifecycle test disabled by in-flight/uncertain state", button.isEnabled)
                button.performClick()
            }
            val start = System.nanoTime()
            var report = ""
            while (System.nanoTime() - start < 20_000_000_000L) {
                report = if (file.isFile) file.readText() else ""
                if (report != previous && "ST05_DESTROY=PASS" in report) break
                Thread.sleep(50)
            }
            assertTrue(report, report != previous && "ST05_DESTROY=PASS" in report)
            assertTrue(report, "destroyed=true" in report)
        }
    }
}
