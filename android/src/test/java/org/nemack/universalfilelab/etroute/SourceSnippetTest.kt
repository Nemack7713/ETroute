package org.nemack.universalfilelab.etroute

import org.junit.Assert.*
import org.junit.Test

class SourceSnippetTest {
    private val source = DiagnosticSource("android/src/main/java/example/Demo.kt", "example", "Demo.kt",
        "a".repeat(64), listOf("val before = 1", "check(false)", "val after = 2"))
    private fun frame(line: Int) = StackTraceElement("example.Demo", "run", "Demo.kt", line)
    @Test fun exactlyThreeSourceRowsWithFaultLocationInMiddle() {
        val report = SourceSnippet.render(listOf(source), frame(2), true)!!
        val rows = report.lines().filter { it.contains(" | ") }
        assertEquals(listOf("  1 | val before = 1", "> 2 | check(false)", "  3 | val after = 2"), rows)
        assertTrue(report.contains("kind=EXCEPTION_LOCATION"))
    }
    @Test fun boundariesAreExplicitRatherThanInventedCode() {
        assertTrue(SourceSnippet.render(listOf(source), frame(1), true)!!.contains("0 | [start of file]"))
        assertTrue(SourceSnippet.render(listOf(source), frame(3), true)!!.contains("4 | [end of file]"))
    }
    @Test fun ambiguousAndSyntheticLocationsAreNotGuessed() {
        assertNull(SourceSnippet.render(listOf(source, source.copy(path = "other/Demo.kt")), frame(2), true))
        assertNull(SourceSnippet.render(listOf(source), frame(100), true))
        assertNull(SourceSnippet.render(listOf(source), frame(-1), true))
        assertNull(SourceSnippet.render(listOf(source), StackTraceElement("outside.Demo", "run", "Demo.kt", 2), true))
    }
    @Test fun externalFailureIsLabelledAsApplicationCallSite() {
        val report = SourceSnippet.render(listOf(source), frame(2), false)!!
        assertTrue(report.contains("kind=APPLICATION_CALL_SITE"))
        assertTrue(report.contains("Original exception location is outside"))
    }
}
