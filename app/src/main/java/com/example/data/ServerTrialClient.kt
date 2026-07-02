package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class ServerTrialClient(private val context: Context) {
    companion object {
        private const val TAG = "ServerTrialClient"
        private const val BASE_URL = "https://us-central1-manhwareader-zkpqwx.cloudfunctions.net"
    }

    suspend fun checkOrStartTrialOnServer(
        deviceFingerprint: String,
        featureId: String
    ): ServerResponse = withContext(Dispatchers.IO) {
        val urlString = "$BASE_URL/checkOrStartTrial"
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; utf-8")
            connection.setRequestProperty("Accept", "application/json")

            val jsonInput = JSONObject().apply {
                put("device_fingerprint", deviceFingerprint)
                put("feature_id", featureId)
                put("timestamp", System.currentTimeMillis())
            }

            connection.outputStream.use { os ->
                OutputStreamWriter(os, "UTF-8").use { writer ->
                    writer.write(jsonInput.toString())
                    writer.flush()
                }
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseString)
                val status = jsonResponse.optString("status", "error")
                val expiresAt = jsonResponse.optLong("expires_at", 0L)
                val purchased = jsonResponse.optBoolean("purchased", false)
                ServerResponse.Success(status, expiresAt, purchased)
            } else {
                Log.w(TAG, "Server returned error response: $responseCode")
                ServerResponse.Error("HTTP $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error contacting server: ${e.message}")
            ServerResponse.Error(e.message ?: "Unknown Connection Error")
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun recordPurchaseOnServer(
        deviceFingerprint: String,
        featureId: String,
        pricePaid: Double
    ): Boolean = withContext(Dispatchers.IO) {
        val urlString = "$BASE_URL/recordPurchase"
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; utf-8")
            connection.setRequestProperty("Accept", "application/json")

            val jsonInput = JSONObject().apply {
                put("device_fingerprint", deviceFingerprint)
                put("feature_id", featureId)
                put("price_paid", pricePaid)
                put("timestamp", System.currentTimeMillis())
            }

            connection.outputStream.use { os ->
                OutputStreamWriter(os, "UTF-8").use { writer ->
                    writer.write(jsonInput.toString())
                    writer.flush()
                }
            }

            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "Error recording purchase on server: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun validateLicenseKeyOnServer(
        deviceFingerprint: String,
        licenseKey: String
    ): ServerResponse = withContext(Dispatchers.IO) {
        val urlString = "$BASE_URL/validateLicenseKey"
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; utf-8")
            connection.setRequestProperty("Accept", "application/json")

            val jsonInput = JSONObject().apply {
                put("device_fingerprint", deviceFingerprint)
                put("license_key", licenseKey)
                put("timestamp", System.currentTimeMillis())
            }

            connection.outputStream.use { os ->
                OutputStreamWriter(os, "UTF-8").use { writer ->
                    writer.write(jsonInput.toString())
                    writer.flush()
                }
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseString)
                val status = jsonResponse.optString("status", "error")
                val isValid = jsonResponse.optBoolean("valid", false)
                if (isValid) {
                    ServerResponse.Success(status, 0L, true)
                } else {
                    ServerResponse.Error("Invalid license key according to backend verification.")
                }
            } else {
                Log.w(TAG, "Server verification returned error response: $responseCode")
                ServerResponse.Error("HTTP $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error contacting license server: ${e.message}")
            ServerResponse.Error(e.message ?: "Unknown Server Error")
        } finally {
            connection?.disconnect()
        }
    }
}

sealed class ServerResponse {
    data class Success(val status: String, val expiresAt: Long, val purchased: Boolean) : ServerResponse()
    data class Error(val message: String) : ServerResponse()
}
