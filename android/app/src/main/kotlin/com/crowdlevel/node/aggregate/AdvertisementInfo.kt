package com.crowdlevel.node.aggregate

/**
 * 広告データから、端末の「種類」を見分けるための値だけを取り出す（校正・調査用。端末内ログにだけ残す）。
 * 個人を識別できる値（アドレス、Apple データの中身そのもの）は取り出さない。
 */
object AdvertisementInfo {
    private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /** Bluetooth の基本 UUID の 16bit 短縮形は "fe9f" のように4桁で、それ以外はそのまま（小文字） */
    fun shortUuid(uuid: String): String {
        val u = uuid.lowercase()
        return if (u.length == 36 && u.startsWith("0000") && u.endsWith(BASE_UUID_SUFFIX)) u.substring(4, 8) else u
    }

    /**
     * Apple（Company ID 0x004C）のメーカーデータに並んでいる種類の番号の一覧。
     * データは「種類(1バイト) 長さ(1バイト) 中身」の繰り返し。例: 0x10 = 近くの端末への通知（Nearby Info）、
     * 0x12 = 探す（Offline Finding）、0x07 = AirPods など、0x0C = Handoff、0x02 = iBeacon。
     * 中身は読まない。形が崩れていたらそこまでで打ち切る
     */
    fun appleTypes(data: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xff
            val len = data[i + 1].toInt() and 0xff
            if (i + 2 + len > data.size) break
            types += type
            i += 2 + len
        }
        return types
    }
}
