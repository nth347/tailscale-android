// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tailscale.ipn.util.TSLog

class BootCompletedReceiver : BroadcastReceiver() {
  private val TAG = "BootCompletedReceiver"

  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != Intent.ACTION_BOOT_COMPLETED) {
      return
    }
    AdvancedPrefs.init(context)

    if (AdvancedPrefs.telegramReportEnabled && AdvancedPrefs.telegramConfigured()) {
      TelegramReporter.scheduleNextReport(context.applicationContext)
    }

    if (!AdvancedPrefs.runInBackground || !AdvancedPrefs.vpnWasRunning) {
      return
    }
    val app = UninitializedApp.get()
    if (app.isAbleToStartVPN()) {
      TSLog.d(TAG, "starting VPN after boot")
      app.startVPN()
    }
  }
}
