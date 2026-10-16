package com.crowdlevel.node.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.crowdlevel.node.estimate.Estimator
import com.crowdlevel.node.settings.NodeConfig
import com.crowdlevel.node.settings.NodeSettings
import com.crowdlevel.node.upload.AmbientPayload
import com.crowdlevel.node.upload.AmbientSchedule

@Composable
fun SettingsScreen(config: NodeConfig, onSave: (NodeConfig) -> Unit, onBack: () -> Unit) {
    var nodeId by rememberSaveable { mutableStateOf(config.nodeId) }
    var apiUrl by rememberSaveable { mutableStateOf(config.apiUrl) }
    var apiKey by rememberSaveable { mutableStateOf(config.apiKey) }
    var apiToken by rememberSaveable { mutableStateOf(config.apiToken) }
    var sendInterval by rememberSaveable { mutableStateOf(config.sendIntervalSec.toString()) }
    var debugLog by rememberSaveable { mutableStateOf(config.debugLog) }
    var keepScreenOn by rememberSaveable { mutableStateOf(config.keepScreenOn) }
    var ambientEnabled by rememberSaveable { mutableStateOf(config.ambientEnabled) }
    var ambientChannelId by rememberSaveable { mutableStateOf(config.ambientChannelId) }
    var ambientWriteKey by rememberSaveable { mutableStateOf(config.ambientWriteKey) }
    var ambientSlot by rememberSaveable { mutableStateOf(config.ambientSlot.toString()) }
    var ambientInterval by rememberSaveable { mutableStateOf(config.ambientIntervalMin.toString()) }
    var estimateFeature by rememberSaveable { mutableStateOf(config.estimateFeature) }
    var estimateSlope by rememberSaveable { mutableStateOf(config.estimateSlope.toString()) }
    var estimateIntercept by rememberSaveable { mutableStateOf(config.estimateIntercept.toString()) }

    val intervalValue = sendInterval.toIntOrNull()
    val intervalValid = intervalValue != null && intervalValue in 10..3600
    val slotValue = ambientSlot.trim().toIntOrNull()?.takeIf(AmbientPayload::isValidSlot)
    val ambientIntervalValue = ambientInterval.trim().toIntOrNull()?.takeIf { it in NodeSettings.AMBIENT_INTERVAL_RANGE }
    val slopeValue = estimateSlope.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    val interceptValue = estimateIntercept.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = nodeId, onValueChange = { nodeId = it }, label = { Text("ノードID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = apiUrl, onValueChange = { apiUrl = it }, label = { Text("Supabase の URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("Supabase の publishable key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = apiToken, onValueChange = { apiToken = it }, label = { Text("ノードのトークン") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = sendInterval, onValueChange = { sendInterval = it }, label = { Text("送信間隔 [秒]（10〜3600）") },
            singleLine = true, isError = !intervalValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("デバッグログ（端末内 JSON Lines）")
            Switch(checked = debugLog, onCheckedChange = { debugLog = it })
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("画面を常時点灯（最低輝度）")
            Switch(checked = keepScreenOn, onCheckedChange = { keepScreenOn = it })
        }
        Text("Ambient（動作確認用）", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Ambient へ送信")
            Switch(checked = ambientEnabled, onCheckedChange = { ambientEnabled = it })
        }
        OutlinedTextField(
            value = ambientChannelId, onValueChange = { ambientChannelId = it }, label = { Text("チャネルID") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ambientWriteKey, onValueChange = { ambientWriteKey = it }, label = { Text("ライトキー") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ambientSlot, onValueChange = { ambientSlot = it },
            label = { Text("データ欄の番号（1〜${AmbientPayload.MAX_SLOT} → d1〜d${AmbientPayload.MAX_SLOT}）") },
            singleLine = true, isError = slotValue == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = ambientInterval, onValueChange = { ambientInterval = it },
            label = { Text("Ambient への送信間隔 [分]（${NodeSettings.AMBIENT_INTERVAL_RANGE.first}〜${NodeSettings.AMBIENT_INTERVAL_RANGE.last}）") },
            singleLine = true, isError = ambientIntervalValue == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "推定人数だけを d${slotValue ?: "?"} に送ります。同じチャネルを使うノードどうしで番号を重ねないでください。" +
                "Ambient のグラフで d1〜d${AmbientPayload.MAX_SLOT} を1つの折れ線グラフに表示しておくと、ノードを足したときに線が増えます。\n" +
                "1チャネル1日${AmbientSchedule.DAILY_LIMIT}件まで：ノード数 × 1440 ÷ 間隔 ≦ ${AmbientSchedule.DAILY_LIMIT}" +
                "（間隔1分なら2台、2分なら4台、3分なら6台まで）。間隔が2分以上のときは区間内の平均を送ります。",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("推定人数の式（校正前の暫定）", style = MaterialTheme.typography.titleMedium)
        Text(
            "推定人数 = 係数 × 特徴量 + 切片（四捨五入、0未満は0）",
            style = MaterialTheme.typography.bodySmall,
        )
        Estimator.FEATURES.forEach { key ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = estimateFeature == key, onClick = { estimateFeature = key }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = estimateFeature == key, onClick = null)
                Text(Estimator.featureLabel(key))
            }
        }
        OutlinedTextField(
            value = estimateSlope, onValueChange = { estimateSlope = it }, label = { Text("係数") },
            singleLine = true, isError = slopeValue == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = estimateIntercept, onValueChange = { estimateIntercept = it }, label = { Text("切片") },
            singleLine = true, isError = interceptValue == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = nodeId.isNotBlank() && intervalValid && slopeValue != null && interceptValue != null &&
                    slotValue != null && ambientIntervalValue != null,
                onClick = {
                    onSave(
                        NodeConfig(
                            nodeId = nodeId.trim(),
                            roomId = config.roomId, // 部屋はダッシュボードのトグルで切り替える
                            apiUrl = apiUrl.trim(),
                            apiKey = apiKey.trim(),
                            apiToken = apiToken.trim(),
                            sendIntervalSec = intervalValue ?: config.sendIntervalSec,
                            debugLog = debugLog,
                            keepScreenOn = keepScreenOn,
                            ambientEnabled = ambientEnabled,
                            ambientChannelId = ambientChannelId.trim(),
                            ambientWriteKey = ambientWriteKey.trim(),
                            ambientSlot = slotValue ?: config.ambientSlot,
                            ambientIntervalMin = ambientIntervalValue ?: config.ambientIntervalMin,
                            estimateFeature = estimateFeature,
                            estimateSlope = slopeValue ?: config.estimateSlope,
                            estimateIntercept = interceptValue ?: config.estimateIntercept,
                        ),
                    )
                },
            ) { Text("保存") }
            OutlinedButton(onClick = onBack) { Text("戻る") }
        }
    }
}
