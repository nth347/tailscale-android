// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object AutoReconnect {
  private const val TAG = "AutoReconnect"

  const val RETRY_INTERVAL_MS = 2000L

  @Volatile var stoppedByUser: Boolean = false
    private set

  private var scope: CoroutineScope? = null
  private var retryJob: Job? = null

  fun start(scope: CoroutineScope) {
    this.scope = scope
    scope.launch { Notifier.state.collect { onStateChanged(it) } }
    scope.launch { Notifier.prefs.collect { onStateChanged(Notifier.state.value) } }
  }

  fun onUserStopped() {
    stoppedByUser = true
    cancelRetries()
  }

  fun onUserStarted() {
    stoppedByUser = false
  }

  fun onSettingChanged() {
    if (AdvancedPrefs.autoReconnect) {
      onStateChanged(Notifier.state.value)
    } else {
      cancelRetries()
    }
  }

  private fun onStateChanged(state: Ipn.State) {
    if (state == Ipn.State.Running) {
      cancelRetries()
      return
    }
    if (!AdvancedPrefs.autoReconnect || stoppedByUser || !wantsToBeRunning()) {
      return
    }
    if (state == Ipn.State.Stopped || state == Ipn.State.NoState) {
      startRetries()
    }
  }

  private fun wantsToBeRunning(): Boolean = Notifier.prefs.value?.WantRunning ?: false

  @Synchronized
  private fun startRetries() {
    if (retryJob?.isActive == true) {
      return
    }
    val scope = this.scope ?: return
    retryJob =
        scope.launch {
          while (isActive) {
            if (!AdvancedPrefs.autoReconnect || stoppedByUser || !wantsToBeRunning()) {
              break
            }
            val state = Notifier.state.value
            if (state == Ipn.State.Running) {
              break
            }
            val app = UninitializedApp.get()
            if ((state == Ipn.State.Stopped || state == Ipn.State.NoState) &&
                app.isAbleToStartVPN()) {
              TSLog.d(TAG, "connection lost in state $state; attempting to reconnect")
              app.startVPN()
            }
            delay(RETRY_INTERVAL_MS)
          }
        }
  }

  @Synchronized
  private fun cancelRetries() {
    retryJob?.cancel()
    retryJob = null
  }
}
