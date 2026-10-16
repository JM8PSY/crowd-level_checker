package com.crowdlevel.node.aggregate

/**
 * 人数の推定に使わない端末を見分ける。
 *
 * - 探す（Apple 0x12）だけを出しているアドレス：「探す」の広告はアドレスを暗号の鍵から作るため、
 *   同じ iPhone などが Nearby Info（0x10）とは別のアドレスでも見え、1台が2台に数えられる。
 *   7a の実測（2026-10-17）で、強さのそろった Nearby Info と「探す」の対が3組あった。紛失中の AirTag なども外れる
 * - iBeacon（Apple 0x02）だけを出しているアドレス：人ではなく設置された機器
 *
 * Nearby Info などほかの種類も同じアドレスで出していれば数える。種類が分からない（メーカーデータがない、
 * Apple 以外）端末もこれまでどおり数える。
 */
object DeviceFilter {
    const val APPLE_COMPANY_ID = 0x004C
    const val APPLE_IBEACON = 0x02
    const val APPLE_FIND_MY = 0x12

    enum class Reason(val key: String) { FIND_MY("findmy"), IBEACON("ibeacon") }

    /** 数えない理由。数えるなら null */
    fun excludeReason(companyId: Int?, appleTypes: Collection<Int>): Reason? {
        if (companyId != APPLE_COMPANY_ID || appleTypes.isEmpty()) return null
        return when {
            appleTypes.all { it == APPLE_FIND_MY } -> Reason.FIND_MY
            appleTypes.all { it == APPLE_IBEACON } -> Reason.IBEACON
            appleTypes.all { it == APPLE_FIND_MY || it == APPLE_IBEACON } -> Reason.FIND_MY
            else -> null
        }
    }

    fun excludeReason(d: DeviceSummary): Reason? = excludeReason(d.companyId, d.appleTypes)

    fun isCounted(d: DeviceSummary): Boolean = excludeReason(d) == null
}
