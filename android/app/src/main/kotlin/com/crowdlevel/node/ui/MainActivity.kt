package com.crowdlevel.node.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.crowdlevel.node.scan.BleScanService
import com.crowdlevel.node.scan.ScanStatusHolder
import com.crowdlevel.node.settings.NodeConfig
import com.crowdlevel.node.settings.NodeSettings
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 常駐画面。固定設置したノードでは常にこの画面を前面に出しておく。
 * 設定の keepScreenOn が ON のとき、FLAG_KEEP_SCREEN_ON と最低輝度で画面を点灯し続ける。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 以降は画面端まで描画されるため、全バージョンで揃えて端まで描画し、
        // 内容はシステムバーの内側に置く。背景は常に暗いのでバーのアイコンは白にする
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val settings = NodeSettings(this)
        setContent {
            val config by settings.config.collectAsStateWithLifecycle(initialValue = NodeConfig.DEFAULT)
            val status by ScanStatusHolder.status.collectAsStateWithLifecycle()
            var showSettings by remember { mutableStateOf(false) }
            var permissionsGranted by remember { mutableStateOf(Permissions.scanGranted(this)) }
            var locationOn by remember { mutableStateOf(Permissions.locationEnabled(this)) }
            var batteryExempt by remember { mutableStateOf(Permissions.ignoringBatteryOptimizations(this)) }
            val scope = rememberCoroutineScope()

            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                permissionsGranted = Permissions.scanGranted(this)
            }

            LaunchedEffect(config.keepScreenOn) { applyScreenPolicy(config.keepScreenOn) }
            LaunchedEffect(permissionsGranted) {
                if (permissionsGranted && !status.serviceRunning) BleScanService.start(this@MainActivity)
            }

            NodeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (showSettings) {
                            SettingsScreen(
                                config = config,
                                onSave = { c ->
                                    scope.launch { settings.update(c) }
                                    showSettings = false
                                },
                                onBack = { showSettings = false },
                            )
                        } else {
                            DashboardScreen(
                                status = status,
                                config = config,
                                permissionsGranted = permissionsGranted,
                                locationEnabled = locationOn,
                                batteryExempt = batteryExempt,
                                onRequestPermissions = { permissionLauncher.launch(Permissions.requested().toTypedArray()) },
                                onOpenLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                                onOpenBatterySettings = { requestBatteryExemption() },
                                // ACTION_REQUEST_ENABLE は BLUETOOTH_CONNECT が必要なため、Bluetooth の設定画面を開く
                                onEnableBluetooth = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                                onStartService = { BleScanService.start(this@MainActivity) },
                                onStopService = { BleScanService.stop(this@MainActivity) },
                                onOpenSettings = { showSettings = true },
                                onSelectRoom = { roomId -> scope.launch { settings.setRoom(roomId) } },
                                onRefreshChecks = {
                                    locationOn = Permissions.locationEnabled(this@MainActivity)
                                    batteryExempt = Permissions.ignoringBatteryOptimizations(this@MainActivity)
                                    permissionsGranted = Permissions.scanGranted(this@MainActivity)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun applyScreenPolicy(keepOn: Boolean) {
        val lp = window.attributes
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lp.screenBrightness = MIN_BRIGHTNESS
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
    }

    @Suppress("BatteryLife") // 固定設置ノード用アプリのため、バッテリー最適化の除外を直接依頼する
    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    companion object {
        /** 0 は「システム既定」扱いになるため、わずかに正の値にする */
        private const val MIN_BRIGHTNESS = 0.01f
    }
}

@Composable
fun NodeTheme(content: @Composable () -> Unit) {
    // 常時点灯運用のため、焼き付きと消費電力を抑えるダークテーマ固定
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
