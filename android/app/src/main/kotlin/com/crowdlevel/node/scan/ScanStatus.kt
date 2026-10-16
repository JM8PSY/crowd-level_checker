package com.crowdlevel.node.scan

import com.crowdlevel.node.aggregate.WindowSummary
import com.crowdlevel.node.upload.UploadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Service の状態を UI に見せるためのプロセス内共有状態 */
data class ScanStatus(
    val serviceRunning: Boolean = false,
    val scanning: Boolean = false,
    val bluetoothOn: Boolean = false,
    val lastError: String? = null,
    /** 直近1秒間に受信したパケット数 */
    val packetsPerSec: Int = 0,
    /** 現在の窓で見えているユニーク端末数（暫定値） */
    val devicesInCurrentWindow: Int = 0,
    val currentWindowStartMillis: Long = 0,
    val lastSummary: WindowSummary? = null,
    /** 直近の窓の推定人数（その時点の式で計算したもの） */
    val lastEstimate: Int? = null,
    val lastLogPath: String? = null,
    val scanStartedAtMillis: Long = 0,
    /** 定期再起動の回数 */
    val restartCount: Int = 0,
    /** 結果が途絶えたことによる再起動の回数 */
    val noResultRestartCount: Int = 0,
    /** Ambient への送信状態 */
    val upload: UploadStatus? = null,
    /** Supabase への送信状態。設定が揃っていなければ null */
    val apiUpload: UploadStatus? = null,
)

object ScanStatusHolder {
    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status

    fun update(block: (ScanStatus) -> ScanStatus) = _status.update(block)
}
