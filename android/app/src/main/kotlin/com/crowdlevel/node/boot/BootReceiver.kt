package com.crowdlevel.node.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.crowdlevel.node.scan.BleScanService
import com.crowdlevel.node.ui.Permissions

/** 端末再起動時に Service を自動起動する。必要な権限が揃っていなければ何もしない */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Permissions.scanGranted(context)) return
        BleScanService.start(context)
    }
}
