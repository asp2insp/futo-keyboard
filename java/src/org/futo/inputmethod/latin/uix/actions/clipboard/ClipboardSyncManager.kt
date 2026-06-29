package org.futo.inputmethod.latin.uix.actions.clipboard

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.futo.inputmethod.latin.uix.getSettingBlocking
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class ClipboardFormat(
    val mime_type: String,
    val data: String? = null,
    val data_base64: String? = null
)

@Serializable
data class PushEntryRequest(
    val formats: List<ClipboardFormat>,
    val source_device: String? = null,
    val source_platform: String = "android"
)

@Serializable
data class PushEntryResponse(
    val id: Int
)

@Serializable
data class RemoteEntry(
    val id: Int,
    val created_at: String,
    val source_device: String? = null,
    val source_platform: String? = null,
    val text_preview: String? = null,
    val formats: List<ClipboardFormat>
)

@Serializable
data class RecentEntriesResponse(
    val entries: List<RemoteEntry>
)

class ClipboardSyncManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun getAuthToken(): String = context.getSettingBlocking(ClipboardRemoteSyncToken).trim()
    private fun getServerUrl(): String = context.getSettingBlocking(ClipboardRemoteSyncServerUrl).trim().trimEnd('/')
    private fun getDeviceName(): String = context.getSettingBlocking(ClipboardRemoteSyncDeviceName).let {
        if (it.isBlank()) Build.MODEL else it
    }

    suspend fun pushEntry(entry: ClipboardEntry): Boolean = withContext(Dispatchers.IO) {
        if (!context.getSettingBlocking(ClipboardRemoteSyncEnabled)) return@withContext false
        val token = getAuthToken()
        if (token.isBlank()) return@withContext false

        val formats = mutableListOf<ClipboardFormat>()
        if (entry.text != null) {
            formats.add(ClipboardFormat("text/plain", data = entry.text))
        }
        
        if (entry.backingFile != null) {
            val file = entry.getFile(context)
            if (file != null && file.exists()) {
                try {
                    val bytes = file.readBytes()
                    val mimeType = entry.mimeTypes.firstOrNull() ?: "image/png"
                    formats.add(ClipboardFormat(mimeType, data_base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)))
                } catch (e: Exception) {
                    Log.e("ClipboardSync", "Failed to read image file for sync", e)
                }
            }
        }
        
        if (formats.isEmpty()) return@withContext false

        val request = PushEntryRequest(
            formats = formats,
            source_device = getDeviceName(),
            source_platform = "android"
        )

        try {
            tryWithRetry {
                val url = URL("${getServerUrl()}/api/entry")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.doOutput = true

                connection.outputStream.use { 
                    it.write(json.encodeToString(request).toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    true
                } else if (responseCode == 401) {
                    Log.e("ClipboardSync", "Auth failed (401)")
                    false
                } else {
                    Log.e("ClipboardSync", "Failed to push entry: $responseCode")
                    throw Exception("HTTP $responseCode")
                }
            }
        } catch (e: Exception) {
            Log.e("ClipboardSync", "Error pushing entry", e)
            false
        }
    }

    suspend fun pullRecent(limit: Int = 10): List<RemoteEntry> = withContext(Dispatchers.IO) {
        if (!context.getSettingBlocking(ClipboardRemoteSyncEnabled)) return@withContext emptyList()
        val token = getAuthToken()
        if (token.isBlank()) return@withContext emptyList()

        try {
            val url = URL("${getServerUrl()}/api/recent?limit=$limit")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                json.decodeFromString<RecentEntriesResponse>(responseBody).entries
            } else {
                Log.e("ClipboardSync", "Failed to pull entries: $responseCode")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("ClipboardSync", "Error pulling entries", e)
            emptyList()
        }
    }

    private suspend fun <T> tryWithRetry(maxAttempts: Int = 3, block: suspend () -> T): T {
        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts) {
                    delay(1000L * (1 shl (attempt - 1))) // 1s, 2s, 4s as per spec
                }
            }
        }
        throw lastException!!
    }
}
