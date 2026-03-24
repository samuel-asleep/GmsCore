/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.server

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var qrCodeImage: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvServerAddress: TextView
    private lateinit var tvWarning: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var statusIndicator: View

    private var serverRunning = false

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

        qrCodeImage = findViewById(R.id.qr_code_image)
        tvStatus = findViewById(R.id.tv_status)
        tvServerAddress = findViewById(R.id.tv_server_address)
        tvWarning = findViewById(R.id.tv_warning)
        btnToggle = findViewById(R.id.btn_toggle_server)
        statusIndicator = findViewById(R.id.status_indicator)

        btnToggle.setOnClickListener {
            if (!serverRunning) {
                requestPermissionsAndStart()
            } else {
                stopServer()
            }
        }

        updateButtonState(false)
        updateQrCode(null)
        checkTailscaleInstalled()
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
            tvServerAddress.text = ""
            tvWarning.visibility = View.GONE
            setIndicatorActive(false)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (ignored: IllegalArgumentException) {
        }
    }

    // ---- Tailscale check ---------------------------------------------------

    private fun checkTailscaleInstalled() {
        try {
            packageManager.getPackageInfo("com.tailscale.ipn", 0)
        } catch (e: PackageManager.NameNotFoundException) {
            showTailscaleRequiredDialog()
        }
    }

    private fun showTailscaleRequiredDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_tailscale_title)
            .setMessage(R.string.dialog_tailscale_message)
            .setPositiveButton(R.string.dialog_tailscale_install) { _, _ ->
                val playStoreIntent = try {
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.tailscale.ipn"))
                } catch (e: Exception) {
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.tailscale.ipn"))
                }
                startActivity(playStoreIntent)
            }
            .setNegativeButton(R.string.dialog_tailscale_dismiss, null)
            .show()
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
        tvServerAddress.text = getString(R.string.searching_tailscale_ip)
    }

    private fun stopServer() {
        val intent = Intent(this, DroidGuardServerService::class.java)
            .setAction(DroidGuardServerService.ACTION_STOP)
        startService(intent)
        tvStatus.setText(R.string.status_idle)
        tvServerAddress.text = ""
        tvWarning.visibility = View.GONE
        updateQrCode(null)
        updateButtonState(false)
        setIndicatorActive(false)
    }

    // ---- Status updates ----------------------------------------------------

    private fun updateStatus(status: String, ip: String?, port: Int) {
        when (status) {
            DroidGuardServerService.STATUS_LISTENING -> {
                if (ip != null) {
                    tvStatus.text = getString(R.string.status_listening, ip, port)
                    tvServerAddress.text = getString(R.string.server_address_format, ip, port)
                    updateQrCode(getString(R.string.server_address_format, ip, port))
                    tvWarning.visibility = View.GONE
                } else {
                    tvStatus.setText(R.string.status_listening_no_ip)
                    tvServerAddress.text = getString(R.string.searching_tailscale_ip)
                    updateQrCode(null)
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
                tvServerAddress.text = ""
                tvWarning.visibility = View.GONE
                updateQrCode(null)
                updateButtonState(false)
                setIndicatorActive(false)
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

    private fun updateQrCode(content: String?) {
        if (content.isNullOrBlank()) {
            qrCodeImage.setImageResource(android.R.drawable.ic_dialog_info)
            return
        }
        try {
            val size = 1024
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size) { i ->
                if (bitMatrix[i % size, i / size]) Color.BLACK else Color.WHITE
            }
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            qrCodeImage.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate QR code", e)
        }
    }

    companion object {
        private const val TAG = "DroidGuardServerMain"
        private const val REQ_NOTIFICATION = 1001

        /**
         * Returns the device's Tailscale IP by looking specifically at the
         * 'tailscale0' network interface. Returns null if Tailscale is not
         * running or no IPv4 address is assigned to that interface.
         */
        fun getTailscaleIp(): String? {
            return try {
                val iface = NetworkInterface.getByName("tailscale0") ?: return null
                iface.inetAddresses.toList()
                    .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                    ?.hostAddress
            } catch (e: Exception) {
                Log.w(TAG, "Could not read tailscale0 interface", e)
                null
            }
        }
    }
}
