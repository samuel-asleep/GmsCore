/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts [DroidGuardServerService] automatically when the device boots,
 * if the user has enabled "Start Server on Boot" in SharedPreferences.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        // Check if boot start is enabled in SharedPreferences
        val prefs = context.getSharedPreferences("droidguard_server_prefs", Context.MODE_PRIVATE)
        val bootStartEnabled = prefs.getBoolean(MainActivity.PREF_BOOT_START, true)

        if (!bootStartEnabled) {
            Log.i(TAG, "Boot start is disabled — not starting DroidGuardServerService")
            return
        }

        Log.i(TAG, "Boot completed — starting DroidGuardServerService")
        val serviceIntent = Intent(context, DroidGuardServerService::class.java)
            .setAction(DroidGuardServerService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        private const val TAG = "DroidGuardBootReceiver"
    }
}
