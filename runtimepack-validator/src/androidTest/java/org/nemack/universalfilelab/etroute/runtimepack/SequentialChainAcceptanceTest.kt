package org.nemack.universalfilelab.etroute.runtimepack

import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// Run this class alone on a fresh chain-check APK; terminal receipt loss intentionally leaves retry blocked.
@RunWith(AndroidJUnit4::class)
class SequentialChainAcceptanceTest {
    @Test fun fixedChainEndsEveryOwnerBeforeAdvancingAndRetainsTerminalEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(AcceptanceChainActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue(it.findViewById<Button>(android.R.id.button1).isEnabled); it.findViewById<Button>(android.R.id.button1).performClick() }
            val file = File(context.filesDir, "latest-chain.txt")
            val deadline = System.nanoTime() + 180_000_000_000L
            var report = ""
            while (System.nanoTime() < deadline) {
                report = if (file.isFile) file.readText() else ""
                if (report.startsWith("SEQUENTIAL_CHAIN=PASS_RETRY_BLOCKED") || report.startsWith("SEQUENTIAL_CHAIN=HALTED") ||
                    report.startsWith("SEQUENTIAL_CHAIN=ERROR")) break
                Thread.sleep(100)
            }
            assertTrue(report, report.startsWith("SEQUENTIAL_CHAIN=PASS_RETRY_BLOCKED"))
            assertTrue(report, report.contains("chainStopCause=NONE"))
            assertEquals(8, report.lineSequence().count { it.contains("=PASS;") && it.contains("barrierReached=true") })
            assertNull(ChainLifecycleBridge.active.get())
            scenario.recreate()
            scenario.onActivity { assertFalse(it.findViewById<Button>(android.R.id.button1).isEnabled) }
            assertTrue(ReceiptLossRetention.verify(context).startsWith("ST06_REOPEN_RETENTION=PASS"))
        }
    }
}
