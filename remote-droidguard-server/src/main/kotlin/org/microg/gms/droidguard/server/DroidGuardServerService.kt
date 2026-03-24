/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.droidguard.DroidGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.android.gms.tasks.await
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that exposes a local HTTP server for multi-step DroidGuard evaluation.
 *
 * Supported endpoints:
 *   POST /?flow=…&source=…   (single-shot, compatible with RemoteHandleImpl)
 *   GET  /status             returns "OK"
 */
class DroidGuardServerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var currentIp: String? = null

    // Active multi-step sessions: sessionId -> open DroidGuardHandle
    private val sessions = ConcurrentHashMap<String, com.google.android.gms.droidguard.DroidGuardHandle>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Server lifecycle --------------------------------------------------

    private fun startServer() {
        if (serverJob?.isActive == true) return

        val ip = MainActivity.getTailscaleIp() ?: run {
            Log.w(TAG, "No Tailscale IP found; binding to all interfaces")
            null
        }
        currentIp = ip

        startForeground(NOTIFICATION_ID, buildNotification(STATUS_LISTENING, ip, DEFAULT_PORT))
        isRunning = true

        serverJob = scope.launch {
            try {
                val ss = ServerSocket(DEFAULT_PORT)
                serverSocket = ss
                broadcastStatus(STATUS_LISTENING, ip, DEFAULT_PORT)
                Log.i(TAG, "Listening on ${ip ?: "0.0.0.0"}:$DEFAULT_PORT")

                while (!ss.isClosed) {
                    val client: Socket = try {
                        ss.accept()
                    } catch (e: SocketException) {
                        break
                    }
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            } finally {
                broadcastStatus(STATUS_STOPPED, null, DEFAULT_PORT)
                isRunning = false
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {
        }
        serverSocket = null
        closeAllSessions()
        isRunning = false
        broadcastStatus(STATUS_STOPPED, null, DEFAULT_PORT)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    private fun closeAllSessions() {
        sessions.forEach { (_, handle) ->
            try { handle.close() } catch (ignored: Exception) { }
        }
        sessions.clear()
    }

    // ---- HTTP request handling --------------------------------------------

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val writer = PrintWriter(s.getOutputStream(), true)

                // Parse request line: METHOD PATH HTTP/x.x
                val requestLine = reader.readLine() ?: return
                Log.d(TAG, "Request: $requestLine")
                val parts = requestLine.trim().split(" ")
                if (parts.size < 2) {
                    sendResponse(writer, 400, "Bad Request", "text/plain", "Bad request line")
                    return
                }
                val method = parts[0].uppercase()
                val rawPath = parts[1]

                // Read headers (consume until blank line)
                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
                while (line != null && line.isNotEmpty()) {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                    line = reader.readLine()
                }

                // Read body if present
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val bodyBuilder = StringBuilder()
                if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val read = reader.read(buf, contentLength - remaining, remaining)
                        if (read < 0) break
                        remaining -= read
                    }
                    bodyBuilder.append(buf)
                }
                val body = bodyBuilder.toString()

                broadcastStatus(STATUS_PROCESSING, currentIp, DEFAULT_PORT)
                updateNotification(STATUS_PROCESSING, currentIp, DEFAULT_PORT)

                try {
                    dispatch(method, rawPath, body, writer)
                } finally {
                    broadcastStatus(STATUS_LISTENING, currentIp, DEFAULT_PORT)
                    updateNotification(STATUS_LISTENING, currentIp, DEFAULT_PORT)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client", e)
            }
        }
    }

    private suspend fun dispatch(method: String, rawPath: String, body: String, writer: PrintWriter) {
        val qIdx = rawPath.indexOf('?')
        val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
        val queryString = if (qIdx >= 0) rawPath.substring(qIdx + 1) else ""
        val queryParams = parseParams(queryString)

        when {
            method == "GET" && path == "/status" -> {
                sendResponse(writer, 200, "OK", "text/plain", "OK")
            }

            // Single-shot guard (compatible with RemoteHandleImpl)
            method == "POST" && (path == "/" || path == "/guard") -> {
                val flow = queryParams["flow"] ?: run {
                    sendResponse(writer, 400, "Bad Request", "text/plain", "Missing 'flow' parameter")
                    return
                }
                val source = queryParams["source"] ?: packageName
                val dataMap = parseParams(body)

                try {
                    val result = DroidGuard.getClient(this, source)
                        .getResults(flow, dataMap, null)
                        .await()
                    sendResponse(writer, 200, "OK", "text/plain", result)
                } catch (e: Exception) {
                    Log.e(TAG, "DroidGuard guard failed", e)
                    sendResponse(writer, 500, "Internal Server Error", "text/plain", "DroidGuard error: ${e.message}")
                }
            }

            // Multi-step: init session
            method == "POST" && path == "/v2/init" -> {
                val flow = queryParams["flow"] ?: run {
                    sendResponse(writer, 400, "Bad Request", "text/plain", "Missing 'flow' parameter")
                    return
                }
                val source = queryParams["source"] ?: packageName
                try {
                    val handle = DroidGuard.getClient(this, source).init(flow, null).await()
                    val sessionId = java.util.UUID.randomUUID().toString()
                    sessions[sessionId] = handle
                    sendResponse(writer, 200, "OK", "text/plain", sessionId)
                } catch (e: Exception) {
                    Log.e(TAG, "DroidGuard init failed", e)
                    sendResponse(writer, 500, "Internal Server Error", "text/plain", "DroidGuard init error: ${e.message}")
                }
            }

            // Multi-step: snapshot
            method == "POST" && path == "/v2/snapshot" -> {
                val sessionId = queryParams["sessionId"] ?: run {
                    sendResponse(writer, 400, "Bad Request", "text/plain", "Missing 'sessionId'")
                    return
                }
                val handle = sessions[sessionId] ?: run {
                    sendResponse(writer, 404, "Not Found", "text/plain", "Session not found")
                    return
                }
                val dataMap = parseParams(body)
                try {
                    val result = handle.snapshot(dataMap)
                    sendResponse(writer, 200, "OK", "text/plain", result ?: "")
                } catch (e: Exception) {
                    Log.e(TAG, "DroidGuard snapshot failed", e)
                    sendResponse(writer, 500, "Internal Server Error", "text/plain", "Snapshot error: ${e.message}")
                }
            }

            // Multi-step: close session
            method == "POST" && path == "/v2/close" -> {
                val sessionId = queryParams["sessionId"] ?: run {
                    sendResponse(writer, 400, "Bad Request", "text/plain", "Missing 'sessionId'")
                    return
                }
                sessions.remove(sessionId)?.close()
                sendResponse(writer, 200, "OK", "text/plain", "closed")
            }

            else -> {
                sendResponse(writer, 404, "Not Found", "text/plain", "Not found: $path")
            }
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private fun parseParams(encoded: String): Map<String, String> {
        if (encoded.isBlank()) return emptyMap()
        return encoded.split("&").mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) null
            else Uri.decode(pair.substring(0, eq)) to Uri.decode(pair.substring(eq + 1))
        }.toMap()
    }

    private fun sendResponse(writer: PrintWriter, code: Int, reason: String, contentType: String, body: String) {
        val bytes = body.encodeToByteArray()
        writer.print("HTTP/1.1 $code $reason\r\n")
        writer.print("Content-Type: $contentType; charset=UTF-8\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    // ---- Notifications & broadcasts ----------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String, ip: String?, port: Int): android.app.Notification {
        val activityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when (status) {
            STATUS_LISTENING -> getString(R.string.notification_text_listening, ip ?: "0.0.0.0", port)
            STATUS_PROCESSING -> getString(R.string.notification_text_processing)
            else -> getString(R.string.notification_text_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(activityIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String, ip: String?, port: Int) {
        @Suppress("DEPRECATION")
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status, ip, port))
    }

    private fun broadcastStatus(status: String, ip: String?, port: Int) {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            ip?.let { putExtra(EXTRA_IP, it) }
            putExtra(EXTRA_PORT, port)
            setPackage(packageName)
        })
    }

    companion object {
        private const val TAG = "DroidGuardServerSvc"
        private const val CHANNEL_ID = "droidguard_server"
        private const val NOTIFICATION_ID = 7070

        const val DEFAULT_PORT = 7070

        const val ACTION_START = "org.microg.gms.droidguard.server.START"
        const val ACTION_STOP = "org.microg.gms.droidguard.server.STOP"
        const val ACTION_STATUS_UPDATE = "org.microg.gms.droidguard.server.STATUS_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"

        const val STATUS_LISTENING = "listening"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_STOPPED = "stopped"

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
