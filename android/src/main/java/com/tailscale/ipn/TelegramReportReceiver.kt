// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TelegramReportReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != TelegramReporter.ACTION_DAILY_REPORT) {
      return
    }
    AdvancedPrefs.init(context)
    if (!AdvancedPrefs.telegramReportEnabled) {
      return
    }

    val appContext = context.applicationContext
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        TelegramReporter.sendReport(appContext)
      } finally {
        TelegramReporter.scheduleNextReport(appContext)
        pendingResult.finish()
      }
    }
  }
}
