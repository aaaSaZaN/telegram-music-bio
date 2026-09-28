package com.example.tgmusicsync

import android.app.Notification
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MediaNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "MediaNotifyListener"
        const val PREFS_NAME = "tg_music_sync_prefs"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_ENABLED = "sync_enabled"
        const val KEY_LAST_STATUS = "last_status"

        @Volatile
        var isServiceConnected: Boolean = false
            private set

        @Volatile
        var lastTrackInfo: String = "Ожидание музыки..."
            private set

        private var lastSentKey: String? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
        Log.i(TAG, "Notification listener connected successfully")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isServiceConnected = false
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, true)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val token: MediaSession.Token? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
        }

        var artist = ""
        var title = ""
        var durationSec: Int? = null
        var isPlaying = false
        var isMediaNotification = false

        if (token != null) {
            try {
                val controller = MediaController(this, token)
                val metadata = controller.metadata
                val playbackState = controller.playbackState

                isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING

                if (metadata != null) {
                    artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                    title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                    val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                    if (durationMs > 0) {
                        durationSec = (durationMs / 1000).toInt()
                    }
                }
                isMediaNotification = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read media controller", e)
            }
        }

        // Fallback: считываем из текста уведомления
        if (title.isBlank()) {
            val extraTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val extraText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

            val category = notification.category
            if (category == Notification.CATEGORY_TRANSPORT || notification.actions?.isNotEmpty() == true) {
                if (extraTitle.isNotBlank()) {
                    title = extraTitle
                    artist = extraText
                    isMediaNotification = true
                    isPlaying = notification.actions?.any {
                        val actTitle = it.title?.toString()?.lowercase() ?: ""
                        actTitle.contains("pause") || actTitle.contains("пауза")
                    } ?: true
                }
            }
        }

        if (!isMediaNotification || title.isBlank()) return

        val trackKey = "$artist|$title|$isPlaying"
        if (trackKey == lastSentKey) {
            // Дедупликация: ровно то же состояние трека, сеть не нагружаем
            return
        }

        lastSentKey = trackKey
        val displayInfo = if (isPlaying) "🎧 $artist — $title" else "⏸ Пауза: $artist — $title"
        lastTrackInfo = displayInfo
        prefs.edit().putString(KEY_LAST_STATUS, displayInfo).apply()

        Log.i(TAG, "Media state changed: $displayInfo")
        sendWebhook(artist, title, durationSec, isPlaying)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn ?: return

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, true)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""

        if (title.isNotBlank() && lastSentKey?.contains(title) == true) {
            lastSentKey = null
            lastTrackInfo = "Музыка выключена"
            prefs.edit().putString(KEY_LAST_STATUS, "Музыка выключена").apply()
            Log.i(TAG, "Media notification removed, sending stop")
            sendWebhook("", "", null, false)
        }
    }

    private fun sendWebhook(artist: String, track: String, duration: Int?, isPlaying: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(KEY_SERVER_URL, "")?.trim() ?: ""
        val apiKey = prefs.getString(KEY_API_KEY, "")?.trim() ?: ""

        if (serverUrl.isBlank()) {
            Log.w(TAG, "Server URL is empty in settings")
            return
        }

        val endpoint = if (serverUrl.endsWith("/api/now-playing")) {
            serverUrl
        } else {
            "${serverUrl.trimEnd('/')}/api/now-playing"
        }

        serviceScope.launch {
            try {
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true

                if (apiKey.isNotBlank()) {
                    conn.setRequestProperty("X-Api-Key", apiKey)
                }

                val json = JSONObject().apply {
                    put("artist", artist)
                    put("track", track)
                    put("playing", isPlaying)
                    if (duration != null && duration > 0) {
                        put("duration", duration)
                    }
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(json.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                Log.d(TAG, "Webhook response: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending webhook: ${e.message}")
            }
        }
    }
}
