package org.nemack.universalfilelab.etroute.runtimepack

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.nemack.universalfilelab.etroute.SourceErrorContext

@RunWith(AndroidJUnit4::class)
class SourceContextAssetTest {
    @Test fun packagedSourceRendersThreeRowsForAnExplicitStackFrameFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.assets.open("diagnostics/source-context-v1.json").bufferedReader().use { JSONObject(it.readText()) }
        val sources = root.getJSONArray("files")
        val source = (0 until sources.length()).map { sources.getJSONObject(it) }.single { it.getString("file") == "ReceiptLossFiles.kt" }
        val error = IllegalStateException("Source mapping fixture; not a device failure")
        error.stackTrace = arrayOf(StackTraceElement(source.getString("package") + ".ReceiptLossFiles", "diagnostic", "ReceiptLossFiles.kt", 3))
        val report = SourceErrorContext.describe(context, error)
        assertTrue(report, report.contains("kind=EXCEPTION_LOCATION"))
        assertEquals(3, report.lineSequence().count { it.contains(" | ") })
        assertTrue(report, report.contains("> 3 | " + source.getJSONArray("lines").getString(2)))
    }
}
