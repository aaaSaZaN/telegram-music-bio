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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class MediaNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeControllers = ConcurrentHashMap<MediaSession.Token, MediaController>()
    private val registeredCallbacks = ConcurrentHashMap<MediaSession.Token, MediaController.Callback>()

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

        private val _currentStatusFlow = MutableStateFlow(lastTrackInfo)
        val currentStatusFlow: StateFlow<String> = _currentStatusFlow.asStateFlow()

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
        cleanupCallbacks()
        Log.i(TAG, "Notification listener disconnected")
    }

    private fun cleanupCallbacks() {
        for ((token, controller) in activeControllers) {
            val cb = registeredCallbacks.remove(token)
            if (cb != null) {
                try {
                    controller.unregisterCallback(cb)
                } catch (_: Exception) {}
            }
        }
        activeControllers.clear()
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

        // Строгая проверка: уведомление ОБЯЗАНО быть медиа-уведомлением
        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val isMediaStyle = template.contains("MediaStyle")
        val isTransport = notification.category == Notification.CATEGORY_TRANSPORT

        if (token == null && !isMediaStyle && !isTransport) {
            // Игнорируем любые не-медиа уведомления (скриншоты, чаты, системные сообщения)
            return
        }

        if (token != null) {
            hookMediaController(token)
            val controller = activeControllers[token] ?: try {
                MediaController(this, token).also { activeControllers[token] = it }
            } catch (e: Exception) {
                Log.w(TAG, "Could not create MediaController", e)
                null
            }
            if (controller != null) {
                processMediaController(controller, notification)
                return
            }
        }

        // Редкий fallback: плеер использует MediaStyle, но не привязал токен
        if (isMediaStyle || isTransport) {
            processNotificationFallback(notification)
        }
    }

    private fun hookMediaController(token: MediaSession.Token) {
        if (registeredCallbacks.containsKey(token)) return
        try {
            val controller = MediaController(this, token)
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    Log.d(TAG, "MediaController onPlaybackStateChanged: ${state?.state}")
                    processMediaController(controller, null)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    Log.d(TAG, "MediaController onMetadataChanged")
                    processMediaController(controller, null)
                }

                override fun onSessionDestroyed() {
                    Log.d(TAG, "MediaController onSessionDestroyed")
                    val cb = registeredCallbacks.remove(token)
                    if (cb != null) {
                        try { controller.unregisterCallback(cb) } catch (_: Exception) {}
                    }
                    activeControllers.remove(token)
                }
            }
            controller.registerCallback(callback)
            registeredCallbacks[token] = callback
            activeControllers[token] = controller
            Log.i(TAG, "Hooked MediaController callback for ${controller.packageName}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register controller callback", e)
        }
    }

    private fun processMediaController(controller: MediaController, notification: Notification?) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, true)) return

        val metadata = controller.metadata
        val playbackState = controller.playbackState

        var artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        var title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: ""

        // Если метаданные в контроллере еще не прогрузились, пробуем из уведомления
        if (title.isBlank() && notification != null) {
            val extras = notification.extras
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            if (artist.isBlank()) {
                artist = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            }
        }

        if (title.isBlank()) return

        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val durationSec = if (durationMs > 0) (durationMs / 1000).toInt() else null

        val state = playbackState?.state
        val isPlaying: Boolean = when (state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> true
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_ERROR -> false
            else -> {
                // Если статус не определен контроллером, проверяем наличие кнопки паузы в уведомлении
                notification?.actions?.any {
                    val actTitle = it.title?.toString()?.lowercase() ?: ""
                    actTitle.contains("pause") || actTitle.contains("пауз")
                } ?: false
            }
        }

        dispatchTrackUpdate(artist, title, durationSec, isPlaying)
    }

    private fun processNotificationFallback(notification: Notification) {
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        if (title.isBlank()) return

        val isPlaying = notification.actions?.any {
            val actTitle = it.title?.toString()?.lowercase() ?: ""
            actTitle.contains("pause") || actTitle.contains("пауз")
        } ?: false

        dispatchTrackUpdate(artist, title, null, isPlaying)
    }

    private fun dispatchTrackUpdate(artist: String, title: String, durationSec: Int?, isPlaying: Boolean) {
        val trackKey = "$artist|$title|$isPlaying"
        if (trackKey == lastSentKey) {
            return
        }

        lastSentKey = trackKey
        val displayInfo = if (isPlaying) "🎧 $artist — $title" else "⏸ Пауза: $artist — $title"
        lastTrackInfo = displayInfo
        _currentStatusFlow.value = displayInfo

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_STATUS, displayInfo).apply()

        Log.i(TAG, "Media state changed: $displayInfo (duration: ${durationSec}s)")
        sendWebhook(artist, title, durationSec, isPlaying)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
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

        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val isMediaStyle = template.contains("MediaStyle")
        val isTransport = notification.category == Notification.CATEGORY_TRANSPORT

        if (token == null && !isMediaStyle && !isTransport) {
            // Удаление не-медиа уведомления нас не касается
            return
        }

        if (token != null) {
            val cb = registeredCallbacks.remove(token)
            val controller = activeControllers.remove(token)
            if (cb != null && controller != null) {
                try { controller.unregisterCallback(cb) } catch (_: Exception) {}
            }
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        if (title.isNotBlank() && lastSentKey?.contains(title) == true) {
            lastSentKey = null
            lastTrackInfo = "Музыка выключена"
            _currentStatusFlow.value = "Музыка выключена"
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
