// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object TelegramReporter {
  private const val TAG = "TelegramReporter"
  private const val ALARM_REQUEST_CODE = 4242
  private const val TIMEOUT_MS = 20000
  private const val CELLULAR_TIMEOUT_MS = 20000
  private const val UNKNOWN_SSID = "<unknown ssid>"

  const val ACTION_DAILY_REPORT = "com.tailscale.ipn.TELEGRAM_DAILY_REPORT"

  fun scheduleNextReport(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val at = nextReportTime()
    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, reportIntent(context))
    TSLog.d(TAG, "scheduled next report for ${format(at)}")
  }

  fun cancelReports(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.cancel(reportIntent(context))
    TSLog.d(TAG, "cancelled scheduled reports")
  }

  fun sendReport(context: Context): Result<Unit> {
    if (!AdvancedPrefs.telegramConfigured()) {
      return Result.failure(IllegalStateException("Telegram bot token or chat ID is not set"))
    }
    return post(
        context, AdvancedPrefs.telegramBotToken, AdvancedPrefs.telegramChatId, buildReport(context))
  }

  fun buildReport(context: Context): String {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val wifi = hasTransport(connectivityManager, NetworkCapabilities.TRANSPORT_WIFI)
    val cellular = hasTransport(connectivityManager, NetworkCapabilities.TRANSPORT_CELLULAR)

    return buildString {
      appendLine(context.getString(R.string.telegram_report_heading))
      appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
      appendLine("Time: ${format(System.currentTimeMillis())}")
      appendLine("Battery: ${batteryStatus(context)}")
      appendLine("Wi-Fi: ${wifiStatus(context, wifi)}")
      appendLine("Mobile data: ${mobileDataStatus(context, cellular, wifi)}")
      append("Tailscale VPN: ${if (vpnUp(connectivityManager)) "up" else "down"}")
    }
  }

  fun formatTime(context: Context, hour: Int, minute: Int): String {
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return DateTimeFormatter.ofPattern(pattern).format(LocalTime.of(hour, minute))
  }

  fun formatReportTime(context: Context): String =
      formatTime(context, AdvancedPrefs.reportHour, AdvancedPrefs.reportMinute)

  private fun mobileDataStatus(
      context: Context,
      cellularConnected: Boolean,
      wifiConnected: Boolean
  ): String {
    if (cellularConnected) {
      return "connected"
    }
    if (simAbsent(context)) {
      return "no SIM"
    }
    return when (mobileDataEnabled(context)) {
      true -> if (wifiConnected) "on, idle (Wi-Fi in use)" else "on, no connection"
      false -> "off"
      else -> "disconnected"
    }
  }

  private fun simAbsent(context: Context): Boolean {
    val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
    return telephonyManager.simState == TelephonyManager.SIM_STATE_ABSENT
  }

  private fun mobileDataEnabled(context: Context): Boolean? {
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    if (telephonyManager != null) {
      try {
        return telephonyManager.isDataEnabled
      } catch (e: Exception) {
        TSLog.d(TAG, "isDataEnabled unavailable: $e")
      }
    }
    return try {
      when (Settings.Global.getInt(context.contentResolver, "mobile_data", -1)) {
        1 -> true
        0 -> false
        else -> null
      }
    } catch (e: Exception) {
      null
    }
  }

  fun locationServicesEnabled(context: Context): Boolean {
    val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      locationManager.isLocationEnabled
    } else {
      locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
          locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }
  }

  private fun wifiStatus(context: Context, connected: Boolean): String {
    if (!connected) {
      return "nothing connected"
    }
    val name = wifiNetworkName(context) ?: return "connected"
    return "connected ($name)"
  }

  @Suppress("DEPRECATION")
  fun wifiNetworkName(context: Context): String? {
    if (!hasLocationPermission(context) || !locationServicesEnabled(context)) {
      return null
    }
    val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
    val ssid = wifiManager.connectionInfo?.ssid?.trim('"').orEmpty()
    if (ssid.isEmpty() || ssid == UNKNOWN_SSID) {
      return null
    }
    return ssid
  }

  fun hasLocationPermission(context: Context): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED

  fun hasBackgroundLocationPermission(context: Context): Boolean =
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) hasLocationPermission(context)
      else
          hasLocationPermission(context) &&
              ContextCompat.checkSelfPermission(
                  context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                  PackageManager.PERMISSION_GRANTED

  private fun nextReportTime(): Long {
    val zone = ZoneId.systemDefault()
    val reportTime = LocalTime.of(AdvancedPrefs.reportHour, AdvancedPrefs.reportMinute)
    var next = LocalDateTime.of(LocalDate.now(zone), reportTime)
    if (!next.isAfter(LocalDateTime.now(zone))) {
      next = next.plusDays(1)
    }
    return next.atZone(zone).toInstant().toEpochMilli()
  }

  private fun reportIntent(context: Context): PendingIntent {
    val intent =
        Intent(context, TelegramReportReceiver::class.java).apply { action = ACTION_DAILY_REPORT }
    return PendingIntent.getBroadcast(
        context.applicationContext,
        ALARM_REQUEST_CODE,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun batteryStatus(context: Context): String {
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val levelText = if (level in 0..100) "$level%" else "unknown"
    return "$levelText (${chargingStatus(batteryManager)})"
  }

  private fun chargingStatus(batteryManager: BatteryManager): String =
      when (batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "plugged in, not charging"
        else -> if (batteryManager.isCharging) "charging" else "unknown"
      }

  @Suppress("DEPRECATION")
  private fun hasTransport(connectivityManager: ConnectivityManager, transport: Int): Boolean {
    for (network in connectivityManager.allNetworks) {
      val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
      if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
        continue
      }
      if (caps.hasTransport(transport) &&
          caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        return true
      }
    }
    return false
  }

  @Suppress("DEPRECATION")
  private fun vpnUp(connectivityManager: ConnectivityManager): Boolean {
    if (Notifier.state.value == Ipn.State.Running) {
      return true
    }
    for (network in connectivityManager.allNetworks) {
      val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
        return true
      }
    }
    return false
  }

  private fun post(context: Context, botToken: String, chatId: String, text: String): Result<Unit> {
    val url = URL("https://api.telegram.org/bot$botToken/sendMessage")
    val body =
        "chat_id=${URLEncoder.encode(chatId, "UTF-8")}&text=${URLEncoder.encode(text, "UTF-8")}"
    if (!AdvancedPrefs.telegramPreferCellular) {
      return post(null, url, body)
    }
    val lease = requestCellularNetwork(context)
    if (lease == null) {
      TSLog.d(TAG, "prefer cellular is on but no cellular network is available; using default")
      return post(null, url, body)
    }
    return try {
      TSLog.d(TAG, "sending report over cellular network ${lease.network}")
      post(lease.network, url, body)
    } finally {
      lease.release()
    }
  }

  private fun requestCellularNetwork(context: Context): NetworkLease? {
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
    val request =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    val available = AtomicReference<Network?>(null)
    val settled = CountDownLatch(1)
    val callback =
        object : ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) {
            available.compareAndSet(null, network)
            settled.countDown()
          }

          override fun onUnavailable() {
            settled.countDown()
          }
        }
    return try {
      connectivityManager.requestNetwork(request, callback, CELLULAR_TIMEOUT_MS)
      settled.await(CELLULAR_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
      val network = available.get()
      if (network == null) {
        unregister(connectivityManager, callback)
        null
      } else {
        NetworkLease(network, connectivityManager, callback)
      }
    } catch (e: Exception) {
      TSLog.e(TAG, "failed to request a cellular network: $e")
      unregister(connectivityManager, callback)
      null
    }
  }

  private fun unregister(
      connectivityManager: ConnectivityManager,
      callback: ConnectivityManager.NetworkCallback
  ) {
    try {
      connectivityManager.unregisterNetworkCallback(callback)
    } catch (e: Exception) {
      TSLog.d(TAG, "failed to unregister network callback: $e")
    }
  }

  private class NetworkLease(
      val network: Network,
      private val connectivityManager: ConnectivityManager,
      private val callback: ConnectivityManager.NetworkCallback
  ) {
    fun release() = TelegramReporter.unregister(connectivityManager, callback)
  }

  private fun post(network: Network?, url: URL, body: String): Result<Unit> {
    return try {
      val connection =
          ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
          }
      try {
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code in 200..299) {
          TSLog.d(TAG, "report delivered")
          Result.success(Unit)
        } else {
          val error =
              connection.errorStream
                  ?.bufferedReader()
                  ?.use(BufferedReader::readText)
                  .orEmpty()
                  .take(200)
          TSLog.e(TAG, "Telegram API returned $code: $error")
          Result.failure(RuntimeException("Telegram API error $code"))
        }
      } finally {
        connection.disconnect()
      }
    } catch (e: Exception) {
      TSLog.e(TAG, "failed to send report: $e")
      Result.failure(e)
    }
  }

  private fun format(epochMillis: Long): String =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
          .format(
              Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime())
}
