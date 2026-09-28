package com.example.tgmusicsync

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.tgmusicsync.theme.TgMusicSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TgMusicSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SyncAppScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncAppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(MediaNotificationListener.PREFS_NAME, Context.MODE_PRIVATE) }

    var serverUrl by remember { mutableStateOf(prefs.getString(MediaNotificationListener.KEY_SERVER_URL, "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString(MediaNotificationListener.KEY_API_KEY, "") ?: "") }
    var syncEnabled by remember { mutableStateOf(prefs.getBoolean(MediaNotificationListener.KEY_ENABLED, true)) }
    var showPassword by remember { mutableStateOf(false) }

    var isNotificationAccessGranted by remember { mutableStateOf(checkNotificationAccess(context)) }
    var isBatteryOptIgnored by remember { mutableStateOf(checkBatteryOptimization(context)) }

    var testStatusMessage by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }

    var lastTrack by remember {
        mutableStateOf(prefs.getString(MediaNotificationListener.KEY_LAST_STATUS, MediaNotificationListener.lastTrackInfo) ?: "Ожидание музыки...")
    }

    LaunchedEffect(Unit) {
        MediaNotificationListener.currentStatusFlow.collect { status ->
            lastTrack = status
        }
    }

    // Обновление состояния при возвращении в приложение
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isNotificationAccessGranted = checkNotificationAccess(context)
                isBatteryOptIgnored = checkBatteryOptimization(context)
                lastTrack = prefs.getString(MediaNotificationListener.KEY_LAST_STATUS, MediaNotificationListener.lastTrackInfo) ?: "Ожидание музыки..."
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🎧", fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("TG Music Sync", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Статус разрешений
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Системные разрешения", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

                    // Доступ к уведомлениям
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Доступ к уведомлениям медиа", fontWeight = FontWeight.Medium)
                            Text(
                                if (isNotificationAccessGranted) "Разрешено" else "Требуется для чтения трека",
                                fontSize = 12.sp,
                                color = if (isNotificationAccessGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        if (!isNotificationAccessGranted) {
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Включить")
                            }
                        } else {
                            Text("✅", fontSize = 20.sp)
                        }
                    }

                    HorizontalDivider()

                    // Оптимизация батареи (Samsung)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Работа в фоне (Samsung)", fontWeight = FontWeight.Medium)
                            Text(
                                if (isBatteryOptIgnored) "Оптимизация отключена" else "Рекомендуется отключить",
                                fontSize = 12.sp,
                                color = if (isBatteryOptIgnored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                        }
                        if (!isBatteryOptIgnored) {
                            OutlinedButton(
                                onClick = { requestIgnoreBattery(context) }
                            ) {
                                Text("Настроить")
                            }
                        } else {
                            Text("✅", fontSize = 20.sp)
                        }
                    }
                }
            }

            // 2. Настройки сервера
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Подключение к серверу", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Адрес сервера") },
                        placeholder = { Text("http://ip_сервера:8088") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Text("🌐", modifier = Modifier.padding(start = 8.dp)) }
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("Секретный ключ (WEBHOOK_SECRET)") },
                        placeholder = { Text("Токен из .env файла") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        leadingIcon = { Text("🔑", modifier = Modifier.padding(start = 8.dp)) },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Text(if (showPassword) "👁" else "🙈")
                            }
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Авто-синхронизация", fontWeight = FontWeight.Medium)
                        Switch(
                            checked = syncEnabled,
                            onCheckedChange = {
                                syncEnabled = it
                                prefs.edit().putBoolean(MediaNotificationListener.KEY_ENABLED, it).apply()
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (serverUrl.isBlank()) {
                                    Toast.makeText(context, "Укажите адрес сервера", Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }
                                isTestingConnection = true
                                testStatusMessage = null
                                scope.launch {
                                    val result = testServerConnection(serverUrl, apiKey)
                                    isTestingConnection = false
                                    testStatusMessage = result
                                }
                            },
                            enabled = !isTestingConnection
                        ) {
                            if (isTestingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Проверить")
                            }
                        }

                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                prefs.edit()
                                    .putString(MediaNotificationListener.KEY_SERVER_URL, serverUrl.trim())
                                    .putString(MediaNotificationListener.KEY_API_KEY, apiKey.trim())
                                    .putBoolean(MediaNotificationListener.KEY_ENABLED, syncEnabled)
                                    .apply()
                                Toast.makeText(context, "Настройки сохранены!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Сохранить")
                        }
                    }

                    testStatusMessage?.let { msg ->
                        Text(
                            text = msg,
                            fontSize = 13.sp,
                            color = if (msg.startsWith("✅")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // 3. Текущее состояние
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Текущий статус на телефоне", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        text = lastTrack,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun checkNotificationAccess(context: Context): Boolean {
    return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}

fun checkBatteryOptimization(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@SuppressLint("BatteryLife")
fun requestIgnoreBattery(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        context.startActivity(fallback)
    }
}

suspend fun testServerConnection(serverUrl: String, apiKey: String): String = withContext(Dispatchers.IO) {
    try {
        val cleanUrl = serverUrl.trim().trimEnd('/')
        val healthUrl = "$cleanUrl/health"
        val url = URL(healthUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("X-Api-Key", apiKey.trim())
        }

        val code = conn.responseCode
        if (code in 200..299) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val currentBio = json.optString("current_bio", "")
            "✅ Сервер доступен! (Ответ: ${code} OK, био: $currentBio)"
        } else {
            "❌ Ошибка сервера: HTTP $code"
        }
    } catch (e: Exception) {
        "❌ Не удалось подключиться: ${e.localizedMessage ?: e.message}"
    }
}
