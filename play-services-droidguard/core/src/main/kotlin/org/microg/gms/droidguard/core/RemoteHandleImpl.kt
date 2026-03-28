/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.android.gms.droidguard.internal.DroidGuardInitReply
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.droidguard.internal.IDroidGuardHandle
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "RemoteGuardImpl"

class RemoteHandleImpl(private val context: Context, private val packageName: String) : IDroidGuardHandle.Stub() {
    private var flow: String? = null
    private var request: DroidGuardResultsRequest? = null
    private var sessionId: String? = null
    private val url: String
        get() = DroidGuardPreferences.getNetworkServerUrl(context) ?: throw IllegalStateException("Network URL required")

    override fun init(flow: String?) {
        Log.d(TAG, "init($flow)")
        this.flow = flow
        // Try to initialize multi-step session
        try {
            sessionId = callInitEndpoint(flow, null)
            Log.d(TAG, "Multi-step session initialized: $sessionId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize multi-step session, will fallback to single-shot", e)
            sessionId = null
        }
    }

    override fun snapshot(map: Map<Any?, Any?>?): ByteArray {
        Log.d(TAG, "snapshot($map)")

        // Multi-step mode: use /v2/snapshot with sessionId
        val currentSessionId = sessionId
        if (currentSessionId != null) {
            return snapshotMultiStep(currentSessionId, map)
        }

        // Single-shot mode: use / endpoint (backwards compatibility)
        return snapshotSingleShot(map)
    }

    private fun snapshotMultiStep(sessionId: String, map: Map<Any?, Any?>?): ByteArray {
        Log.d(TAG, "Using multi-step snapshot with sessionId: $sessionId")
        val endpoint = "$url/v2/snapshot?sessionId=${Uri.encode(sessionId)}"
        val payload = map.orEmpty().map { Uri.encode(it.key as String) + "=" + Uri.encode(it.value as String) }.joinToString("&")

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        Log.d(TAG, "POST $endpoint: $payload")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.requestMethod = "POST"
        connection.doInput = true
        connection.doOutput = true
        connection.outputStream.use { it.write(payload.encodeToByteArray()) }
        val bytes = connection.inputStream.use { it.readBytes() }.decodeToString()
        return Base64.decode(bytes, Base64.URL_SAFE + Base64.NO_WRAP + Base64.NO_PADDING)
    }

    private fun snapshotSingleShot(map: Map<Any?, Any?>?): ByteArray {
        Log.d(TAG, "Using single-shot snapshot (backwards compatibility)")
        val paramsMap = mutableMapOf("flow" to flow, "source" to packageName)
        for (key in request?.bundle?.keySet().orEmpty()) {
            request?.bundle?.getString(key)?.let { paramsMap["x-request-$key"] = it }
        }
        val params = paramsMap.map { Uri.encode(it.key) + "=" + Uri.encode(it.value) }.joinToString("&")
        val connection = URL("$url?$params").openConnection() as HttpURLConnection
        val payload = map.orEmpty().map { Uri.encode(it.key as String) + "=" + Uri.encode(it.value as String) }.joinToString("&")
        Log.d(TAG, "POST ${connection.url}: $payload")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        connection.requestMethod = "POST"
        connection.doInput = true
        connection.doOutput = true
        connection.outputStream.use { it.write(payload.encodeToByteArray()) }
        val bytes = connection.inputStream.use { it.readBytes() }.decodeToString()
        return Base64.decode(bytes, Base64.URL_SAFE + Base64.NO_WRAP + Base64.NO_PADDING)
    }

    override fun close() {
        Log.d(TAG, "close()")

        // Close multi-step session on server if one exists
        val currentSessionId = sessionId
        if (currentSessionId != null) {
            try {
                closeSession(currentSessionId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close session on server", e)
            }
        }

        // Clean up local state
        this.sessionId = null
        this.request = null
        this.flow = null
    }

    private fun closeSession(sessionId: String) {
        Log.d(TAG, "Closing session on server: $sessionId")
        val endpoint = "$url/v2/close?sessionId=${Uri.encode(sessionId)}"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doInput = true
        connection.doOutput = true
        connection.outputStream.use { it.write(ByteArray(0)) }
        val responseCode = connection.responseCode
        Log.d(TAG, "Close session response: $responseCode")
        connection.disconnect()
    }

    override fun initWithRequest(flow: String?, request: DroidGuardResultsRequest?): DroidGuardInitReply? {
        Log.d(TAG, "initWithRequest($flow, $request)")
        this.flow = flow
        this.request = request
        // Try to initialize multi-step session
        try {
            sessionId = callInitEndpoint(flow, request)
            Log.d(TAG, "Multi-step session initialized: $sessionId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize multi-step session, will fallback to single-shot", e)
            sessionId = null
        }
        return null
    }

    private fun callInitEndpoint(flow: String?, request: DroidGuardResultsRequest?): String? {
        val paramsMap = mutableMapOf("flow" to flow, "source" to packageName)
        for (key in request?.bundle?.keySet().orEmpty()) {
            request?.bundle?.getString(key)?.let { paramsMap["x-request-$key"] = it }
        }
        val params = paramsMap.map { Uri.encode(it.key) + "=" + Uri.encode(it.value) }.joinToString("&")
        val endpoint = "$url/v2/init?$params"

        Log.d(TAG, "POST $endpoint")
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doInput = true
        connection.doOutput = true
        connection.outputStream.use { it.write(ByteArray(0)) }

        val responseCode = connection.responseCode
        if (responseCode != 200) {
            throw RuntimeException("Init endpoint returned $responseCode")
        }

        val sessionId = connection.inputStream.use { it.readBytes() }.decodeToString().trim()
        if (sessionId.isEmpty()) {
            throw RuntimeException("Init endpoint returned empty sessionId")
        }

        return sessionId
    }
}