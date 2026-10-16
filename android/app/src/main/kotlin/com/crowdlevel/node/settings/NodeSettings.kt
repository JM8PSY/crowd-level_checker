package com.crowdlevel.node.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.crowdlevel.node.BuildConfig
import com.crowdlevel.node.estimate.Estimator
import com.crowdlevel.node.upload.AmbientPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "node_settings")

/** 設定値のスナップショット */
data class NodeConfig(
    val nodeId: String,
    /** 送信先の Supabase の URL（https://<ref>.supabase.co） */
    val apiUrl: String,
    /** Supabase の publishable key（公開してよいキー） */
    val apiKey: String,
    /** ノードのトークン（SQL Editor の private.add_node で発行したもの） */
    val apiToken: String,
    val sendIntervalSec: Int,
    val debugLog: Boolean,
    val keepScreenOn: Boolean,
    /** Ambient（ambidata.io）への送信。動作確認用 */
    val ambientEnabled: Boolean,
    val ambientChannelId: String,
    val ambientWriteKey: String,
    /** このノードが書き込む Ambient のデータ欄（1〜8 → d1〜d8）。同じチャネルを共有するノードどうしで重ねない */
    val ambientSlot: Int,
    /** Ambient へ1点送る間隔 [分]。区間内の推定人数の平均を送る。ノード数 × 1440 ÷ 間隔 ≦ 3000 にする */
    val ambientIntervalMin: Int,
    /** 推定人数の式（校正前の暫定）。推定人数 = estimateSlope × 特徴量 + estimateIntercept */
    val estimateFeature: String,
    val estimateSlope: Double,
    val estimateIntercept: Double,
) {
    fun estimator(): Estimator = Estimator(estimateFeature, estimateSlope, estimateIntercept)

    companion object {
        /** Ambient・ノードの既定値は local.properties → BuildConfig から読む（リポジトリには含めない） */
        val DEFAULT = NodeConfig(
            nodeId = BuildConfig.NODE_ID,
            apiUrl = BuildConfig.SUPABASE_URL,
            apiKey = BuildConfig.SUPABASE_KEY,
            apiToken = BuildConfig.NODE_TOKEN,
            sendIntervalSec = 60,
            debugLog = true,
            keepScreenOn = true,
            ambientEnabled = BuildConfig.AMBIENT_WRITE_KEY.isNotEmpty(),
            ambientChannelId = BuildConfig.AMBIENT_CHANNEL_ID,
            ambientWriteKey = BuildConfig.AMBIENT_WRITE_KEY,
            ambientSlot = 1,
            ambientIntervalMin = 1,
            // 校正するまでは係数1・切片0（-80 dBm以上の端末数をそのまま推定人数とする）
            estimateFeature = "-80",
            estimateSlope = 1.0,
            estimateIntercept = 0.0,
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
        val API_KEY = stringPreferencesKey("api_key")
        val API_TOKEN = stringPreferencesKey("api_token")
        val SEND_INTERVAL_SEC = intPreferencesKey("send_interval_sec")
        val DEBUG_LOG = booleanPreferencesKey("debug_log")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val AMBIENT_ENABLED = booleanPreferencesKey("ambient_enabled")
        val AMBIENT_CHANNEL_ID = stringPreferencesKey("ambient_channel_id")
        val AMBIENT_WRITE_KEY = stringPreferencesKey("ambient_write_key")
        val AMBIENT_SLOT = intPreferencesKey("ambient_slot")
        val AMBIENT_INTERVAL_MIN = intPreferencesKey("ambient_interval_min")
        val ESTIMATE_FEATURE = stringPreferencesKey("estimate_feature")
        val ESTIMATE_SLOPE = doublePreferencesKey("estimate_slope")
        val ESTIMATE_INTERCEPT = doublePreferencesKey("estimate_intercept")
    }

    val config: Flow<NodeConfig> = context.dataStore.data.map { p ->
        val d = NodeConfig.DEFAULT
        NodeConfig(
            nodeId = p[Keys.NODE_ID] ?: d.nodeId,
            // 空で保存されている（Phase 1 で未使用だった）ときはビルド時の既定値を使う
            apiUrl = p[Keys.API_URL]?.takeIf { it.isNotBlank() } ?: d.apiUrl,
            apiKey = p[Keys.API_KEY]?.takeIf { it.isNotBlank() } ?: d.apiKey,
            apiToken = p[Keys.API_TOKEN]?.takeIf { it.isNotBlank() } ?: d.apiToken,
            sendIntervalSec = p[Keys.SEND_INTERVAL_SEC] ?: d.sendIntervalSec,
            debugLog = p[Keys.DEBUG_LOG] ?: d.debugLog,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: d.keepScreenOn,
            ambientEnabled = p[Keys.AMBIENT_ENABLED] ?: d.ambientEnabled,
            ambientChannelId = p[Keys.AMBIENT_CHANNEL_ID] ?: d.ambientChannelId,
            ambientWriteKey = p[Keys.AMBIENT_WRITE_KEY] ?: d.ambientWriteKey,
            ambientSlot = p[Keys.AMBIENT_SLOT]?.takeIf(AmbientPayload::isValidSlot) ?: d.ambientSlot,
            ambientIntervalMin = p[Keys.AMBIENT_INTERVAL_MIN]?.takeIf { it in AMBIENT_INTERVAL_RANGE } ?: d.ambientIntervalMin,
            estimateFeature = p[Keys.ESTIMATE_FEATURE]?.takeIf { it in Estimator.FEATURES } ?: d.estimateFeature,
            estimateSlope = p[Keys.ESTIMATE_SLOPE] ?: d.estimateSlope,
            estimateIntercept = p[Keys.ESTIMATE_INTERCEPT] ?: d.estimateIntercept,
        )
    }

    companion object {
        /** Ambient の送信間隔として選べる範囲 [分] */
        val AMBIENT_INTERVAL_RANGE = 1..10
    }

    suspend fun current(): NodeConfig = config.first()

    suspend fun update(config: NodeConfig) {
        context.dataStore.edit { p ->
            p[Keys.NODE_ID] = config.nodeId
            p[Keys.API_URL] = config.apiUrl
            p[Keys.API_KEY] = config.apiKey
            p[Keys.API_TOKEN] = config.apiToken
            p[Keys.SEND_INTERVAL_SEC] = config.sendIntervalSec
            p[Keys.DEBUG_LOG] = config.debugLog
            p[Keys.KEEP_SCREEN_ON] = config.keepScreenOn
            p[Keys.AMBIENT_ENABLED] = config.ambientEnabled
            p[Keys.AMBIENT_CHANNEL_ID] = config.ambientChannelId
            p[Keys.AMBIENT_WRITE_KEY] = config.ambientWriteKey
            p[Keys.AMBIENT_SLOT] = config.ambientSlot
            p[Keys.AMBIENT_INTERVAL_MIN] = config.ambientIntervalMin
            p[Keys.ESTIMATE_FEATURE] = config.estimateFeature
            p[Keys.ESTIMATE_SLOPE] = config.estimateSlope
            p[Keys.ESTIMATE_INTERCEPT] = config.estimateIntercept
        }
    }
}
