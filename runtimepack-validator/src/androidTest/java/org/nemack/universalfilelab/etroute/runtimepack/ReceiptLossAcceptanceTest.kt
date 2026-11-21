package org.nemack.universalfilelab.etroute.runtimepack

import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptLossAcceptanceTest {
    @Test fun lostReceiptBlocksRetryAndSurvivesActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(UncertaintyActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<Button>(android.R.id.button1).performClick() }
            val file = File(context.filesDir, "latest-receipt-loss.txt")
            val deadline = System.nanoTime() + 60_000_000_000L
            while (!file.exists() && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(file.exists())
            val report = file.readText()
            assertTrue(report, report.startsWith("ST06_SIMULATED_RECEIPT_LOSS=PASS"))
            assertTrue(report, report.contains("retryBlockedBeforeApproval=true"))
            assertTrue(report, report.contains("actualNativeUncertaintyTested=false"))
            scenario.recreate()
            scenario.onActivity { assertFalse(it.findViewById<Button>(android.R.id.button1).isEnabled) }
            assertTrue(ReceiptLossRetention.verify(context).startsWith("ST06_REOPEN_RETENTION=PASS"))
        }
    }
}
