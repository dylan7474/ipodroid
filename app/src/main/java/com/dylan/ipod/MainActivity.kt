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
    var musicPath: String = "Music",
    var audiobooksPath: String = "Audiobooks",
    var radioStations: MutableList<RadioStation> = mutableListOf(
        RadioStation("Lofi Girl", "https://stream.live.vc.bbc.co.uk/bbc_radio_one"),
        RadioStation("KEXP", "https://kexp-mp3-128.streamguys1.com/kexp128.mp3")
    ),
    var audiobookPositions: MutableMap<String, Int> = mutableMapOf()
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
        
        // Keep Screen On
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
                        <tr>
                            <td><b>${station.name}</b></td>
                            <td><code style="font-size: 0.85em; color: #636e72;">${station.url}</code></td>
                            <td style="text-align: right;">
                                <form action="/remove-station" method="POST" style="display:inline;">
                                    <input type="hidden" name="index" value="$index">
                                    <button type="submit" class="btn-remove">Remove</button>
                                </form>
                            </td>
                        </tr>
                        """.trimIndent()
                    }.joinToString("")

                    call.respondText(
                        """
                        <!DOCTYPE html>
                        <html lang="en">
                        <head>
                            <meta charset="UTF-8">
                            <meta name="viewport" content="width=device-width, initial-scale=1.0">
                            <title>iPod Radio Manager</title>
                            <style>
                                :root { --primary: #2d3436; --accent: #0984e3; --danger: #d63031; --bg: #f5f6fa; --card: #ffffff; }
                                body { font-family: 'Segoe UI', system-ui, -apple-system, sans-serif; background-color: var(--bg); margin: 0; padding: 20px; color: var(--primary); }
                                .container { max-width: 900px; margin: 0 auto; }
                                .card { background: var(--card); border-radius: 12px; box-shadow: 0 10px 30px rgba(0,0,0,0.05); padding: 40px; margin-bottom: 20px; }
                                header { display: flex; justify-content: space-between; align-items: center; border-bottom: 2px solid var(--bg); padding-bottom: 20px; margin-bottom: 30px; }
                                h1 { margin: 0; font-size: 24px; display: flex; align-items: center; gap: 12px; }
                                .ip-badge { background: var(--bg); padding: 8px 16px; border-radius: 20px; font-size: 13px; font-weight: bold; color: #636e72; border: 1px solid #dfe6e9; }
                                table { width: 100%; border-collapse: collapse; margin-top: 10px; }
                                th { text-align: left; padding: 12px; border-bottom: 2px solid var(--bg); color: #b2bec3; text-transform: uppercase; font-size: 11px; letter-spacing: 1px; }
                                td { padding: 16px 12px; border-bottom: 1px solid var(--bg); }
                                .btn-remove { background: #fff; color: var(--danger); border: 1px solid #ff7675; padding: 6px 14px; border-radius: 6px; cursor: pointer; font-size: 12px; font-weight: 600; transition: all 0.2s; }
                                .btn-remove:hover { background: var(--danger); color: white; }
                                .add-section { margin-top: 50px; padding: 30px; background: #f8f9fa; border-radius: 10px; border: 1px solid #eee; }
                                .form-row { display: grid; grid-template-columns: 1fr 2fr auto; gap: 20px; align-items: end; }
                                .input-group { display: flex; flex-direction: column; gap: 8px; }
                                label { font-size: 12px; font-weight: 800; color: #636e72; text-transform: uppercase; }
                                input { padding: 12px; border: 1px solid #dfe6e9; border-radius: 8px; font-size: 14px; transition: border 0.2s; }
                                input:focus { outline: none; border-color: var(--accent); background: white; }
                                .btn-add { background: var(--accent); color: white; border: none; padding: 0 30px; border-radius: 8px; cursor: pointer; font-weight: bold; height: 44px; transition: 0.2s; }
                                .btn-add:hover { background: #074b83; transform: translateY(-1px); }
                                .footer { text-align: center; color: #b2bec3; font-size: 13px; margin-top: 50px; }
                                .hint { font-size: 13px; color: #636e72; margin-top: 10px; line-height: 1.5; }
                            </style>
                        </head>
                        <body>
                            <div class="container">
                                <div class="card">
                                    <header>
                                        <h1>📻 iPod Radio Station Manager</h1>
                                        <div class="ip-badge">Device IP: $ip</div>
                                    </header>

                                    <table>
                                        <thead>
                                            <tr>
                                                <th>Station Name</th>
                                                <th>Stream URL</th>
                                                <th style="text-align: right;">Action</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            $stationsRows
                                            ${if (ipodState.config.radioStations.isEmpty()) "<tr><td colspan='3' style='text-align:center; padding: 60px; color: #b2bec3;'>No stations configured. Use the form below to add your first stream.</td></tr>" else ""}
                                        </tbody>
                                    </table>

                                    <div class="add-section">
                                        <h2 style="font-size: 16px; margin-bottom: 20px; text-transform: uppercase; color: #2d3436;">Add New Stream</h2>
                                        <form action="/add-station" method="POST" class="form-row">
                                            <div class="input-group">
                                                <label>Display Name</label>
                                                <input type="text" name="name" placeholder="BBC Radio 1" required>
                                            </div>
                                            <div class="input-group">
                                                <label>Stream URL (Direct MP3/AAC Link)</label>
                                                <input type="text" name="url" placeholder="https://..." required>
                                            </div>
                                            <button type="submit" class="btn-add">Add Station</button>
                                        </form>
                                        <p class="hint"><b>Tip:</b> Make sure the URL points to a direct audio stream. Web player URLs will not work.</p>
                                    </div>
                                </div>
                                <div class="footer">
                                    iPod Classic Management Terminal &bull; Local Access Only
                                </div>
                            </div>
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
                        ipodState.config.radioStations.add(RadioStation(name, url))
                        saveConfig()
                    }
                    call.respondRedirect("/")
                }
                post("/remove-station") {
                    val params = call.receiveParameters()
                    val index = params["index"]?.toIntOrNull()
                    if (index != null && index in ipodState.config.radioStations.indices) {
                        ipodState.config.radioStations.removeAt(index)
                        saveConfig()
                    }
                    call.respondRedirect("/")
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

    // Sleep Timer State
    var sleepMinutesRemaining by mutableIntStateOf(0)
    private var sleepTimerThread: Thread? = null

    // Folder Picker State
    var isPickingFolder by mutableStateOf(false)
    var pickingTarget by mutableStateOf("") // "Music" or "Audiobooks"
    var currentPickPath by mutableStateOf("") // Path relative to root

    // Media Player
    private var mediaPlayer: MediaPlayer? = null
    private val audioExtensions = listOf("mp3", "flac", "m4a", "wav", "ogg")

    // Noise Generator
    private var noiseTrack: AudioTrack? = null
    private var noiseThread: Thread? = null
    @Volatile private var isNoiseThreadRunning = false

    init {
        startNoiseGenerator()
    }

    fun startNoiseGenerator() {
        if (isNoiseThreadRunning) return
        isNoiseThreadRunning = true
        noiseThread = Thread {
            val sampleRate = 44100
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            try {
                noiseTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(minBufSize)
                    .build()

                val buffer = ShortArray(minBufSize)
                val random = java.util.Random()
                val pinkRows = 12
                val pinkValues = DoubleArray(pinkRows)
                var pinkRunningSum = 0.0
                var lastWhite = 0.0
                
                noiseTrack?.play()
                
                while (isNoiseThreadRunning) {
                    val type = noiseType
                    val vol = if (isNoiseOn) 1f - noiseLevel else 0f
                    noiseTrack?.setVolume(vol)
                    
                    for (i in buffer.indices) {
                        val white = random.nextDouble() * 2.0 - 1.0
                        val sample = when (type) {
                            "White" -> white
                            "Pink" -> {
                                var mask = 1
                                val r = random.nextInt()
                                for (j in 0 until pinkRows) {
                                    if ((r and mask) != 0) {
                                        pinkRunningSum -= pinkValues[j]
                                        pinkValues[j] = random.nextDouble() * 2.0 - 1.0
                                        pinkRunningSum += pinkValues[j]
                                    }
                                    mask = mask shl 1
                                }
                                (pinkRunningSum + white) / (pinkRows + 1)
                            }
                            "Blue" -> {
                                val blue = white - lastWhite
                                lastWhite = white
                                blue * 0.5
                            }
                            else -> white
                        }
                        // Scale to avoid clipping and harshness
                        buffer[i] = (sample * 0.4 * Short.MAX_VALUE).toInt().toShort()
                    }
                    noiseTrack?.write(buffer, 0, buffer.size)
                }
            } catch (e: Exception) {
                Log.e("IPOD", "Noise generation failed", e)
            } finally {
                try {
                    noiseTrack?.stop()
                    noiseTrack?.release()
                } catch (e: Exception) {}
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

        val currentView = menuStack.last()
        return when (currentView) {
            "main" -> listOf("Music", "Audiobooks", "Radio", "Mixer", "Settings")
            "Radio" -> config.radioStations.map { it.name }
            "Mixer" -> listOf(
                "Noise: ${if (isNoiseOn) "On" else "Off"}",
                "Type: $noiseType",
                "Mix: ${(noiseLevel * 100).toInt()}% Stream"
            )
            "Settings" -> listOf(
                "Set Music Folder",
                "Set Audiobooks Folder",
                "Connect: ${ipAddress ?: "No IP"}:8080",
                "About",
                "Sleep Timer"
            )
            "Sleep Timer" -> listOf("Off", "15 Minutes", "30 Minutes", "60 Minutes", "90 Minutes")
            "Music" -> scanFolder(config.musicPath)
            "Audiobooks" -> scanFolder(config.audiobooksPath)
            else -> scanFolder(currentView) // Subfolder navigation
        }
    }

    private fun scanFolder(path: String, foldersOnly: Boolean = false): List<String> {
        val root = Environment.getExternalStorageDirectory()
        val dir = if (path.isEmpty()) root else File(root, path)
        
        Log.d("IPOD", "Scanning: ${dir.absolutePath} (foldersOnly=$foldersOnly)")
        
        if (!dir.exists() || !dir.isDirectory) {
            Log.e("IPOD", "Not a directory: ${dir.absolutePath}")
            return listOf("Empty / Not Found")
        }
        
        val allFiles = dir.listFiles()
        if (allFiles == null) {
            Log.e("IPOD", "listFiles() is null. Permission issue?")
            return listOf("Access Denied")
        }

        val filtered = allFiles.filter { file ->
            if (foldersOnly) {
                file.isDirectory
            } else {
                file.isDirectory || audioExtensions.any { ext -> file.name.lowercase().endsWith(".$ext") }
            }
        }
        
        Log.d("IPOD", "Found ${filtered.size} items")

        return filtered
            .map { it.name }
            .sortedWith(compareBy({ !File(dir, it).isDirectory }, { it.lowercase() }))
    }

    fun handleSelect(onSave: () -> Unit) {
        if (isPickingFolder) {
            val items = getCurrentItems()
            val item = items[selectedIndex]
            if (item == "[ SELECT CURRENT FOLDER ]") {
                if (pickingTarget == "Music") config.musicPath = currentPickPath
                else config.audiobooksPath = currentPickPath
                isPickingFolder = false
                onSave()
                selectedIndex = 0
            } else {
                currentPickPath = if (currentPickPath.isEmpty()) item else "$currentPickPath/$item"
                selectedIndex = 0
            }
            return
        }

        if (isAdjustingMix) {
            isAdjustingMix = false
            return
        }
        val items = getCurrentItems()
        if (items.isEmpty() || items[0] == "Empty / Not Found" || items[0] == "Access Denied") return
        val item = items[selectedIndex]
        val currentView = menuStack.last()
        
        when (currentView) {
            "main" -> {
                menuStack.add(item)
                selectedIndex = 0
            }
            "Settings" -> {
                when (item) {
                    "Set Music Folder" -> {
                        isPickingFolder = true
                        pickingTarget = "Music"
                        currentPickPath = ""
                        selectedIndex = 0
                    }
                    "Set Audiobooks Folder" -> {
                        isPickingFolder = true
                        pickingTarget = "Audiobooks"
                        currentPickPath = ""
                        selectedIndex = 0
                    }
                    "Sleep Timer" -> {
                        menuStack.add("Sleep Timer")
                        selectedIndex = 0
                    }
                }
            }
            "Sleep Timer" -> {
                val mins = when (item) {
                    "15 Minutes" -> 15
                    "30 Minutes" -> 30
                    "60 Minutes" -> 60
                    "90 Minutes" -> 90
                    else -> 0
                }
                setSleepTimer(mins)
                menuStack.removeAt(menuStack.size - 1)
                selectedIndex = 0
            }
            "Radio" -> {
                val station = config.radioStations[selectedIndex]
                playSource(station.url, station.name, "Radio Stream")
            }
            "Mixer" -> {
                when {
                    item.startsWith("Mix:") -> isAdjustingMix = true
                    item.startsWith("Noise:") -> {
                        isNoiseOn = !isNoiseOn
                        syncVolumes()
                    }
                    item.startsWith("Type:") -> {
                        noiseType = when (noiseType) {
                            "White" -> "Pink"
                            "Pink" -> "Blue"
                            else -> "White"
                        }
                    }
                }
            }
            "Music", "Audiobooks" -> {
                val basePath = if (currentView == "Music") config.musicPath else config.audiobooksPath
                val file = File(File(Environment.getExternalStorageDirectory(), basePath), item)
                if (file.isDirectory) {
                    menuStack.add("$basePath/$item")
                    selectedIndex = 0
                } else {
                    playSource(file.absolutePath, item, currentView)
                }
            }
            else -> {
                val file = File(File(Environment.getExternalStorageDirectory(), currentView), item)
                if (file.isDirectory) {
                    menuStack.add("${currentView}/$item")
                    selectedIndex = 0
                } else {
                    playSource(file.absolutePath, item, currentView)
                }
            }
        }
    }

    private fun setSleepTimer(minutes: Int) {
        sleepTimerThread?.interrupt()
        sleepMinutesRemaining = minutes
        if (minutes > 0) {
            sleepTimerThread = Thread {
                try {
                    while (sleepMinutesRemaining > 0) {
                        Thread.sleep(60000)
                        sleepMinutesRemaining--
                    }
                    // Stop audio stream only
                    mediaPlayer?.let {
                        try {
                            if (it.isPlaying) it.stop()
                            it.release()
                        } catch (e: Exception) {}
                    }
                    mediaPlayer = null
                    isNowPlaying = false
                    // isNoiseOn = false // Keep noise generator running
                } catch (e: InterruptedException) {}
            }.apply { 
                name = "SleepTimerThread"
                start() 
            }
        }
    }

    private fun playSource(source: String, name: String, album: String) {
        try {
            // Safety: isNowPlaying set to true early so UI switches to NowPlayingView immediately
            isNowPlaying = true
            currentTrack = Track(name, "iPod Player", album, source)
            playbackProgress = 0f
            currentPositionText = "0:00"
            durationText = "0:00"

            val oldPlayer = mediaPlayer
            mediaPlayer = null
            oldPlayer?.let {
                Thread {
                    try {
                        it.setOnPreparedListener(null)
                        it.setOnCompletionListener(null)
                        it.setOnErrorListener(null)
                        it.release()
                    } catch (e: Exception) {}
                }.start()
            }

            val newPlayer = MediaPlayer()
            newPlayer.apply {
                setDataSource(source)
                syncVolumes(this)
                prepareAsync()
                setOnPreparedListener { 
                    if (album.contains("Audiobooks")) {
                        val savedPos = config.audiobookPositions[source] ?: 0
                        it.seekTo(savedPos)
                    }
                    start()
                }
                setOnCompletionListener { p ->
                    if (mediaPlayer == p) isNowPlaying = false
                    if (album.contains("Audiobooks")) {
                        config.audiobookPositions.remove(source)
                        onSaveConfig()
                    }
                    p.release()
                }
                setOnErrorListener { p, what, extra -> 
                    Log.e("IPOD", "MediaPlayer Error: $what, $extra")
                    if (mediaPlayer == p) isNowPlaying = false
                    p.release()
                    true 
                }
                mediaPlayer = this
            }
        } catch (e: Exception) {
            Log.e("IPOD", "Playback failed", e)
            isNowPlaying = false
        }
    }

    fun togglePlay() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.pause() else it.start()
            }
        } catch (e: Exception) {
            Log.e("IPOD", "Toggle play failed", e)
        }
    }

    fun seek(seconds: Int) {
        try {
            mediaPlayer?.let {
                val newPos = it.currentPosition + (seconds * 1000)
                it.seekTo(newPos.coerceIn(0, it.duration))
            }
        } catch (e: Exception) {}
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    fun updateProgress() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying && it.duration > 0) {
                    playbackProgress = it.currentPosition.toFloat() / it.duration.toFloat()
                    currentPositionText = formatTime(it.currentPosition)
                    durationText = formatTime(it.duration)
                    
                    // Save audiobook position
                    if (currentTrack?.album?.contains("Audiobooks") == true) {
                        config.audiobookPositions[currentTrack!!.path] = it.currentPosition
                        onSaveConfig()
                    }
                } else if (it.isPlaying) {
                    currentPositionText = formatTime(it.currentPosition)
                    durationText = "Live"
                    playbackProgress = 0f
                }
            } catch (e: Exception) {}
        }
    }

    fun releasePlayer() {
        isNoiseThreadRunning = false
        try { noiseThread?.join(500) } catch (e: Exception) {}
        mediaPlayer?.let {
            try { it.stop() } catch (e: Exception) {}
            it.release()
        }
        mediaPlayer = null
        sleepTimerThread?.interrupt()
    }

    fun handleBack() {
        if (isPickingFolder) {
            if (currentPickPath.contains("/")) {
                currentPickPath = currentPickPath.substringBeforeLast("/")
            } else if (currentPickPath.isNotEmpty()) {
                currentPickPath = ""
            } else {
                isPickingFolder = false
            }
            selectedIndex = 0
            return
        }

        if (isAdjustingMix) {
            isAdjustingMix = false
        } else if (isNowPlaying) {
            isNowPlaying = false
        } else if (menuStack.size > 1) {
            menuStack.removeAt(menuStack.size - 1)
            selectedIndex = 0
        }
    }

    fun handleMove(delta: Int) {
        if (isAdjustingMix) {
            noiseLevel = (noiseLevel + delta * 0.02f).coerceIn(0f, 1f)
            syncVolumes()
        } else if (isNowPlaying && currentTrack?.album?.contains("Audiobooks") == true) {
            seek(delta * 5) // seek 5 seconds per tick
        } else {
            val items = getCurrentItems()
            if (items.isNotEmpty()) {
                selectedIndex = (selectedIndex + delta).coerceIn(0, items.size - 1)
            }
        }
    }
}

data class Track(val name: String, val artist: String, val album: String, val path: String)

// --- UI Components ---

@Composable
fun IpodApp(state: IpodState, onSaveConfig: () -> Unit) {
    BackHandler(enabled = state.menuStack.size > 1 || state.isNowPlaying || state.isPickingFolder) {
        state.handleBack()
    }

    LaunchedEffect(state.isNowPlaying) {
        while (state.isNowPlaying) {
            state.updateProgress()
            delay(500)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(340.dp)
                .height(640.dp)
                .clip(RoundedCornerShape(40.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFFE6E6E6), Color(0xFFBCBCBC))
                    )
                )
                .padding(25.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IpodScreen(state)
            Spacer(modifier = Modifier.weight(1f))
            ClickWheel(
                onScroll = { state.handleMove(it) },
                onMenu = { state.handleBack() },
                onSelect = { state.handleSelect(onSaveConfig) },
                onPlayPause = { state.togglePlay() },
                onForward = { state.seek(30) },
                onBackward = { state.seek(-15) }
            )
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun IpodScreen(state: IpodState) {
    val screenBg = Color(0xFFB4C3B0)
    val screenText = Color(0xFF2D3436)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .border(4.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(screenBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.1f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val title = when {
                state.isPickingFolder -> "Pick ${state.pickingTarget} Folder"
                state.isNowPlaying -> "Now Playing"
                else -> state.menuStack.last().split("/").last().replaceFirstChar { it.uppercase() }
            }
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = screenText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (state.sleepMinutesRemaining > 0) {
                Text(text = "🌙 ${state.sleepMinutesRemaining}m", fontSize = 10.sp, color = screenText, modifier = Modifier.padding(end = 4.dp))
            }
            Text(text = "${state.batteryLevel}%", fontSize = 12.sp, color = screenText)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (state.isAdjustingMix) {
                AdjustmentView(state.noiseLevel, screenText)
            } else if (state.isNowPlaying) {
                NowPlayingView(state, screenText)
            } else {
                val items = state.getCurrentItems()
                val listState = rememberLazyListState()
                
                LaunchedEffect(state.selectedIndex, state.isPickingFolder, state.currentPickPath) {
                    if (items.isNotEmpty()) {
                        listState.scrollToItem(state.selectedIndex)
                    }
                }

                LazyColumn(state = listState) {
                    itemsIndexed(items) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (index == state.selectedIndex) screenText else Color.Transparent)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item,
                                fontSize = 16.sp,
                                fontWeight = if (item.startsWith("[ SELECT")) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (index == state.selectedIndex) screenBg else screenText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NowPlayingView(state: IpodState, textColor: Color) {
    Column(
        modifier = Modifier.fillMaxSize().padding(15.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = state.currentTrack?.album ?: "", fontSize = 14.sp, color = textColor.copy(alpha = 0.6f))
        Text(text = state.currentTrack?.name ?: "Unknown", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = textColor, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(text = state.currentTrack?.artist ?: "", fontSize = 16.sp, color = textColor, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.weight(1f))
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
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
    var lastAngle by remember { mutableFloatStateOf(0f) }
    var angleAccumulator by remember { mutableFloatStateOf(0f) }
    val step = 18f 

    Box(
        modifier = Modifier.size(260.dp).clip(CircleShape).background(Color.White)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> lastAngle = calculateAngle(offset, size.width.toFloat() / 2f, size.height.toFloat() / 2f); angleAccumulator = 0f },
                    onDrag = { change, _ ->
                        val currentAngle = calculateAngle(change.position, size.width.toFloat() / 2f, size.height.toFloat() / 2f)
                        var delta = currentAngle - lastAngle
                        if (delta > 180f) delta -= 360f else if (delta < -180f) delta += 360f
                        angleAccumulator += delta
                        if (kotlin.math.abs(angleAccumulator) >= step) {
                            val dir = if (angleAccumulator > 0) 1 else -1
                            onScroll(dir)
                            angleAccumulator -= step * dir
                        }
                        lastAngle = currentAngle
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text("MENU", Modifier.align(Alignment.TopCenter).padding(top = 20.dp).clickable { onMenu() }, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF888888))
        Text("▶▶❘", Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).clickable { onForward() }, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF888888))
        Text("❘◀◀", Modifier.align(Alignment.CenterStart).padding(start = 20.dp).clickable { onBackward() }, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF888888))
        Text("▶❘❘", Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).clickable { onPlayPause() }, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF888888))
        Box(modifier = Modifier.size(90.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Color(0xFFF0F0F0), Color(0xFFD9D9D9)))).border(1.dp, Color(0xFFCCCCCC), CircleShape).clickable { onSelect() })
    }
}

fun calculateAngle(offset: Offset, centerX: Float, centerY: Float): Float {
    val x = offset.x - centerX
    val y = offset.y - centerY
    return (atan2(y, x) * (180f / PI.toFloat()))
}
