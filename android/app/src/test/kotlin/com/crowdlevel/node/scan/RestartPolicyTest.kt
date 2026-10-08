package com.crowdlevel.node.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartPolicyTest {
    private val start = 1_000_000L
    private val sec = 1_000L

    private fun decide(now: Long, scanning: Boolean = true, lastStart: Long = start, lastResult: Long = start) =
        RestartPolicy.decide(nowMillis = now, scanning = scanning, lastStartMillis = lastStart, lastResultMillis = lastResult)

    @Test
    fun `スキャンしていなければ再起動しない`() {
        assertNull(decide(start + 10 * 60 * sec, scanning = false))
    }

    @Test
    fun `結果が届いている間は定期間隔まで再起動しない`() {
        val now = start + RestartPolicy.PERIODIC_MILLIS - sec
        assertNull(decide(now, lastResult = now - sec))
    }

    @Test
    fun `定期間隔に達したら再起動する`() {
        val now = start + RestartPolicy.PERIODIC_MILLIS
        assertEquals(RestartPolicy.Reason.PERIODIC, decide(now, lastResult = now - sec))
    }

    @Test
    fun `定期間隔はOSのスキャンタイムアウト5分より短い`() {
        assertTrue(RestartPolicy.PERIODIC_MILLIS < 5 * 60 * sec)
    }

    @Test
    fun `最後の結果から無受信の閾値を超えたら再起動する`() {
        val lastResult = start + 60 * sec
        val now = lastResult + RestartPolicy.NO_RESULT_MILLIS
        assertEquals(RestartPolicy.Reason.NO_RESULTS, decide(now, lastResult = lastResult))
    }

    @Test
    fun `再起動直後は結果がなくても閾値まで待つ`() {
        val restartedAt = start + 100 * sec
        val now = restartedAt + RestartPolicy.NO_RESULT_MILLIS - sec
        assertNull(decide(now, lastStart = restartedAt, lastResult = start))
    }

    @Test
    fun `無受信による再起動は閾値より短い間隔では繰り返さない`() {
        val restartedAt = start + 100 * sec
        assertNull(decide(restartedAt + sec, lastStart = restartedAt, lastResult = start))
        assertEquals(
            RestartPolicy.Reason.NO_RESULTS,
            decide(restartedAt + RestartPolicy.NO_RESULT_MILLIS, lastStart = restartedAt, lastResult = start),
        )
    }

    @Test
    fun `定期と無受信が同時なら定期を優先する`() {
        val now = start + RestartPolicy.PERIODIC_MILLIS
        assertEquals(RestartPolicy.Reason.PERIODIC, decide(now, lastResult = start))
    }
}
