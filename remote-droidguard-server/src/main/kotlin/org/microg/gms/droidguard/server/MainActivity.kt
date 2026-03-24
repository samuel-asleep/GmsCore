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
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var qrCodeImage: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvServerAddress: TextView
    private lateinit var btnToggle: ToggleButton

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
        btnToggle = findViewById(R.id.btn_toggle_server)

        btnToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestPermissionsAndStart()
            } else {
                stopServer()
            }
        }

        updateQrCode(null)
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
        // Sync toggle state with service running state
        val running = DroidGuardServerService.isRunning
        if (btnToggle.isChecked != running) {
            btnToggle.setOnCheckedChangeListener(null)
            btnToggle.isChecked = running
            btnToggle.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) requestPermissionsAndStart() else stopServer()
            }
        }
        if (!running) {
            tvStatus.setText(R.string.status_idle)
            tvServerAddress.text = ""
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (ignored: IllegalArgumentException) {
        }
    }

    private fun requestPermissionsAndStart() {
        // Request POST_NOTIFICATIONS on API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
                return
            }
        }

        // Request ignore battery optimizations (API 23+)
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
        tvServerAddress.text = ""
        updateQrCode(null)
    }

    private fun updateStatus(status: String, ip: String?, port: Int) {
        when (status) {
            DroidGuardServerService.STATUS_LISTENING -> {
                tvStatus.text = getString(R.string.status_listening, ip, port)
                tvServerAddress.text = getString(R.string.server_address_format, ip ?: "0.0.0.0", port)
                updateQrCode(ip?.let { getString(R.string.server_address_format, it, port) })
                if (!btnToggle.isChecked) {
                    btnToggle.setOnCheckedChangeListener(null)
                    btnToggle.isChecked = true
                    btnToggle.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) requestPermissionsAndStart() else stopServer()
                    }
                }
            }
            DroidGuardServerService.STATUS_PROCESSING -> {
                tvStatus.setText(R.string.status_processing)
            }
            DroidGuardServerService.STATUS_STOPPED -> {
                tvStatus.setText(R.string.status_idle)
                tvServerAddress.text = ""
                updateQrCode(null)
                if (btnToggle.isChecked) {
                    btnToggle.setOnCheckedChangeListener(null)
                    btnToggle.isChecked = false
                    btnToggle.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) requestPermissionsAndStart() else stopServer()
                    }
                }
            }
        }
    }

    private fun updateQrCode(content: String?) {
        if (content.isNullOrBlank()) {
            qrCodeImage.setImageResource(android.R.drawable.ic_dialog_info)
            return
        }
        try {
            val size = 500
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            qrCodeImage.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate QR code", e)
        }
    }

    companion object {
        private const val TAG = "DroidGuardServerMain"
        private const val REQ_NOTIFICATION = 1001

        /** Returns the device's first Tailscale IP (100.x.x.x in the CGNAT range). */
        fun getTailscaleIp(): String? {
            return try {
                NetworkInterface.getNetworkInterfaces()?.toList()
                    ?.flatMap { it.inetAddresses.toList() }
                    ?.firstOrNull { addr ->
                        !addr.isLoopbackAddress && addr is java.net.Inet4Address
                            && isTailscaleAddress(addr)
                    }?.hostAddress
            } catch (e: Exception) {
                null
            }
        }

        private fun isTailscaleAddress(addr: InetAddress): Boolean {
            val bytes = addr.address
            // Tailscale uses 100.64.0.0/10 (CGNAT range shared with Tailscale)
            // First octet = 100, second octet 64..127
            return bytes[0] == 100.toByte() && (bytes[1].toInt() and 0xFF) in 64..127
        }
    }
}
