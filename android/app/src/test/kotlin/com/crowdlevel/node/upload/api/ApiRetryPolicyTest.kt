package com.crowdlevel.node.upload.api

import com.crowdlevel.node.upload.api.ApiRetryPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiRetryPolicyTest {
    @Test
    fun `成功はキューから消す`() {
        assertEquals(Action.DONE, ApiRetryPolicy.decide(200))
        assertEquals(Action.DONE, ApiRetryPolicy.decide(204))
    }

    @Test
    fun `トークンやURLの誤りと一時的な失敗は再送する`() {
        listOf(401, 403, 404, 408, 429, 500, 502, 503).forEach { assertEquals("$it", Action.RETRY, ApiRetryPolicy.decide(it)) }
    }

    @Test
    fun `中身の誤りは捨てる`() {
        assertEquals(Action.DISCARD, ApiRetryPolicy.decide(400))
        assertEquals(Action.DISCARD, ApiRetryPolicy.decide(422))
    }
}
