package com.crowdlevel.node.aggregate

/** 窓の中で観測した1端末の要約 */
data class DeviceSummary(
    val hash: String,
    val maxRssi: Int,
    val advCount: Int,
    val companyId: Int?,
)

/** 確定した1窓の集計結果（Android 非依存） */
data class WindowResult(
    val windowStartMillis: Long,
    val windowSec: Int,
    /** 最大RSSIの降順 */
    val devices: List<DeviceSummary>,
    /** 窓内に受信したパケット総数 */
    val advCount: Int,
    /** 窓の全期間を観測できたか。起動直後やスキャンが止まっていた時間を含む窓は false */
    val complete: Boolean = true,
)

/**
 * 時計の分境界に揃えた固定窓で ScanEvent を集計する。
 *
 * 使い方:
 * - 受信したパケットごとに [add] を呼ぶ
 * - 1秒程度の周期で [flush] を呼び、境界を越えていれば確定結果を受け取る
 *
 * スレッドセーフではない。呼び出し側で直列化すること。
 */
class WindowAggregator(
    startMillis: Long,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private class Accumulator(var maxRssi: Int, var advCount: Int, var companyId: Int?)

    var currentWindowStart: Long = windowStartOf(startMillis, windowMillis)
        private set

    private val devices = HashMap<String, Accumulator>()
    private var advCount = 0
    private var incomplete = false

    /** 現在の窓でこれまでに見えたユニーク端末数（UI の暫定表示用） */
    val currentDeviceCount: Int get() = devices.size

    /** 現在の窓を「観測が欠けている」とする。窓が切り替わると解除される */
    fun markIncomplete() {
        incomplete = true
    }

    /**
     * パケットを現在の窓に加える。
     * イベントの時刻が現在の窓を越えていれば、先に現在の窓を確定して返し、イベントは新しい窓に入れる。
     * 現在の窓より古い時刻のイベントは（時計の揺らぎとみなして）現在の窓に入れる。
     */
    fun add(event: ScanEvent): WindowResult? {
        val closed = flush(event.epochMillis)
        val acc = devices[event.hash]
        if (acc == null) {
            devices[event.hash] = Accumulator(event.rssi, 1, event.companyId)
        } else {
            if (event.rssi > acc.maxRssi) acc.maxRssi = event.rssi
            acc.advCount += 1
            if (event.companyId != null) acc.companyId = event.companyId
        }
        advCount += 1
        return closed
    }

    /**
     * 現在時刻が窓の終わりを越えていれば現在の窓を確定し、現在時刻を含む窓に進む。
     * 越えていなければ null。空の窓もゼロ件の結果として返す。
     */
    fun flush(nowMillis: Long): WindowResult? {
        if (nowMillis < currentWindowStart + windowMillis) return null
        val result = WindowResult(
            windowStartMillis = currentWindowStart,
            windowSec = (windowMillis / 1000).toInt(),
            devices = devices.map { (hash, acc) -> DeviceSummary(hash, acc.maxRssi, acc.advCount, acc.companyId) }
                .sortedWith(compareByDescending<DeviceSummary> { it.maxRssi }.thenBy { it.hash }),
            advCount = advCount,
            complete = !incomplete,
        )
        devices.clear()
        advCount = 0
        incomplete = false
        currentWindowStart = windowStartOf(nowMillis, windowMillis)
        return result
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 60_000L

        fun windowStartOf(epochMillis: Long, windowMillis: Long = DEFAULT_WINDOW_MILLIS): Long =
            epochMillis - Math.floorMod(epochMillis, windowMillis)
    }
}
