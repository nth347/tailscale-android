// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.AdvancedPrefs
import com.tailscale.ipn.R
import com.tailscale.ipn.TelegramReporter
import com.tailscale.ipn.ui.theme.listItem
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tailscale.ipn.ui.util.Lists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TelegramReportView(backToAdvanced: BackNavigation) {
  val context = LocalContext.current
  val appContext = context.applicationContext
  val coroutineScope = rememberCoroutineScope()

  var enabled by remember { mutableStateOf(AdvancedPrefs.telegramReportEnabled) }
  var botToken by remember { mutableStateOf(AdvancedPrefs.telegramBotToken) }
  var chatId by remember { mutableStateOf(AdvancedPrefs.telegramChatId) }
  var reportHour by remember { mutableStateOf(AdvancedPrefs.reportHour) }
  var reportMinute by remember { mutableStateOf(AdvancedPrefs.reportMinute) }
  var status by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  var locationGranted by
      remember { mutableStateOf(TelegramReporter.hasLocationPermission(context)) }
  var backgroundLocationGranted by
      remember { mutableStateOf(TelegramReporter.hasBackgroundLocationPermission(context)) }
  var locationServicesOn by
      remember { mutableStateOf(TelegramReporter.locationServicesEnabled(context)) }

  val locationLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationGranted = granted
        backgroundLocationGranted = TelegramReporter.hasBackgroundLocationPermission(context)
        locationServicesOn = TelegramReporter.locationServicesEnabled(context)
      }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        locationGranted = TelegramReporter.hasLocationPermission(context)
        backgroundLocationGranted = TelegramReporter.hasBackgroundLocationPermission(context)
        locationServicesOn = TelegramReporter.locationServicesEnabled(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  fun openAppSettings() {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)))
  }

  val savedMessage = stringResource(R.string.telegram_saved)
  val notConfiguredMessage = stringResource(R.string.telegram_not_configured)
  val testSentMessage = stringResource(R.string.telegram_test_sent)

  fun persist(): Boolean {
    AdvancedPrefs.telegramBotToken = botToken
    AdvancedPrefs.telegramChatId = chatId
    AdvancedPrefs.reportHour = reportHour
    AdvancedPrefs.reportMinute = reportMinute
    val configured = AdvancedPrefs.telegramConfigured()
    val active = enabled && configured
    AdvancedPrefs.telegramReportEnabled = active
    enabled = active
    if (active) {
      TelegramReporter.scheduleNextReport(appContext)
    } else {
      TelegramReporter.cancelReports(appContext)
    }
    return configured
  }

  Scaffold(topBar = { Header(titleRes = R.string.telegram_report, onBack = backToAdvanced) }) {
      innerPadding ->
    Column(modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
      Lists.MultilineDescription {
        Text(
            stringResource(R.string.telegram_report_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      Lists.ItemDivider()
      Setting.Switch(
          R.string.telegram_report_enabled,
          subtitle = stringResource(R.string.telegram_report_enabled_subtitle),
          isOn = enabled,
          onToggle = {
            enabled = !enabled
            status =
                if (enabled && (botToken.isBlank() || chatId.isBlank())) notConfiguredMessage
                else null
          })

      Lists.ItemDivider()
      Setting.Text(
          R.string.telegram_wifi_name,
          subtitle =
              stringResource(
                  when {
                    !locationGranted -> R.string.telegram_wifi_name_off
                    !locationServicesOn -> R.string.telegram_wifi_name_location_off
                    !backgroundLocationGranted -> R.string.telegram_wifi_name_foreground
                    else -> R.string.telegram_wifi_name_on
                  }),
          onClick = {
            if (!locationGranted) {
              locationLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            } else if (!locationServicesOn) {
              context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } else if (!backgroundLocationGranted &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              openAppSettings()
            }
          })

      Lists.ItemDivider()
      Setting.Text(
          R.string.telegram_report_time,
          subtitle = TelegramReporter.formatTime(context, reportHour, reportMinute),
          onClick = {
            TimePickerDialog(
                    context,
                    { _, hour, minute ->
                      reportHour = hour
                      reportMinute = minute
                      persist()
                    },
                    reportHour,
                    reportMinute,
                    DateFormat.is24HourFormat(context))
                .show()
          })

      Lists.ItemDivider()
      TelegramTextField(
          titleRes = R.string.telegram_bot_token,
          placeholderRes = R.string.telegram_bot_token_placeholder,
          value = botToken,
          onValueChange = {
            botToken = it
            status = null
          })

      Lists.ItemDivider()
      TelegramTextField(
          titleRes = R.string.telegram_chat_id,
          placeholderRes = R.string.telegram_chat_id_placeholder,
          value = chatId,
          onValueChange = {
            chatId = it
            status = null
          })

      Lists.ItemDivider()
      ListItem(
          colors = MaterialTheme.colorScheme.listItem,
          headlineContent = {
            Row(modifier = Modifier.fillMaxWidth()) {
              Button(
                  enabled = !busy,
                  onClick = { status = if (persist()) savedMessage else notConfiguredMessage },
                  content = { Text(stringResource(R.string.telegram_save)) })
              Box(modifier = Modifier.padding(start = 8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                      persist()
                      if (!AdvancedPrefs.telegramConfigured()) {
                        status = notConfiguredMessage
                        return@OutlinedButton
                      }
                      busy = true
                      coroutineScope.launch {
                        val result =
                            withContext(Dispatchers.IO) { TelegramReporter.sendReport(appContext) }
                        busy = false
                        status =
                            result.fold(
                                onSuccess = { testSentMessage },
                                onFailure = { it.message ?: it.toString() })
                      }
                    },
                    content = { Text(stringResource(R.string.telegram_send_test)) })
              }
            }
          })

      status?.let {
        Lists.MultilineDescription {
          Text(
              it,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

@Composable
private fun TelegramTextField(
    titleRes: Int,
    placeholderRes: Int,
    value: String,
    onValueChange: (String) -> Unit
) {
  ListItem(
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = { Text(stringResource(titleRes)) },
      supportingContent = {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent),
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            value = value,
            onValueChange = onValueChange,
            placeholder = {
              Text(stringResource(placeholderRes), style = MaterialTheme.typography.bodySmall)
            },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done))
      })
}
