package com.crowdlevel.node.estimate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EstimatorTest {
    private val counts = linkedMapOf("-60" to 21, "-70" to 32, "-80" to 71, "-90" to 75, "all" to 75)

    @Test
    fun `係数1と切片0なら特徴量そのもの`() {
        assertEquals(71, Estimator("-80", 1.0, 0.0).estimate(counts))
    }

    @Test
    fun `係数と切片をかけて最も近い整数に丸める`() {
        assertEquals(26, Estimator("-80", 0.35, 1.0).estimate(counts)) // 0.35 × 71 + 1 = 25.85
        assertEquals(11, Estimator("-70", 0.33, 0.0).estimate(counts)) // 0.33 × 32 = 10.56
    }

    @Test
    fun `負になる場合は0にする`() {
        assertEquals(0, Estimator("-60", 0.1, -5.0).estimate(counts))
    }

    @Test
    fun `allも特徴量に選べる`() {
        assertEquals(75, Estimator("all", 1.0, 0.0).estimate(counts))
    }

    @Test
    fun `存在しない特徴量ならnull`() {
        assertNull(Estimator("-50", 1.0, 0.0).estimate(counts))
    }

    @Test
    fun `選べる特徴量は集計のキーと一致する`() {
        assertEquals(listOf("-60", "-70", "-80", "-90", "all"), Estimator.FEATURES)
    }

    @Test
    fun `式を人が読める形で表す`() {
        assertEquals("0.35 × (-80 dBm以上の端末数) + 1.0", Estimator("-80", 0.35, 1.0).describe())
        assertEquals("1.0 × (全端末数) + 0.0", Estimator("all", 1.0, 0.0).describe())
    }
}
