package com.crowdlevel.node.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomsTest {
    @Test
    fun `選べる部屋はSupabaseのIDと同じ`() {
        assertEquals(listOf("rest-3f", "rest-2f"), Rooms.OPTIONS.map { it.id })
        assertEquals("rest-3f", Rooms.DEFAULT)
    }

    @Test
    fun `表示名と検証`() {
        assertEquals("2F休憩室", Rooms.labelOf("rest-2f"))
        assertEquals("room-1", Rooms.labelOf("room-1"))
        assertTrue(Rooms.isValid("rest-3f"))
        assertFalse(Rooms.isValid("room-1"))
    }
}
