package com.crowdlevel.node.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.crowdlevel.node.settings.NodeConfig

@Composable
fun SettingsScreen(config: NodeConfig, onSave: (NodeConfig) -> Unit, onBack: () -> Unit) {
    var nodeId by rememberSaveable { mutableStateOf(config.nodeId) }
    var apiUrl by rememberSaveable { mutableStateOf(config.apiUrl) }
    var apiToken by rememberSaveable { mutableStateOf(config.apiToken) }
    var sendInterval by rememberSaveable { mutableStateOf(config.sendIntervalSec.toString()) }
    var debugLog by rememberSaveable { mutableStateOf(config.debugLog) }
    var keepScreenOn by rememberSaveable { mutableStateOf(config.keepScreenOn) }
    var ambientEnabled by rememberSaveable { mutableStateOf(config.ambientEnabled) }
    var ambientChannelId by rememberSaveable { mutableStateOf(config.ambientChannelId) }
    var ambientWriteKey by rememberSaveable { mutableStateOf(config.ambientWriteKey) }

    val intervalValue = sendInterval.toIntOrNull()
    val intervalValid = intervalValue != null && intervalValue in 10..3600

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("設定", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = nodeId, onValueChange = { nodeId = it }, label = { Text("ノードID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = apiUrl, onValueChange = { apiUrl = it }, label = { Text("API URL（Phase 2 で使用）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = apiToken, onValueChange = { apiToken = it }, label = { Text("API トークン（Phase 2 で使用）") },
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
        Text(
            "d1〜d5: RSSI -60/-70/-80/-90/全体の端末数、d6: Apple、d7: RSSI中央値、d8: パケット数",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = nodeId.isNotBlank() && intervalValid,
                onClick = {
                    onSave(
                        NodeConfig(
                            nodeId = nodeId.trim(),
                            apiUrl = apiUrl.trim(),
                            apiToken = apiToken.trim(),
                            sendIntervalSec = intervalValue ?: config.sendIntervalSec,
                            debugLog = debugLog,
                            keepScreenOn = keepScreenOn,
                            ambientEnabled = ambientEnabled,
                            ambientChannelId = ambientChannelId.trim(),
                            ambientWriteKey = ambientWriteKey.trim(),
                        ),
                    )
                },
            ) { Text("保存") }
            OutlinedButton(onClick = onBack) { Text("戻る") }
        }
    }
}
