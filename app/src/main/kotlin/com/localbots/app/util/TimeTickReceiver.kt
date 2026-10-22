package com.localbots.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.localbots.app.theme.ThemeManager

class TimeTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_TIME_TICK ||
            intent?.action == Intent.ACTION_TIME_SET ||
            intent?.action == Intent.ACTION_TIMEZONE_CHANGED) {
            ThemeManager.onTimeTick()
        }
    }
}
