package com.crowdlevel.node.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crowdlevel.node.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "node_settings")

/** 設定値のスナップショット */
data class NodeConfig(
    val nodeId: String,
    val apiUrl: String,
    val apiToken: String,
    val sendIntervalSec: Int,
    val debugLog: Boolean,
    val keepScreenOn: Boolean,
    /** Ambient（ambidata.io）への送信。動作確認用 */
    val ambientEnabled: Boolean,
    val ambientChannelId: String,
    val ambientWriteKey: String,
) {
    companion object {
        /** Ambient の既定値は local.properties → BuildConfig から読む（リポジトリには含めない） */
        val DEFAULT = NodeConfig(
            nodeId = "node-01",
            apiUrl = "",
            apiToken = "",
            sendIntervalSec = 60,
            debugLog = true,
            keepScreenOn = true,
            ambientEnabled = BuildConfig.AMBIENT_WRITE_KEY.isNotEmpty(),
            ambientChannelId = BuildConfig.AMBIENT_CHANNEL_ID,
            ambientWriteKey = BuildConfig.AMBIENT_WRITE_KEY,
        )
    }
}

/**
 * DataStore に保存するノード設定。
 * API トークンは端末内にのみ保存し、リポジトリやログには出さない。
 */
class NodeSettings(private val context: Context) {
    private object Keys {
        val NODE_ID = stringPreferencesKey("node_id")
        val API_URL = stringPreferencesKey("api_url")
        val API_TOKEN = stringPreferencesKey("api_token")
        val SEND_INTERVAL_SEC = intPreferencesKey("send_interval_sec")
        val DEBUG_LOG = booleanPreferencesKey("debug_log")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val AMBIENT_ENABLED = booleanPreferencesKey("ambient_enabled")
        val AMBIENT_CHANNEL_ID = stringPreferencesKey("ambient_channel_id")
        val AMBIENT_WRITE_KEY = stringPreferencesKey("ambient_write_key")
    }

    val config: Flow<NodeConfig> = context.dataStore.data.map { p ->
        val d = NodeConfig.DEFAULT
        NodeConfig(
            nodeId = p[Keys.NODE_ID] ?: d.nodeId,
            apiUrl = p[Keys.API_URL] ?: d.apiUrl,
            apiToken = p[Keys.API_TOKEN] ?: d.apiToken,
            sendIntervalSec = p[Keys.SEND_INTERVAL_SEC] ?: d.sendIntervalSec,
            debugLog = p[Keys.DEBUG_LOG] ?: d.debugLog,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: d.keepScreenOn,
            ambientEnabled = p[Keys.AMBIENT_ENABLED] ?: d.ambientEnabled,
            ambientChannelId = p[Keys.AMBIENT_CHANNEL_ID] ?: d.ambientChannelId,
            ambientWriteKey = p[Keys.AMBIENT_WRITE_KEY] ?: d.ambientWriteKey,
        )
    }

    suspend fun current(): NodeConfig = config.first()

    suspend fun update(config: NodeConfig) {
        context.dataStore.edit { p ->
            p[Keys.NODE_ID] = config.nodeId
            p[Keys.API_URL] = config.apiUrl
            p[Keys.API_TOKEN] = config.apiToken
            p[Keys.SEND_INTERVAL_SEC] = config.sendIntervalSec
            p[Keys.DEBUG_LOG] = config.debugLog
            p[Keys.KEEP_SCREEN_ON] = config.keepScreenOn
            p[Keys.AMBIENT_ENABLED] = config.ambientEnabled
            p[Keys.AMBIENT_CHANNEL_ID] = config.ambientChannelId
            p[Keys.AMBIENT_WRITE_KEY] = config.ambientWriteKey
        }
    }
}
