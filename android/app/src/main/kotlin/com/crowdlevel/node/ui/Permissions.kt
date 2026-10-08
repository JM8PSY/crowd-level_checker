package com.crowdlevel.node.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** 実行時権限の一覧と判定 */
object Permissions {
    /** スキャンに必須の実行時権限。これが揃えば Service を起動する */
    fun scan(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
        }
        // neverForLocation を付けないため、全バージョンで正確な位置情報の権限が必要
        list += Manifest.permission.ACCESS_FINE_LOCATION
        return list
    }

    /**
     * 画面から要求する権限。
     * Android 12 以上では FINE だけを要求すると無視されるため、COARSE も同じ要求に含める。
     * 通知は無くてもスキャンできるので、ここで要求するが必須にはしない。
     */
    fun requested(): List<String> {
        val list = scan().toMutableList()
        list += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list
    }

    fun scanGranted(context: Context): Boolean = scan().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** 位置情報サービス（端末設定）が ON か。OFF だとスキャン結果が届かない */
    fun locationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun ignoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
