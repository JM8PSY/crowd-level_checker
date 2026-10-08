package com.crowdlevel.node.aggregate

/** メーカーデータ（Company ID）によるカテゴリ。key は JSON 上のキー名 */
enum class Vendor(val key: String) {
    APPLE("apple"),
    GOOGLE("google"),
    MICROSOFT("microsoft"),
    SAMSUNG("samsung"),
    OTHER("other"),
    NONE("none"),
}

object VendorClassifier {
    private const val APPLE = 0x004C
    private const val GOOGLE = 0x00E0
    private const val MICROSOFT = 0x0006
    private const val SAMSUNG = 0x0075

    fun classify(companyId: Int?): Vendor = when (companyId) {
        null -> Vendor.NONE
        APPLE -> Vendor.APPLE
        GOOGLE -> Vendor.GOOGLE
        MICROSOFT -> Vendor.MICROSOFT
        SAMSUNG -> Vendor.SAMSUNG
        else -> Vendor.OTHER
    }
}
