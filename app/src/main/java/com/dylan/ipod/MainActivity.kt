package com.dylan.ipod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dylan.ipod.ui.theme.IpodTheme
import com.google.gson.Gson
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2

// --- Models for persistence ---
data class RadioStation(val name: String, val url: String)
data class LibraryConfig(
    val musicPath: String = "Music",
    val audiobooksPath: String = "Audiobooks",
    val radioStations: List<RadioStation> = listOf(
        RadioStation("Lofi Girl", "https://stream.live.vc.bbc.co.uk/bbc_radio_one"),
        RadioStation("KEXP", "https://kexp-mp3-128.streamguys1.com/kexp128.mp3")
    ),
    val audiobookPositions: Map<String, Int> = mapOf()
)

class MainActivity : ComponentActivity() {
    private lateinit var ipodState: IpodState
    private var server: ApplicationEngine? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level != -1 && scale != -1) {
                ipodState.batteryLevel = (level * 100 / scale.toFloat()).toInt()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ipodState = IpodState(onSaveConfig = { saveConfig() })
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        loadConfig()
        checkPermissions()
        startWebServer()
        startIpodService()
        
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        enableEdgeToEdge()
        setContent {
            IpodTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1A1A1A)) {
                    IpodApp(ipodState, onSaveConfig = { saveConfig() })
                }
            }
        }
    }

    private fun startIpodService() {
        val intent = Intent(this, IpodService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:${packageName}")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }

    private fun loadConfig() {
        val file = File(getExternalFilesDir(null), "config.json")
        if (file.exists()) {
            try {
                val config = Gson().fromJson(file.readText(), LibraryConfig::class.java)
                ipodState.config = config
            } catch (e: Exception) {
                Log.e("IPOD", "Failed to load config", e)
            }
        }
    }

    private fun saveConfig() {
        val file = File(getExternalFilesDir(null), "config.json")
        file.writeText(Gson().toJson(ipodState.config))
    }

    private fun startWebServer() {
        server = embeddedServer(CIO, port = 8080, host = "0.0.0.0") {
            install(ContentNegotiation) { gson { } }
            install(CORS) { anyHost() }
            routing {
                get("/") {
                    val ip = getIpAddress() ?: "localhost"
                    val stationsRows = ipodState.config.radioStations.mapIndexed { index, station ->
                        """
                        <div class="station-card" data-id="$index">
                            <div class="drag-handle">
                                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="9" cy="5" r="1"/><circle cx="9" cy="12" r="1"/><circle cx="9" cy="19" r="1"/><circle cx="15" cy="5" r="1"/><circle cx="15" cy="12" r="1"/><circle cx="15" cy="19" r="1"/></svg>
                            </div>
                            <div class="station-info">
                                <div class="station-name">${station.name}</div>
                                <div class="station-url">${station.url}</div>
                            </div>
                            <div class="station-actions">
                                <form action="/remove-station" method="POST" style="display:inline;">
                                    <input type="hidden" name="index" value="$index">
                                    <button type="submit" class="btn-remove">
                                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>
                                    </button>
                                </form>
                            </div>
                        </div>
                        """.trimIndent()
                    }.joinToString("")

                    call.respondText(
                        """
                        <!DOCTYPE html>
                        <html lang="en">
                        <head>
                            <meta charset="UTF-8">
                            <meta name="viewport" content="width=device-width, initial-scale=1.0">
                            <title>iPod Connect</title>
                            <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;600;800&display=swap" rel="stylesheet">
                            <script src="https://cdn.jsdelivr.net/npm/sortablejs@1.15.0/Sortable.min.js"></script>
                            <style>
                                :root {
                                    --bg: #05070a;
                                    --surface: #0f121a;
                                    --surface-bright: #1a1f2e;
                                    --accent: #3b82f6;
                                    --accent-glow: rgba(59, 130, 246, 0.2);
                                    --text: #ffffff;
                                    --text-dim: #94a3b8;
                                    --danger: #ef4444;
                                    --border: #262f45;
                                }
                                * { box-sizing: border-box; }
                                body {
                                    font-family: 'Plus Jakarta Sans', sans-serif;
                                    background-color: var(--bg);
                                    color: var(--text);
                                    margin: 0;
                                    padding: 40px 20px;
                                    line-height: 1.5;
                                }
                                .container { max-width: 700px; margin: 0 auto; }
                                header { margin-bottom: 40px; text-align: center; }
                                h1 {
                                    font-size: 2.5rem;
                                    font-weight: 800;
                                    margin: 0 0 8px 0;
                                    letter-spacing: -0.02em;
                                    background: linear-gradient(135deg, #fff 0%, #94a3b8 100%);
                                    -webkit-background-clip: text;
                                    -webkit-text-fill-color: transparent;
                                }
                                .status-bar {
                                    display: inline-flex;
                                    align-items: center;
                                    gap: 12px;
                                    background: var(--surface);
                                    padding: 6px 16px;
                                    border-radius: 99px;
                                    border: 1px solid var(--border);
                                    font-size: 0.85rem;
                                    color: var(--text-dim);
                                }
                                .status-dot {
                                    width: 8px;
                                    height: 8px;
                                    background: #22c55e;
                                    border-radius: 50%;
                                    box-shadow: 0 0 10px #22c55e;
                                }
                                .card {
                                    background: var(--surface);
                                    border-radius: 24px;
                                    border: 1px solid var(--border);
                                    padding: 24px;
                                    box-shadow: 0 20px 40px rgba(0,0,0,0.4);
                                }
                                .section-title {
                                    font-size: 1.1rem;
                                    font-weight: 700;
                                    margin: 0 0 20px 0;
                                    color: var(--text-dim);
                                    display: flex;
                                    align-items: center;
                                    gap: 8px;
                                }
                                .station-list { margin-bottom: 32px; }
                                .station-card {
                                    display: flex;
                                    align-items: center;
                                    background: var(--surface-bright);
                                    border: 1px solid var(--border);
                                    border-radius: 16px;
                                    margin-bottom: 12px;
                                    padding: 12px;
                                    transition: transform 0.2s, box-shadow 0.2s;
                                }
                                .station-card:hover {
                                    border-color: var(--accent);
                                    box-shadow: 0 0 15px var(--accent-glow);
                                }
                                .drag-handle { cursor: grab; padding: 8px; color: var(--text-dim); display: flex; align-items: center; }
                                .drag-handle:active { cursor: grabbing; }
                                .station-info { flex: 1; margin: 0 16px; min-width: 0; }
                                .station-name { font-weight: 700; font-size: 1rem; margin-bottom: 2px; }
                                .station-url { font-size: 0.8rem; color: var(--text-dim); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                                .btn-remove {
                                    background: rgba(239, 68, 68, 0.1);
                                    border: 1px solid rgba(239, 68, 68, 0.2);
                                    color: var(--danger);
                                    width: 40px;
                                    height: 40px;
                                    border-radius: 12px;
                                    display: flex;
                                    align-items: center;
                                    justify-content: center;
                                    cursor: pointer;
                                    transition: 0.2s;
                                }
                                .btn-remove:hover { background: var(--danger); color: white; }
                                .add-form { background: var(--surface-bright); border-radius: 20px; padding: 24px; border: 1px solid var(--border); }
                                .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
                                .input-group label { display: block; font-size: 0.75rem; font-weight: 800; text-transform: uppercase; color: var(--text-dim); margin-bottom: 8px; }
                                input {
                                    width: 100%;
                                    background: var(--bg);
                                    border: 1px solid var(--border);
                                    border-radius: 12px;
                                    padding: 12px 16px;
                                    color: white;
                                    font-family: inherit;
                                    font-size: 0.95rem;
                                    outline: none;
                                    transition: border-color 0.2s;
                                }
                                input:focus { border-color: var(--accent); }
                                .btn-add {
                                    width: 100%;
                                    background: var(--accent);
                                    color: white;
                                    border: none;
                                    border-radius: 12px;
                                    padding: 14px;
                                    font-weight: 700;
                                    font-size: 1rem;
                                    cursor: pointer;
                                    transition: 0.2s;
                                }
                                .btn-add:hover { filter: brightness(1.1); transform: translateY(-1px); box-shadow: 0 4px 12px rgba(59, 130, 246, 0.4); }
                                .sortable-ghost { opacity: 0.3; background: var(--accent); }
                                @media (max-width: 600px) { .form-grid { grid-template-columns: 1fr; } }
                            </style>
                        </head>
                        <body>
                            <div class="container">
                                <header>
                                    <h1>iPod Connect</h1>
                                    <div class="status-bar">
                                        <div class="status-dot"></div>
                                        Active at $ip:8080
                                    </div>
                                </header>

                                <div class="card">
                                    <div class="section-title">
                                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4.9 19.1C1 15.2 1 8.8 4.9 4.9"/><path d="M7.8 16.2c-2.3-2.3-2.3-6.1 0-8.4"/><circle cx="12" cy="12" r="2"/><path d="M16.2 7.8c2.3 2.3 2.3 6.1 0 8.4"/><path d="M19.1 4.9C23 8.8 23 15.2 19.1 19.1"/></svg>
                                        Radio Stations
                                    </div>
                                    <div id="stationList" class="station-list">
                                        $stationsRows
                                        ${if (ipodState.config.radioStations.isEmpty()) "<p style='text-align:center; color:var(--text-dim); padding: 40px;'>No stations configured.</p>" else ""}
                                    </div>
                                    <div class="section-title">
                                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                                        Add New Station
                                    </div>
                                    <div class="add-form">
                                        <form action="/add-station" method="POST">
                                            <div class="form-grid">
                                                <div class="input-group">
                                                    <label>Display Name</label>
                                                    <input type="text" name="name" placeholder="e.g. Chillhop" required>
                                                </div>
                                                <div class="input-group">
                                                    <label>Stream URL</label>
                                                    <input type="text" name="url" placeholder="https://..." required>
                                                </div>
                                            </div>
                                            <button type="submit" class="btn-add">Add to Library</button>
                                        </form>
                                    </div>
                                </div>
                            </div>
                            <script>
                                var el = document.getElementById('stationList');
                                Sortable.create(el, {
                                    handle: '.drag-handle',
                                    animation: 200,
                                    ghostClass: 'sortable-ghost',
                                    onEnd: function () {
                                        var order = Array.from(el.querySelectorAll('.station-card')).map(it => parseInt(it.dataset.id));
                                        fetch('/reorder', {
                                            method: 'POST',
                                            headers: { 'Content-Type': 'application/json' },
                                            body: JSON.stringify({ order: order })
                                        }).then(r => { if(r.ok) window.location.reload(); });
                                    }
                                });
                            </script>
                        </body>
                        </html>
                        """.trimIndent(),
                        ContentType.Text.Html
                    )
                }
                post("/add-station") {
                    val params = call.receiveParameters()
                    val name = params["name"]?.trim() ?: ""
                    val url = params["url"]?.trim() ?: ""
                    if (name.isNotEmpty() && url.isNotEmpty()) {
                        val updated = ipodState.config.radioStations.toMutableList()
                        updated.add(RadioStation(name, url))
                        ipodState.config = ipodState.config.copy(radioStations = updated)
                        saveConfig()
                    }
                    call.respondRedirect("/")
                }
                post("/remove-station") {
                    val params = call.receiveParameters()
                    val index = params["index"]?.toIntOrNull()
                    if (index != null && index in ipodState.config.radioStations.indices) {
                        val updated = ipodState.config.radioStations.toMutableList()
                        updated.removeAt(index)
                        ipodState.config = ipodState.config.copy(radioStations = updated)
                        saveConfig()
                    }
                    call.respondRedirect("/")
                }
                post("/reorder") {
                    data class ReorderRequest(val order: List<Int>)
                    try {
                        val request = call.receive<ReorderRequest>()
                        val current = ipodState.config.radioStations
                        val reordered = request.order.mapNotNull { current.getOrNull(it) }
                        if (reordered.size == current.size) {
                            ipodState.config = ipodState.config.copy(radioStations = reordered)
                            saveConfig()
                            call.respond(HttpStatusCode.OK)
                        } else { call.respond(HttpStatusCode.BadRequest) }
                    } catch (e: Exception) { call.respond(HttpStatusCode.InternalServerError) }
                }
            }
        }.start(wait = false)
        ipodState.ipAddress = getIpAddress()
    }

    private fun getIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is InetAddress && addr.address.size == 4) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) { }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop(1000, 2000)
        ipodState.releasePlayer()
        unregisterReceiver(batteryReceiver)
    }
}

// --- State Management ---

class IpodState(val onSaveConfig: () -> Unit) {
    var config by mutableStateOf(LibraryConfig())
    var ipAddress by mutableStateOf<String?>(null)
    
    var menuStack = mutableStateListOf("main")
    var selectedIndex by mutableIntStateOf(0)
    var isNowPlaying by mutableStateOf(false)
    var isAdjustingMix by mutableStateOf(false)
    var currentTrack by mutableStateOf<Track?>(null)
    var playbackProgress by mutableFloatStateOf(0.0f)
    var currentPositionText by mutableStateOf("0:00")
    var durationText by mutableStateOf("0:00")
    var noiseLevel by mutableFloatStateOf(0.33f)
    var isNoiseOn by mutableStateOf(false)
    var noiseType by mutableStateOf("White")
    var batteryLevel by mutableIntStateOf(100)

    var lastInteractionTime by mutableLongStateOf(System.currentTimeMillis())

    var sleepMinutesRemaining by mutableIntStateOf(0)
    private var sleepTimerThread: Thread? = null

    var isPickingFolder by mutableStateOf(false)
    var pickingTarget by mutableStateOf("")
    var currentPickPath by mutableStateOf("")

    private var mediaPlayer: MediaPlayer? = null
    private val audioExtensions = listOf("mp3", "flac", "m4a", "wav", "ogg")

    private var noiseTrack: AudioTrack? = null
    private var noiseThread: Thread? = null
    @Volatile private var isNoiseThreadRunning = false

    init {
        startNoiseGenerator()
    }

    fun notifyInteraction() { lastInteractionTime = System.currentTimeMillis() }

    fun isActuallyPlaying(): Boolean {
        return try { mediaPlayer?.isPlaying == true } catch (e: Exception) { false }
    }

    fun startNoiseGenerator() {
        if (isNoiseThreadRunning) return
        isNoiseThreadRunning = true
        noiseThread = Thread {
            val sampleRate = 44100
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            try {
                noiseTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(minBufSize).build()
                val buffer = ShortArray(minBufSize)
                val random = java.util.Random()
                val pinkRows = 12
                val pinkValues = DoubleArray(pinkRows)
                var pinkRunningSum = 0.0
                var lastWhite = 0.0
                noiseTrack?.play()
                while (isNoiseThreadRunning) {
                    val vol = if (isNoiseOn) 1f - noiseLevel else 0f
                    noiseTrack?.setVolume(vol)
                    for (i in buffer.indices) {
                        val white = random.nextDouble() * 2.0 - 1.0
                        val sample = when (noiseType) {
                            "White" -> white
                            "Pink" -> {
                                var mask = 1; val r = random.nextInt()
                                for (j in 0 until pinkRows) {
                                    if ((r and mask) != 0) {
                                        pinkRunningSum -= pinkValues[j]; pinkValues[j] = random.nextDouble() * 2.0 - 1.0; pinkRunningSum += pinkValues[j]
                                    }
                                    mask = mask shl 1
                                }
                                (pinkRunningSum + white) / (pinkRows + 1)
                            }
                            "Blue" -> { val b = white - lastWhite; lastWhite = white; b * 0.5 }
                            else -> white
                        }
                        buffer[i] = (sample * 0.4 * Short.MAX_VALUE).toInt().toShort()
                    }
                    noiseTrack?.write(buffer, 0, buffer.size)
                }
            } catch (e: Exception) { Log.e("IPOD", "Noise failed", e) } finally {
                try { noiseTrack?.stop(); noiseTrack?.release() } catch (e: Exception) {}
                noiseTrack = null
            }
        }.apply { start() }
    }

    fun syncVolumes(player: MediaPlayer? = mediaPlayer) {
        val streamVol = if (isNoiseOn) noiseLevel else 1.0f
        player?.setVolume(streamVol, streamVol)
    }

    fun getCurrentItems(): List<String> {
        if (isPickingFolder) {
            val items = scanFolder(currentPickPath, foldersOnly = true).toMutableList()
            items.add(0, "[ SELECT CURRENT FOLDER ]")
            return items
        }
        return when (val currentView = menuStack.last()) {
            "main" -> listOf("Music", "Audiobooks", "Radio", "Mixer", "Settings")
            "Radio" -> config.radioStations.map { it.name }
            "Mixer" -> listOf("Noise: ${if (isNoiseOn) "On" else "Off"}", "Type: $noiseType", "Mix: ${(noiseLevel * 100).toInt()}% Stream")
            "Settings" -> listOf("Set Music Folder", "Set Audiobooks Folder", "Connect: ${ipAddress ?: "No IP"}:8080", "About", "Sleep Timer")
            "Sleep Timer" -> listOf("Off", "15 Minutes", "30 Minutes", "60 Minutes", "90 Minutes")
            "Music" -> scanFolder(config.musicPath)
            "Audiobooks" -> scanFolder(config.audiobooksPath)
            else -> scanFolder(currentView)
        }
    }

    private fun scanFolder(path: String, foldersOnly: Boolean = false): List<String> {
        val root = Environment.getExternalStorageDirectory()
        val dir = if (path.isEmpty()) root else File(root, path)
        if (!dir.exists() || !dir.isDirectory) return listOf("Empty / Not Found")
        val allFiles = dir.listFiles() ?: return listOf("Access Denied")
        return allFiles.filter { file ->
            if (foldersOnly) file.isDirectory
            else file.isDirectory || audioExtensions.any { ext -> file.name.lowercase().endsWith(".$ext") }
        }.map { it.name }.sortedWith(compareBy({ !File(dir, it).isDirectory }, { it.lowercase() }))
    }

    fun handleSelect(onSave: () -> Unit) {
        notifyInteraction()
        val items = getCurrentItems()
        if (items.isEmpty() || items[0] == "Empty / Not Found" || items[0] == "Access Denied") return
        val item = items[selectedIndex]
        if (isPickingFolder) {
            if (item == "[ SELECT CURRENT FOLDER ]") {
                config = if (pickingTarget == "Music") config.copy(musicPath = currentPickPath) else config.copy(audiobooksPath = currentPickPath)
                isPickingFolder = false; onSave(); selectedIndex = 0
            } else { currentPickPath = if (currentPickPath.isEmpty()) item else "$currentPickPath/$item"; selectedIndex = 0 }
            return
        }
        if (isAdjustingMix) { isAdjustingMix = false; return }
        when (val currentView = menuStack.last()) {
            "main" -> { menuStack.add(item); selectedIndex = 0 }
            "Settings" -> when (item) {
                "Set Music Folder" -> { isPickingFolder = true; pickingTarget = "Music"; currentPickPath = ""; selectedIndex = 0 }
                "Set Audiobooks Folder" -> { isPickingFolder = true; pickingTarget = "Audiobooks"; currentPickPath = ""; selectedIndex = 0 }
                "Sleep Timer" -> { menuStack.add("Sleep Timer"); selectedIndex = 0 }
            }
            "Sleep Timer" -> {
                val mins = when (item) { "15 Minutes" -> 15; "30 Minutes" -> 30; "60 Minutes" -> 60; "90 Minutes" -> 90; else -> 0 }
                setSleepTimer(mins); menuStack.removeAt(menuStack.size - 1); selectedIndex = 0
            }
            "Radio" -> playSource(config.radioStations[selectedIndex].url, config.radioStations[selectedIndex].name, "Radio Stream")
            "Mixer" -> when {
                item.startsWith("Mix:") -> isAdjustingMix = true
                item.startsWith("Noise:") -> { isNoiseOn = !isNoiseOn; syncVolumes() }
                item.startsWith("Type:") -> noiseType = when (noiseType) { "White" -> "Blue"; "Blue" -> "Pink"; else -> "White" }
            }
            else -> {
                val basePath = if (currentView == "Music") config.musicPath else if (currentView == "Audiobooks") config.audiobooksPath else currentView
                val file = File(File(Environment.getExternalStorageDirectory(), basePath), item)
                if (file.isDirectory) { menuStack.add("$basePath/$item"); selectedIndex = 0 }
                else { playSource(file.absolutePath, item, if (currentView == "Music" || currentView == "Audiobooks") currentView else "Music") }
            }
        }
    }

    private fun setSleepTimer(minutes: Int) {
        sleepTimerThread?.interrupt(); sleepMinutesRemaining = minutes
        if (minutes > 0) {
            sleepTimerThread = Thread {
                try {
                    while (sleepMinutesRemaining > 0) { Thread.sleep(60000); sleepMinutesRemaining-- }
                    mediaPlayer?.let { try { if (it.isPlaying) it.stop(); it.release() } catch (e: Exception) {} }
                    mediaPlayer = null; isNowPlaying = false
                } catch (e: InterruptedException) {}
            }.apply { name = "SleepTimerThread"; start() }
        }
    }

    private fun playSource(source: String, name: String, album: String) {
        try {
            isNowPlaying = true; notifyInteraction()
            currentTrack = Track(name, "iPod Player", album, source)
            playbackProgress = 0f; currentPositionText = "0:00"; durationText = "0:00"
            val oldPlayer = mediaPlayer; mediaPlayer = null
            oldPlayer?.let { Thread { try { it.setOnPreparedListener(null); it.setOnCompletionListener(null); it.setOnErrorListener(null); it.release() } catch (e: Exception) {} }.start() }
            val newPlayer = MediaPlayer()
            newPlayer.apply {
                setDataSource(source); syncVolumes(this); prepareAsync()
                setOnPreparedListener { 
                    if (album.contains("Audiobooks")) { val savedPos = config.audiobookPositions[source] ?: 0; it.seekTo(savedPos) }
                    it.start() 
                }
                setOnCompletionListener { p ->
                    if (album.contains("Audiobooks")) {
                        val updated = config.audiobookPositions.toMutableMap()
                        updated.remove(source)
                        config = config.copy(audiobookPositions = updated)
                        onSaveConfig()
                    }
                    if (mediaPlayer == p) playNextTrack()
                    p.release()
                }
                setOnErrorListener { p, _, _ -> if (mediaPlayer == p) isNowPlaying = false; p.release(); true }
                mediaPlayer = this
            }
        } catch (e: Exception) { Log.e("IPOD", "Playback failed", e); isNowPlaying = false }
    }

    fun playNextTrack() {
        val track = currentTrack ?: return
        if (track.album == "Radio Stream") return
        val folderItems = scanFolder(track.album)
        val dir = File(Environment.getExternalStorageDirectory(), track.album)
        val tracks = folderItems.filter { !File(dir, it).isDirectory && audioExtensions.any { ext -> it.lowercase().endsWith(".$ext") } }
        val currentIndex = tracks.indexOf(track.name)
        if (currentIndex != -1 && currentIndex < tracks.size - 1) playSource(File(dir, tracks[currentIndex + 1]).absolutePath, tracks[currentIndex + 1], track.album)
        else isNowPlaying = false
    }

    fun playPreviousTrack() {
        val track = currentTrack ?: return
        if (track.album == "Radio Stream") return
        mediaPlayer?.let { if (it.currentPosition > 3000) { it.seekTo(0); return } }
        val folderItems = scanFolder(track.album)
        val dir = File(Environment.getExternalStorageDirectory(), track.album)
        val tracks = folderItems.filter { !File(dir, it).isDirectory && audioExtensions.any { ext -> it.lowercase().endsWith(".$ext") } }
        val currentIndex = tracks.indexOf(track.name)
        if (currentIndex > 0) playSource(File(dir, tracks[currentIndex - 1]).absolutePath, tracks[currentIndex - 1], track.album)
        else mediaPlayer?.seekTo(0)
    }

    fun togglePlay() { notifyInteraction(); try { mediaPlayer?.let { if (it.isPlaying) it.pause() else it.start() } } catch (e: Exception) {} }
    fun seek(seconds: Int) { notifyInteraction(); try { mediaPlayer?.let { val newPos = it.currentPosition + (seconds * 1000); it.seekTo(newPos.coerceIn(0, it.duration)) } } catch (e: Exception) {} }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, sec) else String.format(Locale.getDefault(), "%d:%02d", m, sec)
    }

    fun updateProgress() {
        mediaPlayer?.let { try {
            if (it.isPlaying && it.duration > 0) {
                playbackProgress = it.currentPosition.toFloat() / it.duration.toFloat(); currentPositionText = formatTime(it.currentPosition); durationText = formatTime(it.duration)
                if (currentTrack?.album?.contains("Audiobooks") == true) {
                    val updated = config.audiobookPositions.toMutableMap()
                    updated[currentTrack!!.path] = it.currentPosition
                    config = config.copy(audiobookPositions = updated)
                    onSaveConfig()
                }
            } else if (it.isPlaying) { currentPositionText = formatTime(it.currentPosition); durationText = "Live"; playbackProgress = 0f }
        } catch (e: Exception) {} }
    }

    fun releasePlayer() {
        isNoiseThreadRunning = false; try { noiseThread?.join(500) } catch (e: Exception) {}
        mediaPlayer?.let { try { it.stop() } catch (e: Exception) {}; it.release() }
        mediaPlayer = null; sleepTimerThread?.interrupt()
    }

    fun handleBack() {
        notifyInteraction()
        if (isPickingFolder) {
            currentPickPath = if (currentPickPath.contains("/")) currentPickPath.substringBeforeLast("/") else if (currentPickPath.isNotEmpty()) "" else { isPickingFolder = false; "" }
            selectedIndex = 0; return
        }
        if (isAdjustingMix) { isAdjustingMix = false } else if (isNowPlaying) { isNowPlaying = false } else if (menuStack.size > 1) { menuStack.removeAt(menuStack.size - 1); selectedIndex = 0 }
    }

    fun handleMove(delta: Int) {
        notifyInteraction()
        if (isAdjustingMix) { noiseLevel = (noiseLevel + delta * 0.02f).coerceIn(0f, 1f); syncVolumes() }
        else if (isNowPlaying && currentTrack?.album?.contains("Audiobooks") == true) { seek(delta * 5) }
        else { val items = getCurrentItems(); if (items.isNotEmpty()) selectedIndex = (selectedIndex + delta).coerceIn(0, items.size - 1) }
    }
}

data class Track(val name: String, val artist: String, val album: String, val path: String)

@Composable
fun IpodApp(state: IpodState, onSaveConfig: () -> Unit) {
    BackHandler(enabled = state.menuStack.size > 1 || state.isNowPlaying || state.isPickingFolder) { state.handleBack() }
    LaunchedEffect(Unit) {
        while (true) {
            if (state.isNowPlaying || state.isActuallyPlaying()) {
                state.updateProgress()
                if (!state.isNowPlaying && !state.isPickingFolder && !state.isAdjustingMix && System.currentTimeMillis() - state.lastInteractionTime > 15000) state.isNowPlaying = true
            }
            delay(500)
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.width(340.dp).height(640.dp).clip(RoundedCornerShape(40.dp)).background(Brush.verticalGradient(colors = listOf(Color(0xFFE6E6E6), Color(0xFFBCBCBC)))).padding(25.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IpodScreen(state)
            Spacer(modifier = Modifier.weight(1f))
            ClickWheel(onScroll = { state.handleMove(it) }, onMenu = { state.handleBack() }, onSelect = { state.handleSelect(onSaveConfig) }, onPlayPause = { state.togglePlay() }, onForward = { if (state.isNowPlaying) state.playNextTrack() else state.seek(30) }, onBackward = { if (state.isNowPlaying) state.playPreviousTrack() else state.seek(-15) })
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun IpodScreen(state: IpodState) {
    val screenBg = Color(0xFFB4C3B0); val screenText = Color(0xFF2D3436)
    Column(modifier = Modifier.fillMaxWidth().height(260.dp).border(4.dp, Color(0xFF333333), RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).background(screenBg)) {
        Row(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val title = when { state.isPickingFolder -> "Pick ${state.pickingTarget} Folder"; state.isNowPlaying -> "Now Playing"; else -> state.menuStack.last().split("/").last().replaceFirstChar { it.uppercase() } }
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = screenText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (state.sleepMinutesRemaining > 0) Text(text = "🌙 ${state.sleepMinutesRemaining}m", fontSize = 10.sp, color = screenText, modifier = Modifier.padding(end = 4.dp))
            Text(text = "${state.batteryLevel}%", fontSize = 12.sp, color = screenText)
        }
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.isAdjustingMix) AdjustmentView(state.noiseLevel, screenText)
            else if (state.isNowPlaying) NowPlayingView(state, screenText)
            else {
                val items = state.getCurrentItems()
                val listState = rememberLazyListState()
                val visibleItemCount = 6
                LaunchedEffect(state.selectedIndex) {
                    if (items.isNotEmpty()) {
                        val firstVisible = listState.firstVisibleItemIndex
                        val lastVisible = firstVisible + visibleItemCount - 1
                        if (state.selectedIndex > lastVisible) {
                            listState.scrollToItem(state.selectedIndex - visibleItemCount + 1)
                        } else if (state.selectedIndex < firstVisible) {
                            listState.scrollToItem(state.selectedIndex)
                        }
                    }
                }
                LazyColumn(state = listState) {
                    itemsIndexed(items) { index, item ->
                        Row(modifier = Modifier.fillMaxWidth().background(if (index == state.selectedIndex) screenText else Color.Transparent).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text = item, fontSize = 16.sp, fontWeight = if (item.startsWith("[ SELECT")) FontWeight.Bold else FontWeight.SemiBold, color = if (index == state.selectedIndex) screenBg else screenText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NowPlayingView(state: IpodState, textColor: Color) {
    Column(modifier = Modifier.fillMaxSize().padding(15.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = state.currentTrack?.album ?: "", fontSize = 14.sp, color = textColor.copy(alpha = 0.6f))
        Text(text = state.currentTrack?.name ?: "Unknown", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = textColor, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(text = state.currentTrack?.artist ?: "", fontSize = 16.sp, color = textColor, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = state.currentPositionText, fontSize = 12.sp, color = textColor, fontWeight = FontWeight.Bold)
            Text(text = state.durationText, fontSize = 12.sp, color = textColor, fontWeight = FontWeight.Bold)
        }
        Box(modifier = Modifier.fillMaxWidth().height(20.dp).border(2.dp, Color(0xFF333333)).background(Color.Black.copy(alpha = 0.1f))) {
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(state.playbackProgress).background(Color(0xFF333333)))
        }
    }
}

@Composable
fun AdjustmentView(level: Float, textColor: Color) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Stream Volume", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = textColor.copy(alpha = 0.6f))
        Text("${(level * 100).toInt()}%", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = textColor)
        Spacer(modifier = Modifier.height(10.dp))
        Box(modifier = Modifier.fillMaxWidth().height(20.dp).border(2.dp, Color(0xFF333333)).background(Color.Black.copy(alpha = 0.1f))) {
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(level).background(Color(0xFF333333)))
        }
    }
}

@Composable
fun ClickWheel(onScroll: (Int) -> Unit, onMenu: () -> Unit, onSelect: () -> Unit, onPlayPause: () -> Unit, onForward: () -> Unit, onBackward: () -> Unit) {
    var lastAngle by remember { mutableFloatStateOf(0f) }; var angleAccumulator by remember { mutableFloatStateOf(0f) }; val step = 18f
    Box(modifier = Modifier.size(260.dp).clip(CircleShape).background(Color.White).pointerInput(Unit) { detectDragGestures(onDragStart = { offset -> lastAngle = calculateAngle(offset, size.width.toFloat() / 2f, size.height.toFloat() / 2f); angleAccumulator = 0f }, onDrag = { change, _ -> val currentAngle = calculateAngle(change.position, size.width.toFloat() / 2f, size.height.toFloat() / 2f); var delta = currentAngle - lastAngle; if (delta > 180f) delta -= 360f else if (delta < -180f) delta += 360f; angleAccumulator += delta; if (kotlin.math.abs(angleAccumulator) >= step) { val dir = if (angleAccumulator > 0) 1 else -1; onScroll(dir); angleAccumulator -= step * dir }; lastAngle = currentAngle }) }, contentAlignment = Alignment.Center) {
        Text("MENU", Modifier.align(Alignment.TopCenter).padding(top = 20.dp).clickable { onMenu() }, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF888888))
        Text("▶▶❘", Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).clickable { onForward() }, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF888888))
        Text("❘◀◀", Modifier.align(Alignment.CenterStart).padding(start = 20.dp).clickable { onBackward() }, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF888888))
        Text("▶❘❘", Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).clickable { onPlayPause() }, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF888888))
        Box(modifier = Modifier.size(90.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFF0F0F0), Color(0xFFD9D9D9)))).border(1.dp, Color(0xFFCCCCCC), CircleShape).clickable { onSelect() })
    }
}

fun calculateAngle(offset: Offset, centerX: Float, centerY: Float): Float { val x = offset.x - centerX; val y = offset.y - centerY; return (atan2(y, x) * (180f / PI.toFloat())) }
