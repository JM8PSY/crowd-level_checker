package com.crowdlevel.node.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientScheduleTest {
    /** 2026-10-08 10:15:00 JST（分の境界） */
    private val m = 1_791_422_100_000L

    @Test
    fun `スロットごとに毎分の送信時刻をずらす`() {
        assertEquals(2_000L, AmbientSchedule.offsetOf(1))
        assertEquals(9_000L, AmbientSchedule.offsetOf(2))
        assertEquals(51_000L, AmbientSchedule.offsetOf(8))
    }

    @Test
    fun `隣のスロットとは5秒以上あき、最後のスロットも同じ分に収まる`() {
        for (k in 1 until AmbientPayload.MAX_SLOT) {
            val gap = AmbientSchedule.offsetOf(k + 1) - (AmbientSchedule.offsetOf(k) + AmbientSchedule.SEND_WINDOW_MILLIS)
            assertTrue("d$k→d${k + 1}: $gap", gap >= 5_000)
        }
        // d8 の終わりから次の分の d1 まで
        val wrap = AmbientSchedule.MINUTE_MILLIS + AmbientSchedule.offsetOf(1) -
            (AmbientSchedule.offsetOf(AmbientPayload.MAX_SLOT) + AmbientSchedule.SEND_WINDOW_MILLIS)
        assertTrue("d8→d1: $wrap", wrap >= 5_000)
    }

    @Test
    fun `自分の時間帯より前なら時間帯の始まりまで待つ`() {
        assertEquals(m + 9_000, AmbientSchedule.nextSendTime(m + 1_000, slot = 2))
    }

    @Test
    fun `自分の時間帯の中ならすぐ送る`() {
        assertEquals(m + 9_500, AmbientSchedule.nextSendTime(m + 9_500, slot = 2))
    }

    @Test
    fun `自分の時間帯を過ぎていたら次の分まで待つ`() {
        assertEquals(m + 60_000 + 9_000, AmbientSchedule.nextSendTime(m + 10_000, slot = 2))
        assertEquals(m + 60_000 + 2_000, AmbientSchedule.nextSendTime(m + 59_999, slot = 1))
    }

    @Test
    fun `1日の件数の目安`() {
        assertEquals(2_880, AmbientSchedule.pointsPerDay(nodes = 2, intervalMin = 1))
        assertEquals(4_320, AmbientSchedule.pointsPerDay(nodes = 3, intervalMin = 1))
        assertEquals(2_880, AmbientSchedule.pointsPerDay(nodes = 4, intervalMin = 2))
    }
}
