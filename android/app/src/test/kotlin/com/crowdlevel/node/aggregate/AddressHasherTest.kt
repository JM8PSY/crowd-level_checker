package com.crowdlevel.node.aggregate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressHasherTest {
    private val salt = ByteArray(16) { it.toByte() }
    private val address = "AA:BB:CC:DD:EE:FF"

    @Test
    fun `同じソルトと同じアドレスなら同じハッシュ`() {
        val a = AddressHasher(salt).hash(address)
        val b = AddressHasher(salt).hash(address)
        assertEquals(a, b)
    }

    @Test
    fun `ハッシュは16文字の小文字16進`() {
        val h = AddressHasher(salt).hash(address)
        assertEquals(16, h.length)
        assertTrue(h.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun `ソルトが違えばハッシュも違う`() {
        val other = ByteArray(16) { (it + 1).toByte() }
        assertNotEquals(AddressHasher(salt).hash(address), AddressHasher(other).hash(address))
    }

    @Test
    fun `アドレスが違えばハッシュも違う`() {
        val h = AddressHasher(salt)
        assertNotEquals(h.hash(address), h.hash("AA:BB:CC:DD:EE:F0"))
    }

    @Test
    fun `ハッシュに生アドレスの断片が含まれない`() {
        val h = AddressHasher(salt).hash(address)
        assertFalse(h.contains("aabbcc"))
        assertFalse(h.contains("ddeeff"))
    }

    @Test
    fun `randomSaltは16バイトで毎回異なる`() {
        val a = AddressHasher.randomSalt()
        val b = AddressHasher.randomSalt()
        assertEquals(16, a.size)
        assertFalse(a.contentEquals(b))
    }
}
