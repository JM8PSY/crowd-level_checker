package com.crowdlevel.node.upload

import kotlin.math.roundToInt

/**
 * 1分窓の推定人数を、Ambient に送る間隔（intervalMin 分）ごとに1点へまとめる（Android 非依存）。
 *
 * Ambient は1チャネル1日3,000件まで。1分ごとだと1ノード1,440件なので、3台以上で共有するときは
 * 間隔を2分以上にする。間隔1分なら窓ごとにそのまま1点を返す。
 *
 * - 区間は時計に揃える（間隔2分なら 10:00〜10:02、10:02〜10:04 …）。点の時刻は区間の開始
 * - 値は区間内の完全な窓の推定人数の平均（四捨五入）。完全な窓が1つもなければ点を作らない
 * - 区間の最後の窓を受け取った時点で点を確定する。窓が飛んだ場合は、次の区間の窓が来た時点で確定する
 *
 * スレッドセーフではない。呼び出し側で直列化すること。
 */
class AmbientBatcher(
    val intervalMin: Int,
    private val windowMillis: Long = 60_000L,
) {
    data class Point(val startMillis: Long, val value: Int)

    private val blockMillis = intervalMin * windowMillis
    private var blockStart = Long.MIN_VALUE
    private val values = ArrayList<Int>()

    init {
        require(intervalMin >= 1) { "intervalMin は1以上: $intervalMin" }
    }

    /**
     * 確定した窓を1つ加える。
     * @param estimate 推定人数。観測が欠けた窓など、送らない窓は null
     * @return 確定した点（0〜2個、古い順）
     */
    fun add(windowStartMillis: Long, estimate: Int?): List<Point> {
        val out = ArrayList<Point>(2)
        val start = windowStartMillis - Math.floorMod(windowStartMillis, blockMillis)
        if (start != blockStart) {
            close()?.let(out::add)
            blockStart = start
        }
        if (estimate != null) values += estimate
        if (windowStartMillis + windowMillis >= blockStart + blockMillis) close()?.let(out::add)
        return out
    }

    private fun close(): Point? {
        if (values.isEmpty()) return null
        val p = Point(blockStart, values.average().roundToInt())
        values.clear()
        return p
    }
}
