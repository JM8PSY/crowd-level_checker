package com.crowdlevel.node.scan

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.crowdlevel.node.BuildConfig
import com.crowdlevel.node.R
import com.crowdlevel.node.aggregate.AddressHasher
import com.crowdlevel.node.aggregate.ScanEvent
import com.crowdlevel.node.aggregate.WindowAggregator
import com.crowdlevel.node.aggregate.WindowResult
import com.crowdlevel.node.aggregate.WindowSummary
import com.crowdlevel.node.log.JsonlLogger
import com.crowdlevel.node.settings.NodeConfig
import com.crowdlevel.node.settings.NodeSettings
import com.crowdlevel.node.settings.Rooms
import com.crowdlevel.node.ui.MainActivity
import com.crowdlevel.node.upload.AmbientUploader
import com.crowdlevel.node.upload.api.ApiUploader
import com.crowdlevel.node.upload.api.QueueDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId

/**
 * BLE スキャンを常時実行する Foreground Service。
 *
 * スレッドの扱い:
 * - スキャン結果、1秒周期の ticker、Bluetooth 状態の変化はすべてメインスレッドで処理する。
 *   そのため scanner と aggregator はメインスレッドからしか触らない
 * - 窓が確定したら、ファイル書き込みと送信は直列化した IO スレッドで行う
 *
 * 観測が欠けた窓:
 * - スキャンを開始・再開した窓（起動直後、Bluetooth 復帰、失敗からの再試行）と、
 *   スキャンが止まっている間の窓には印を付け、JSON で "partial": true とする
 * - 部分的な窓は端末内ログには残すが、Ambient には送らない
 * - Ambient には設定の式で計算した推定人数だけを、設定したデータ欄（d1〜d8）へ送る
 * - 再起動（定期・無受信）による数ミリ秒の中断は無視する
 *
 * そのほか:
 * - RestartPolicy に従ってスキャンを stop/start する。OS がフィルタなしのスキャンを
 *   数分で OPPORTUNISTIC に移して結果を止めるのを避けるため
 * - 常時給電前提のため、Service が動いている間は PARTIAL_WAKE_LOCK で CPU を止めない
 */
class BleScanService : LifecycleService() {

    private lateinit var settings: NodeSettings
    private lateinit var adapter: BluetoothAdapter
    private lateinit var hasher: AddressHasher
    private var scanner: BleScannerWrapper? = null
    private val aggregator = WindowAggregator(System.currentTimeMillis())
    private var tickerJob: Job? = null
    private var retryJob: Job? = null
    private var uploadStatusJob: Job? = null
    private var apiStatusJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var packetCounter = 0
    private var lastStartMillis = 0L
    private var lastResultMillis = 0L

    // 以下は IO スレッドからも読む
    @Volatile private var config: NodeConfig = NodeConfig.DEFAULT
    @Volatile private var logger: JsonlLogger? = null
    @Volatile private var uploader: AmbientUploader? = null
    @Volatile private var apiUploader: ApiUploader? = null
    /** 最後に部屋を切り替えた時刻。この時刻より前に始まった窓は2部屋が混ざるので部分的な窓にする */
    @Volatile private var roomChangedAtMillis = 0L

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 窓の確定後の処理（ログ・送信）を確定順に1件ずつ実行する */
    private val publishDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth ON")
                    ScanStatusHolder.update { it.copy(bluetoothOn = true, lastError = null) }
                    startScan()
                }
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    Log.w(TAG, "Bluetooth OFF")
                    scanner?.stop()
                    ScanStatusHolder.update { it.copy(bluetoothOn = false, scanning = false, lastError = "Bluetooth OFF") }
                    updateNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = NodeSettings(this)
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        hasher = AddressHasher(AddressHasher.randomSalt())
        createChannel()
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleScope.launch {
            settings.config.collectLatest { c ->
                if (c.roomId != config.roomId && config !== NodeConfig.DEFAULT) {
                    roomChangedAtMillis = System.currentTimeMillis()
                    Log.i(TAG, "room changed: ${config.roomId} -> ${c.roomId}")
                }
                config = c
                logger = if (c.debugLog) JsonlLogger(logDir(this@BleScanService), zone) else null
                rebuildUploader(c)
                rebuildApiUploader(c)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        acquireWakeLock()
        ScanStatusHolder.update { it.copy(serviceRunning = true, bluetoothOn = adapter.isEnabled) }
        startScan()
        startTicker()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        tickerJob?.cancel()
        retryJob?.cancel()
        scanner?.stop()
        uploader?.close()
        apiUploader?.close()
        releaseWakeLock()
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        ScanStatusHolder.update { it.copy(serviceRunning = false, scanning = false) }
        super.onDestroy()
    }

    /** Ambient の設定が変わったら送信器を作り直す（未送信キューと、まとめ途中の区間は破棄される） */
    private fun rebuildUploader(c: NodeConfig) {
        val enabled = c.ambientEnabled && c.ambientChannelId.isNotBlank() && c.ambientWriteKey.isNotBlank()
        val current = uploader
        if (enabled && current != null && current.channelId == c.ambientChannelId && current.writeKey == c.ambientWriteKey &&
            current.slot == c.ambientSlot && current.intervalMin == c.ambientIntervalMin
        ) return
        current?.close()
        uploadStatusJob?.cancel()
        if (!enabled) {
            uploader = null
            ScanStatusHolder.update { it.copy(upload = null) }
            return
        }
        val u = AmbientUploader(lifecycleScope, c.ambientChannelId, c.ambientWriteKey, c.ambientSlot, c.ambientIntervalMin)
        uploader = u
        uploadStatusJob = lifecycleScope.launch {
            u.status.collect { st -> ScanStatusHolder.update { it.copy(upload = st) } }
        }
    }

    /** Supabase の送信設定が変わったら送信器を作り直す（未送信分は端末内のキューに残る） */
    private fun rebuildApiUploader(c: NodeConfig) {
        val enabled = c.apiUrl.isNotBlank() && c.apiKey.isNotBlank() && c.apiToken.isNotBlank() && c.nodeId.isNotBlank()
        val current = apiUploader
        if (enabled && current != null && current.baseUrl == c.apiUrl && current.apiKey == c.apiKey &&
            current.nodeToken == c.apiToken && current.nodeId == c.nodeId && current.sendIntervalSec == c.sendIntervalSec
        ) return
        current?.close()
        apiStatusJob?.cancel()
        if (!enabled) {
            apiUploader = null
            ScanStatusHolder.update { it.copy(apiUpload = null) }
            return
        }
        val u = ApiUploader(
            lifecycleScope, QueueDatabase.get(this).pending(), c.nodeId, c.apiUrl, c.apiKey, c.apiToken, c.sendIntervalSec,
        )
        apiUploader = u
        apiStatusJob = lifecycleScope.launch {
            u.status.collect { st -> ScanStatusHolder.update { it.copy(apiUpload = st) } }
        }
    }

    // ---- スキャン制御（メインスレッド） ----

    /** @param restart 再起動による開始なら true。数ミリ秒の中断なので観測の欠けとはみなさない */
    private fun startScan(restart: Boolean = false) {
        if (!adapter.isEnabled) {
            ScanStatusHolder.update { it.copy(bluetoothOn = false, scanning = false, lastError = "Bluetooth OFF") }
            updateNotification()
            return
        }
        val s = scanner ?: BleScannerWrapper(adapter, hasher, ::onScanEvent, ::onScanFailed).also { scanner = it }
        if (s.isScanning) return
        val ok = s.start()
        lastStartMillis = System.currentTimeMillis()
        if (ok && !restart) aggregator.markIncomplete()
        if (!restart) Log.i(TAG, if (ok) "scan started" else "scan start failed: no BluetoothLeScanner")
        ScanStatusHolder.update {
            it.copy(
                scanning = ok,
                bluetoothOn = true,
                lastError = if (ok) null else "BluetoothLeScanner を取得できません",
                scanStartedAtMillis = if (ok && it.scanStartedAtMillis == 0L) lastStartMillis else it.scanStartedAtMillis,
            )
        }
        updateNotification()
    }

    private fun restartScan(reason: RestartPolicy.Reason) {
        Log.i(TAG, "scan restart: $reason")
        scanner?.stop()
        ScanStatusHolder.update {
            when (reason) {
                RestartPolicy.Reason.PERIODIC -> it.copy(restartCount = it.restartCount + 1)
                RestartPolicy.Reason.NO_RESULTS -> it.copy(noResultRestartCount = it.noResultRestartCount + 1)
            }
        }
        startScan(restart = true)
    }

    private fun onScanEvent(event: ScanEvent) {
        packetCounter++
        lastResultMillis = event.epochMillis
        aggregator.add(event)?.let(::onWindowClosed)
    }

    private fun onScanFailed(errorCode: Int) {
        val name = BleScannerWrapper.errorName(errorCode)
        Log.w(TAG, "onScanFailed: $name")
        ScanStatusHolder.update { it.copy(scanning = false, lastError = "スキャン失敗: $name") }
        updateNotification()
        // 登録失敗やスキャン頻度超過は少し長めに待ってから再試行する
        val delayMillis = if (errorCode == 2 || errorCode == 6) 120_000L else 30_000L
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            delay(delayMillis)
            startScan()
        }
    }

    // ---- 窓の確定 ----

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val scanningNow = scanner?.isScanning == true
                // スキャンが止まっている間は、閉じる窓と新しく始まる窓の両方を部分的とする
                if (!scanningNow) aggregator.markIncomplete()
                aggregator.flush(now)?.let(::onWindowClosed)
                if (!scanningNow) aggregator.markIncomplete()
                val pps = packetCounter
                packetCounter = 0
                ScanStatusHolder.update {
                    it.copy(
                        packetsPerSec = pps,
                        devicesInCurrentWindow = aggregator.currentDeviceCount,
                        currentWindowStartMillis = aggregator.currentWindowStart,
                    )
                }
                RestartPolicy.decide(now, scanner?.isScanning == true, lastStartMillis, lastResultMillis)
                    ?.let(::restartScan)
            }
        }
    }

    /** メインスレッドから呼ばれる。重い処理は publishDispatcher へ回す */
    private fun onWindowClosed(result: WindowResult) {
        lifecycleScope.launch(publishDispatcher) { publish(result) }
    }

    private suspend fun publish(result: WindowResult) {
        val c = config
        val battery = readBattery()
        val summary = WindowSummary.from(
            result = result,
            nodeId = c.nodeId,
            zone = zone,
            battery = battery?.first,
            charging = battery?.second,
            appVersion = BuildConfig.VERSION_NAME,
            includeDevices = c.debugLog,
            roomId = c.roomId,
        ).let { s ->
            // 窓の途中で部屋を切り替えたら、その窓は2部屋の観測が混ざるので送らない
            if (roomChangedAtMillis > result.windowStartMillis) s.copy(partial = true) else s
        }
        val estimator = c.estimator()
        val estimate = estimator.estimate(summary.countsByRssi)
        // 端末内ログには、生の集計値に加えて Ambient に送る元になった推定人数も残す
        val logged = summary.copy(estimatedPeople = estimate)
        // 個人を識別できる値（ハッシュを含む）は logcat に出さない
        Log.i(
            TAG,
            "window ${summary.observedAt} all=${summary.countsByRssi["all"]} " +
                "-80=${summary.countsByRssi["-80"]} adv=${summary.advCount} estimate=$estimate" +
                if (summary.isPartial) " partial" else "",
        )
        var logPath: String? = null
        var error: String? = null
        logger?.let { l ->
            runCatching { l.append(logged, result.windowStartMillis) }
                .onSuccess { logPath = it.absolutePath }
                .onFailure { e ->
                    Log.e(TAG, "ログ書き込み失敗", e)
                    error = "ログ書き込み失敗: ${e.message}"
                }
        }
        ScanStatusHolder.update {
            it.copy(
                lastSummary = summary,
                lastEstimate = estimate,
                lastLogPath = logPath ?: it.lastLogPath,
                lastError = error ?: it.lastError,
            )
        }
        // 部分的な窓も渡す（送る値には使わないが、Ambient の区間を閉じるきっかけになる）
        uploader?.submit(result.windowStartMillis, estimate.takeUnless { summary.isPartial })
        // Supabase へは生の集計値と推定の式を送り、サーバがその式で推定する（画面の推定人数と同じ式）。送信失敗でもスキャンは止めない
        apiUploader?.let { u ->
            runCatching { u.submit(summary, result.windowStartMillis, estimator) }
                .onFailure { e -> Log.e(TAG, "送信キューへの追加に失敗", e) }
        }
        updateNotification()
    }

    /** @return (残量%, 給電中) 。取得できなければ null */
    private fun readBattery(): Pair<Int, Boolean>? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return (level * 100 / scale) to (plugged != 0)
    }

    // ---- ウェイクロック ----

    @SuppressLint("WakelockTimeout") // 常時給電のノードで、Service が動いている間は CPU を止めないため時間制限を付けない
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrowdLevelNode:scan")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ---- 通知 ----

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notif_channel_desc)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val status = ScanStatusHolder.status.value
        val text = when {
            status.lastError != null -> status.lastError
            status.lastSummary != null -> getString(
                R.string.notif_text_counts,
                status.lastSummary.countsByRssi["-80"] ?: 0,
                status.lastSummary.countsByRssi["all"] ?: 0,
            )
            else -> getString(R.string.notif_text_starting)
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle(getString(R.string.notif_title, "${config.nodeId}・${Rooms.labelOf(config.roomId)}"))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification()) }
    }

    companion object {
        private const val TAG = "BleScanService"
        private const val CHANNEL_ID = "scan"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.crowdlevel.node.action.STOP"


        fun logDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BleScanService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BleScanService::class.java).setAction(ACTION_STOP))
        }
    }
}
