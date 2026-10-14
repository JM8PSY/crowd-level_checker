package com.crowdlevel.node.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crowdlevel.node.estimate.Estimator
import com.crowdlevel.node.scan.ScanStatus
import com.crowdlevel.node.settings.NodeConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun fmtTime(millis: Long): String =
    if (millis == 0L) "--:--:--" else timeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

@Composable
fun DashboardScreen(
    status: ScanStatus,
    config: NodeConfig,
    permissionsGranted: Boolean,
    locationEnabled: Boolean,
    batteryExempt: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshChecks: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(config.nodeId, style = MaterialTheme.typography.headlineSmall)
            OutlinedButton(onClick = onOpenSettings) { Text("設定") }
        }

        // ---- セットアップ状態 ----
        if (!permissionsGranted || !locationEnabled || !batteryExempt || !status.bluetoothOn) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("セットアップ", style = MaterialTheme.typography.titleMedium)
                    if (!permissionsGranted) {
                        Text("権限（付近のデバイス・正確な位置情報）が未許可です")
                        Button(onClick = onRequestPermissions) { Text("権限を許可") }
                    }
                    if (!locationEnabled) {
                        Text("端末の位置情報サービスが OFF です。OFF だとスキャン結果が届きません")
                        Button(onClick = onOpenLocationSettings) { Text("位置情報の設定を開く") }
                    }
                    if (!status.bluetoothOn && status.serviceRunning) {
                        Text("Bluetooth が OFF です")
                        Button(onClick = onEnableBluetooth) { Text("Bluetooth を ON にする") }
                    }
                    if (!batteryExempt) {
                        Text("バッテリー最適化の対象です。長時間運用では除外を推奨します")
                        Button(onClick = onOpenBatterySettings) { Text("最適化を除外") }
                    }
                    OutlinedButton(onClick = onRefreshChecks) { Text("状態を再確認") }
                }
            }
        }

        // ---- スキャン状態 ----
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("スキャン状態", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        !status.serviceRunning -> "停止中"
                        status.scanning -> "スキャン中"
                        else -> "待機中"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                status.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("受信: ${status.packetsPerSec} パケット/秒")
                Text("現在の窓 ${fmtTime(status.currentWindowStartMillis)}〜: ${status.devicesInCurrentWindow} 台（暫定）")
                Text("スキャン開始 ${fmtTime(status.scanStartedAtMillis)}")
                Text("再起動 定期 ${status.restartCount} 回 / 無受信 ${status.noResultRestartCount} 回")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.serviceRunning) {
                        OutlinedButton(onClick = onStopService) { Text("停止") }
                    } else {
                        Button(onClick = onStartService, enabled = permissionsGranted) { Text("開始") }
                    }
                }
            }
        }

        // ---- 直近の確定窓 ----
        val s = status.lastSummary
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("直近の窓", style = MaterialTheme.typography.titleMedium)
                if (s == null) {
                    Text("まだ確定した窓がありません（分境界で確定します）")
                } else {
                    Text(
                        if (s.isPartial) "${s.observedAt}（観測が欠けた窓。送信しない）" else s.observedAt,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("推定人数 ${status.lastEstimate ?: "-"} 人", style = MaterialTheme.typography.headlineMedium)
                    Text("式: ${config.estimator().describe()}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("RSSI閾値別（累積）", style = MaterialTheme.typography.labelLarge)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        s.countsByRssi.forEach { (k, v) ->
                            Column {
                                Text(k, style = MaterialTheme.typography.labelSmall)
                                Text("$v", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("メーカー別", style = MaterialTheme.typography.labelLarge)
                    Text(s.countsByVendor.entries.joinToString("  ") { "${it.key}:${it.value}" })
                    Spacer(Modifier.height(4.dp))
                    val st = s.rssiStats
                    Text(
                        if (st == null) "RSSI統計: なし"
                        else "RSSI 中央値 ${st.median} / p10 ${st.p10} / p90 ${st.p90} dBm",
                    )
                    Text("パケット ${s.advCount} / バッテリー ${s.battery ?: "-"}% ${if (s.charging == true) "充電中" else ""}")
                }
            }
        }

        // ---- Ambient 送信 ----
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Ambient 送信", style = MaterialTheme.typography.titleMedium)
                val up = status.upload
                if (!config.ambientEnabled || up == null) {
                    Text("OFF")
                } else {
                    Text("ON  チャネル ${config.ambientChannelId}（d${config.ambientSlot} = 推定人数、${config.ambientIntervalMin}分ごと）")
                    Text("送信済み ${up.sentCount}点 / 失敗 ${up.failedCount}回 / 未送信 ${up.queued}点")
                    Text("最終成功 ${fmtTime(up.lastSuccessMillis)} / 最終試行 ${fmtTime(up.lastAttemptMillis)}")
                    up.lastMessage?.let {
                        Text(it, color = if (it.startsWith("OK")) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // ---- ログ ----
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("デバッグログ", style = MaterialTheme.typography.titleMedium)
                Text(if (config.debugLog) "ON（JSON Lines、1分ごとに追記）" else "OFF")
                status.lastLogPath?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
