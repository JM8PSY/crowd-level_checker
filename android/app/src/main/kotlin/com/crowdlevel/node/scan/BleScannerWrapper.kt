package com.crowdlevel.node.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import com.crowdlevel.node.aggregate.AddressHasher
import com.crowdlevel.node.aggregate.ScanEvent

/**
 * BluetoothLeScanner の薄いラッパ。
 * ScanResult をその場でハッシュ化して ScanEvent に変換し、生の MAC アドレスは外に出さない。
 * ScanCallback は OS によりメインスレッドで呼ばれる。start / stop もメインスレッドから呼ぶこと。
 *
 * 降格・停止への対策（効果は Android のバージョンで異なるため実機で確認する）:
 * - 中身が空の ScanFilter を1つ付ける。一部の Android では「フィルタ付き」とみなされ、
 *   30分でOPPORTUNISTICへ落とされる対象から外れる。新しい Android では空フィルタを
 *   フィルタなしと同じに扱うため、画面オフ中の停止は防げない可能性が高い
 * - そのため運用は画面常時点灯を基本とし、30分降格は呼び出し側の定期再起動で避ける
 * - startScan は 30秒に5回を超えると OS にブロックされるため、呼び出し側は乱発しないこと
 */
class BleScannerWrapper(
    private val adapter: BluetoothAdapter,
    private val hasher: AddressHasher,
    private val onEvent: (ScanEvent) -> Unit,
    private val onFailed: (Int) -> Unit,
) {
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    val isScanning: Boolean get() = callback != null

    @SuppressLint("MissingPermission") // 権限は Service 起動前に確認する
    fun start(): Boolean {
        if (callback != null) return true
        val leScanner = adapter.bluetoothLeScanner ?: return false
        val builder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
        // 拡張アドバタイズに対応したコントローラでのみ、拡張アドバタイズも受ける
        if (adapter.isLeExtendedAdvertisingSupported) builder.setLegacy(false)
        val settings = builder.build()
        val filters = listOf(ScanFilter.Builder().build())
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onEvent(toEvent(result))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) onEvent(toEvent(r))
            }

            override fun onScanFailed(errorCode: Int) {
                callback = null
                onFailed(errorCode)
            }
        }
        leScanner.startScan(filters, settings, cb)
        scanner = leScanner
        callback = cb
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { scanner?.stopScan(cb) }
        scanner = null
    }

    /**
     * 受信時刻はコールバック時点の壁時計を使う。reportDelay = 0 のため受信からの遅れはミリ秒程度で、
     * 窓の確定に使う時計（System.currentTimeMillis）と揃うので、時計の補正が入っても窓がずれない。
     */
    private fun toEvent(result: ScanResult): ScanEvent {
        val mfr = result.scanRecord?.manufacturerSpecificData
        val companyId = if (mfr != null && mfr.size() > 0) mfr.keyAt(0) else null
        return ScanEvent(
            hash = hasher.hash(result.device.address),
            rssi = result.rssi,
            companyId = companyId,
            epochMillis = System.currentTimeMillis(),
        )
    }

    companion object {
        fun errorName(code: Int): String = when (code) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APPLICATION_REGISTRATION_FAILED"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            5 -> "OUT_OF_HARDWARE_RESOURCES"
            6 -> "SCANNING_TOO_FREQUENTLY"
            else -> "UNKNOWN($code)"
        }
    }
}
