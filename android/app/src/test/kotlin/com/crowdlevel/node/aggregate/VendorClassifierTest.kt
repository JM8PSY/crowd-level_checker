package com.crowdlevel.node.aggregate

import org.junit.Assert.assertEquals
import org.junit.Test

class VendorClassifierTest {
    @Test
    fun `既知のCompany IDをカテゴリに変換する`() {
        assertEquals(Vendor.APPLE, VendorClassifier.classify(0x004C))
        assertEquals(Vendor.GOOGLE, VendorClassifier.classify(0x00E0))
        assertEquals(Vendor.MICROSOFT, VendorClassifier.classify(0x0006))
        assertEquals(Vendor.SAMSUNG, VendorClassifier.classify(0x0075))
    }

    @Test
    fun `未知のCompany IDはother`() {
        assertEquals(Vendor.OTHER, VendorClassifier.classify(0x1234))
    }

    @Test
    fun `メーカーデータなしはnone`() {
        assertEquals(Vendor.NONE, VendorClassifier.classify(null))
    }
}
