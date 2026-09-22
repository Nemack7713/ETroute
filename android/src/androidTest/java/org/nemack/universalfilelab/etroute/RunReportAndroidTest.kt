package org.nemack.universalfilelab.etroute

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunReportAndroidTest {

    @Test
    fun terminationClassificationIsDeterministic() {
        assertEquals(
            TerminationClass.TERM_OK,
            result(exitCode = 0).terminationClass()
        )
        assertEquals(
            TerminationClass.EXIT_NONZERO,
            result(exitCode = 7).terminationClass()
        )
        assertEquals(
            TerminationClass.SIGNALLED,
            result(exitCode = null, signal = 15).terminationClass()
        )
        assertEquals(
            TerminationClass.TIMEOUT,
            result(
                exitCode = null,
                signal = 9,
                timedOut = true,
                stage = NativeSpawnStage.TIMEOUT_KILL
            ).terminationClass()
        )
        assertEquals(
            TerminationClass.EXEC_FAILED,
            result(
                exitCode = 127,
                spawnErrno = 2,
                stage = NativeSpawnStage.EXECVE
            ).terminationClass()
        )
        assertEquals(
            TerminationClass.SPAWN_FAILED,
            result(
                exitCode = null,
                spawnErrno = 2,
                stage = NativeSpawnStage.CHDIR
            ).terminationClass()
        )
        assertEquals(
            TerminationClass.UNKNOWN,
            result(exitCode = null).terminationClass()
        )
        assertEquals(
            TerminationClass.CANCELLED,
            result(
                exitCode = null,
                signal = 9,
                timedOut = true,
                stage = NativeSpawnStage.TIMEOUT_KILL
            ).terminationClass(cancelled = true)
        )
    }

    private fun result(
        exitCode: Int? = null,
        signal: Int? = null,
        timedOut: Boolean = false,
        spawnErrno: Int = 0,
        stage: NativeSpawnStage = NativeSpawnStage.OK
    ): NativeRunResult =
        NativeRunResult(
            exitCode = exitCode,
            signal = signal,
            timedOut = timedOut,
            durationMs = 1,
            spawnErrno = spawnErrno,
            stage = stage
        )
}
