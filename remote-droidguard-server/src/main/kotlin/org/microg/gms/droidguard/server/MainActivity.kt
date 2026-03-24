/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.server

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var tvTailscaleIp: TextView
    private lateinit var btnCopyIp: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvWarning: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var btnTailscaleAction: MaterialButton
    private lateinit var statusIndicator: View

    private var serverRunning = false
    private var currentIp: String? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                DroidGuardServerService.ACTION_STATUS_UPDATE -> {
                    val status = intent.getStringExtra(DroidGuardServerService.EXTRA_STATUS) ?: return
                    val ip = intent.getStringExtra(DroidGuardServerService.EXTRA_IP)
                    val port = intent.getIntExtra(DroidGuardServerService.EXTRA_PORT, DroidGuardServerService.DEFAULT_PORT)
                    updateStatus(status, ip, port)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTailscaleIp = findViewById(R.id.tv_tailscale_ip)
        btnCopyIp = findViewById(R.id.btn_copy_ip)
        tvStatus = findViewById(R.id.tv_status)
        tvWarning = findViewById(R.id.tv_warning)
        btnToggle = findViewById(R.id.btn_toggle_server)
        btnTailscaleAction = findViewById(R.id.btn_tailscale_action)
        statusIndicator = findViewById(R.id.status_indicator)

        btnToggle.setOnClickListener {
            if (!serverRunning) {
                requestPermissionsAndStart()
            } else {
                stopServer()
            }
        }

        btnCopyIp.setOnClickListener {
            copyIpToClipboard()
        }

        btnTailscaleAction.setOnClickListener {
            openTailscaleApp()
        }

        updateButtonState(false)
        checkTailscaleAndUpdateUI()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(DroidGuardServerService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        val running = DroidGuardServerService.isRunning
        if (running != serverRunning) {
            updateButtonState(running)
        }
        if (!running) {
            tvStatus.setText(R.string.status_idle)
            tvWarning.visibility = View.GONE
            setIndicatorActive(false)
        }
        checkTailscaleAndUpdateUI()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (ignored: IllegalArgumentException) {
        }
    }

    // ---- Tailscale check & UI update --------------------------------------

    private fun checkTailscaleAndUpdateUI() {
        val isTailscaleInstalled = isTailscaleInstalled()
        val tailscaleIp = getTailscaleIp()

        currentIp = tailscaleIp

        if (!isTailscaleInstalled) {
            // Tailscale not installed
            tvTailscaleIp.text = getString(R.string.tailscale_not_installed)
            btnTailscaleAction.text = getString(R.string.btn_install_tailscale)
            btnTailscaleAction.visibility = View.VISIBLE
            btnCopyIp.isEnabled = false
        } else if (tailscaleIp == null) {
            // Tailscale installed but not connected
            tvTailscaleIp.text = getString(R.string.tailscale_not_active)
            btnTailscaleAction.text = getString(R.string.btn_open_tailscale)
            btnTailscaleAction.visibility = View.VISIBLE
            btnCopyIp.isEnabled = false
        } else {
            // Tailscale connected with IP
            tvTailscaleIp.text = getString(R.string.tailscale_ip_format, tailscaleIp, DroidGuardServerService.DEFAULT_PORT)
            btnTailscaleAction.visibility = View.GONE
            btnCopyIp.isEnabled = true
        }
    }

    private fun isTailscaleInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("com.tailscale.ipn", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openTailscaleApp() {
        if (!isTailscaleInstalled()) {
            // Open Play Store to install
            val playStoreIntent = try {
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.tailscale.ipn"))
            } catch (e: Exception) {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.tailscale.ipn"))
            }
            startActivity(playStoreIntent)
        } else {
            // Open Tailscale app
            val launchIntent = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, R.string.cannot_open_tailscale, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyIpToClipboard() {
        val ip = currentIp
        if (ip != null) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(
                getString(R.string.clipboard_label),
                getString(R.string.server_address_format, ip, DroidGuardServerService.DEFAULT_PORT)
            )
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.ip_copied, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Permissions & server start ----------------------------------------

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot request battery optimization exemption", e)
                }
            }
        }

        startServer()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION) {
            startServer()
        }
    }

    private fun startServer() {
        val intent = Intent(this, DroidGuardServerService::class.java)
            .setAction(DroidGuardServerService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        tvStatus.setText(R.string.status_idle)
    }

    private fun stopServer() {
        val intent = Intent(this, DroidGuardServerService::class.java)
            .setAction(DroidGuardServerService.ACTION_STOP)
        startService(intent)
        tvStatus.setText(R.string.status_idle)
        tvWarning.visibility = View.GONE
        updateButtonState(false)
        setIndicatorActive(false)
    }

    // ---- Status updates ----------------------------------------------------

    private fun updateStatus(status: String, ip: String?, port: Int) {
        when (status) {
            DroidGuardServerService.STATUS_LISTENING -> {
                if (ip != null) {
                    tvStatus.text = getString(R.string.status_listening, ip, port)
                    currentIp = ip
                    tvTailscaleIp.text = getString(R.string.tailscale_ip_format, ip, port)
                    btnCopyIp.isEnabled = true
                    tvWarning.visibility = View.GONE
                } else {
                    tvStatus.setText(R.string.status_listening_no_ip)
                    currentIp = null
                    tvTailscaleIp.text = getString(R.string.tailscale_not_active)
                    btnCopyIp.isEnabled = false
                    tvWarning.text = getString(R.string.warning_no_tailscale_ip)
                    tvWarning.visibility = View.VISIBLE
                }
                updateButtonState(true)
                setIndicatorActive(true)
            }
            DroidGuardServerService.STATUS_PROCESSING -> {
                tvStatus.setText(R.string.status_processing)
            }
            DroidGuardServerService.STATUS_STOPPED -> {
                tvStatus.setText(R.string.status_idle)
                tvWarning.visibility = View.GONE
                updateButtonState(false)
                setIndicatorActive(false)
                checkTailscaleAndUpdateUI()
            }
        }
    }

    // ---- UI helpers --------------------------------------------------------

    private fun updateButtonState(running: Boolean) {
        serverRunning = running
        if (running) {
            btnToggle.text = getString(R.string.btn_stop_server)
            btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
        } else {
            btnToggle.text = getString(R.string.btn_start_server)
            btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#388E3C"))
        }
    }

    private fun setIndicatorActive(active: Boolean) {
        val color = if (active) Color.parseColor("#4CAF50") else Color.parseColor("#9E9E9E")
        ViewCompat.setBackgroundTintList(statusIndicator, ColorStateList.valueOf(color))
        if (active) {
            val anim = AnimationUtils.loadAnimation(this, R.anim.pulse)
            statusIndicator.startAnimation(anim)
        } else {
            statusIndicator.clearAnimation()
            statusIndicator.alpha = 1f
        }
    }

    companion object {
        private const val TAG = "DroidGuardServerMain"
        private const val REQ_NOTIFICATION = 1001

        /**
         * Returns the device's Tailscale IP by enumerating all network interfaces
         * and checking for addresses in the Tailscale CGNAT range (100.64.0.0/10).
         * Returns null if Tailscale is not running or no IPv4 address is found.
         */
        fun getTailscaleIp(): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (!iface.isUp) continue

                    val addresses = iface.inetAddresses.toList()
                    val tailscaleIp = addresses
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { addr ->
                            !addr.isLoopbackAddress && isTailscaleIp(addr)
                        }

                    if (tailscaleIp != null) {
                        Log.i(TAG, "Found Tailscale IP: ${tailscaleIp.hostAddress} on interface ${iface.name}")
                        return tailscaleIp.hostAddress
                    }
                }
                Log.w(TAG, "No Tailscale IP found in any network interface")
                null
            } catch (e: Exception) {
                Log.w(TAG, "Could not enumerate network interfaces", e)
                null
            }
        }

        /**
         * Checks if an IPv4 address is in the Tailscale CGNAT range (100.64.0.0/10).
         * This range is 100.64.0.0 - 100.127.255.255.
         */
        private fun isTailscaleIp(addr: Inet4Address): Boolean {
            val bytes = addr.address
            val firstOctet = bytes[0].toInt() and 0xFF
            val secondOctet = bytes[1].toInt() and 0xFF

            return firstOctet == 100 && secondOctet in 64..127
        }
    }
}
