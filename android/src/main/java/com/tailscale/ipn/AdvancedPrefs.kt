// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences

object AdvancedPrefs {
  private const val PREFS_NAME = "tailscale_prefs"

  private const val KEY_PREFER_CELLULAR = "prefer_cellular"
  private const val KEY_RUN_IN_BACKGROUND = "run_in_background"
  private const val KEY_AUTO_RECONNECT = "auto_reconnect"
  private const val KEY_VPN_WAS_RUNNING = "vpn_was_running"
  private const val KEY_TELEGRAM_ENABLED = "telegram_report_enabled"
  private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
  private const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
  private const val KEY_TELEGRAM_PREFER_CELLULAR = "telegram_prefer_cellular"
  private const val KEY_REPORT_HOUR = "telegram_report_hour"
  private const val KEY_REPORT_MINUTE = "telegram_report_minute"

  private const val DEFAULT_REPORT_HOUR = 9
  private const val DEFAULT_REPORT_MINUTE = 0

  @Volatile private var prefs: SharedPreferences? = null

  fun init(context: Context) {
    if (prefs == null) {
      synchronized(this) {
        if (prefs == null) {
          prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        }
      }
    }
  }

  var preferCellular: Boolean
    get() = getBoolean(KEY_PREFER_CELLULAR, false)
    set(value) = putBoolean(KEY_PREFER_CELLULAR, value)

  var runInBackground: Boolean
    get() = getBoolean(KEY_RUN_IN_BACKGROUND, true)
    set(value) = putBoolean(KEY_RUN_IN_BACKGROUND, value)

  var vpnWasRunning: Boolean
    get() = getBoolean(KEY_VPN_WAS_RUNNING, false)
    set(value) = putBoolean(KEY_VPN_WAS_RUNNING, value)

  var autoReconnect: Boolean
    get() = getBoolean(KEY_AUTO_RECONNECT, false)
    set(value) = putBoolean(KEY_AUTO_RECONNECT, value)

  var telegramReportEnabled: Boolean
    get() = getBoolean(KEY_TELEGRAM_ENABLED, false)
    set(value) = putBoolean(KEY_TELEGRAM_ENABLED, value)

  var telegramBotToken: String
    get() = getString(KEY_TELEGRAM_BOT_TOKEN)
    set(value) = putString(KEY_TELEGRAM_BOT_TOKEN, value)

  var telegramChatId: String
    get() = getString(KEY_TELEGRAM_CHAT_ID)
    set(value) = putString(KEY_TELEGRAM_CHAT_ID, value)

  var telegramPreferCellular: Boolean
    get() = getBoolean(KEY_TELEGRAM_PREFER_CELLULAR, false)
    set(value) = putBoolean(KEY_TELEGRAM_PREFER_CELLULAR, value)

  var reportHour: Int
    get() = getInt(KEY_REPORT_HOUR, DEFAULT_REPORT_HOUR).coerceIn(0, 23)
    set(value) = putInt(KEY_REPORT_HOUR, value.coerceIn(0, 23))

  var reportMinute: Int
    get() = getInt(KEY_REPORT_MINUTE, DEFAULT_REPORT_MINUTE).coerceIn(0, 59)
    set(value) = putInt(KEY_REPORT_MINUTE, value.coerceIn(0, 59))

  fun telegramConfigured(): Boolean = telegramBotToken.isNotEmpty() && telegramChatId.isNotEmpty()

  private fun getBoolean(key: String, default: Boolean): Boolean =
      prefs?.getBoolean(key, default) ?: default

  private fun putBoolean(key: String, value: Boolean) {
    prefs?.edit()?.putBoolean(key, value)?.apply()
  }

  private fun getInt(key: String, default: Int): Int = prefs?.getInt(key, default) ?: default

  private fun putInt(key: String, value: Int) {
    prefs?.edit()?.putInt(key, value)?.apply()
  }

  private fun getString(key: String): String = prefs?.getString(key, "")?.trim() ?: ""

  private fun putString(key: String, value: String) {
    prefs?.edit()?.putString(key, value.trim())?.apply()
  }
}
