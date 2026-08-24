// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tailscale.ipn.AdvancedPrefs
import com.tailscale.ipn.AutoReconnect
import com.tailscale.ipn.IPNService
import com.tailscale.ipn.R
import com.tailscale.ipn.TelegramReporter
import com.tailscale.ipn.ui.util.Lists

@Composable
fun AdvancedSettingsView(
    backToSettings: BackNavigation,
    onNavigateToTelegramReport: () -> Unit,
) {
  val context = LocalContext.current
  var preferCellular by remember { mutableStateOf(AdvancedPrefs.preferCellular) }
  var runInBackground by remember { mutableStateOf(AdvancedPrefs.runInBackground) }
  var autoReconnect by remember { mutableStateOf(AdvancedPrefs.autoReconnect) }
  val telegramEnabled = AdvancedPrefs.telegramReportEnabled

  Scaffold(topBar = { Header(titleRes = R.string.advanced, onBack = backToSettings) }) {
      innerPadding ->
    Column(modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
      Setting.Switch(
          R.string.prefer_cellular,
          subtitle = stringResource(R.string.prefer_cellular_subtitle),
          isOn = preferCellular,
          onToggle = {
            preferCellular = !preferCellular
            AdvancedPrefs.preferCellular = preferCellular
            IPNService.refreshUnderlyingNetworks()
          })

      Lists.ItemDivider()
      Setting.Switch(
          R.string.run_in_background,
          subtitle = stringResource(R.string.run_in_background_subtitle),
          isOn = runInBackground,
          onToggle = {
            runInBackground = !runInBackground
            AdvancedPrefs.runInBackground = runInBackground
          })

      Lists.ItemDivider()
      Setting.Switch(
          R.string.auto_reconnect,
          subtitle = stringResource(R.string.auto_reconnect_subtitle),
          isOn = autoReconnect,
          onToggle = {
            autoReconnect = !autoReconnect
            AdvancedPrefs.autoReconnect = autoReconnect
            AutoReconnect.onSettingChanged()
          })

      Lists.ItemDivider()
      Setting.Text(
          R.string.telegram_report,
          subtitle =
              if (telegramEnabled && AdvancedPrefs.telegramConfigured())
                  stringResource(
                      R.string.telegram_report_on, TelegramReporter.formatReportTime(context))
              else stringResource(R.string.telegram_report_off),
          onClick = onNavigateToTelegramReport)
    }
  }
}
