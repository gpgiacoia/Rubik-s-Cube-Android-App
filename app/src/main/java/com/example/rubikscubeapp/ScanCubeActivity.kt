package com.example.rubikscubeapp

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.example.rubikscubeapp.gl.CubeGLSurfaceView
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class ScanCubeActivity : AppCompatActivity() {

    private lateinit var scanBtn: MaterialButton
    private lateinit var cubeView: CubeGLSurfaceView
    private lateinit var subtitle: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnSpeed: MaterialButton
    private lateinit var btnShuffle: MaterialButton
    private lateinit var btnLock: MaterialButton
    private lateinit var timerText: TextView

    private var isLocked = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateTimerDisplay()
            if (SessionStore.isRunning) {
                uiHandler.postDelayed(this, 200)
            }
        }
    }

    private val pickCsv = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            handleCsvPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_cube)

        // Back button behaves like DeviceListActivity
        findViewById<MaterialButton>(R.id.back)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        scanBtn = findViewById(R.id.scanCube)
        cubeView = findViewById(R.id.cubeSurface)
        subtitle = findViewById(R.id.subtitle)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnShuffle = findViewById(R.id.shuffle)
        btnLock = findViewById(R.id.lock)
        timerText = findViewById(R.id.timerText)

        // Initialize lock button enabled state: disabled until a cube CSV is loaded
        val hasCube = SessionStore.cubeCsv != null
        setButtonState(btnLock, enabled = hasCube, colorHex = if (hasCube) "#80FF00FF" else "#BDBDBD")

        // Initial states based on session (if cube already loaded this run)
        if (SessionStore.cubeCsv != null) {
            showCube(SessionStore.cubeCsv!!)
        } else {
            applyDisabledControls()
            updateTimerDisplay()
        }

        // Wire actions: instead of launching file picker, wire scan button to query Raspberry Pi
        scanBtn.setOnClickListener { performPiScan() }
        btnStart.setOnClickListener { onStartPressed() }
        btnStop.setOnClickListener { onStopPressed() }
        btnSpeed.setOnClickListener { showSpeedMenu() }

        // Wire shuffle button: only active when a cube has been loaded/scanned
        btnShuffle.setOnClickListener {
            if (!btnShuffle.isEnabled) return@setOnClickListener
            // Only shuffle the currently-loaded cube; do not override a missing scanned cube
            cubeView.setSolidBlue()
        }

        // Wire lock button: behave same as MainActivity's lock (only works when enabled)
        btnLock.setOnClickListener {
            if (!btnLock.isEnabled) return@setOnClickListener
            isLocked = !isLocked
            cubeView.setCubeLocked(isLocked)
            updateLockButtonTint()
        }

        // Restore speed label if set in this run
        SessionStore.speedLabel?.let { btnSpeed.text = it }

        // Restore running state
        if (SessionStore.isRunning) {
            setRunningUi(running = true)
            startTimerTicker()
        }
    }

    private fun performPiScan() {
        // Use stored SessionStore.deviceIp/port or fallback to common defaults
        val ip = SessionStore.deviceIp ?: "192.168.4.1"
        val port = SessionStore.devicePort ?: 9000

        // Provide immediate UI feedback
        Toast.makeText(this, "Connecting to Pi $ip:$port…", Toast.LENGTH_SHORT).show()

        GlobalScope.launch {
            var error: String? = null
            var state54: String? = null
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), TimeUnit.SECONDS.toMillis(5).toInt())
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

                // Handshake
                writer.write("${"RPI_HELLO"}")
                writer.newLine()
                writer.flush()
                val reply = reader.readLine()
                if (reply == null) throw Exception("No handshake reply")
                val r = reply.trim().uppercase()
                if (!(r.contains("RPI_ACK") || r.contains("ACK") || r.contains("OK"))) {
                    // allow server to indicate RUNNING state too
                    if (r.contains("RUNNING")) {
                        // continue — server accepted but busy
                    } else {
                        throw Exception("Unexpected handshake reply: $reply")
                    }
                }

                // Send START_SCAN and wait for STATE
                writer.write("START_SCAN")
                writer.newLine()
                writer.flush()

                // read lines until STATE: prefix
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.trim().isEmpty()) continue
                    if (line.startsWith("READY")) {
                        runOnUiThread { Toast.makeText(this@ScanCubeActivity, "Pi ready, scanning…", Toast.LENGTH_SHORT).show() }
                        continue
                    }
                    if (line.startsWith("STATE:")) {
                        val payload = line.removePrefix("STATE:").trim()
                        if (payload.equals("FAILED", ignoreCase = true) || payload.length != 54) {
                            // collect extra diagnostic lines
                            val sb = StringBuilder()
                            if (payload.isNotEmpty()) sb.append(payload).append("\n")
                            try {
                                while (true) {
                                    val extra = reader.readLine() ?: break
                                    if (extra.trim().isEmpty()) continue
                                    sb.append(extra).append("\n")
                                }
                            } catch (_: Exception) {}
                            throw Exception("Scan failed: ${sb.toString().trim()}")
                        } else {
                            state54 = payload
                        }
                        break
                    }
                }
                try { socket.close() } catch (_: Exception) {}

            } catch (e: Exception) {
                error = e.localizedMessage ?: e.toString()
            }

            runOnUiThread {
                if (error != null) {
                    Toast.makeText(this@ScanCubeActivity, "Scan error: $error", Toast.LENGTH_LONG).show()
                } else if (state54 != null) {
                    // Convert 54-letter sequence into CSV format expected by the app and display
                    val csv = convertState54ToCsv(state54!!)
                    if (csv == null) {
                        Toast.makeText(this@ScanCubeActivity, "Invalid STATE from Pi: $state54", Toast.LENGTH_LONG).show()
                    } else {
                        SessionStore.cubeCsv = csv
                        writeTempCsv(csv)
                        showCube(csv)
                        Toast.makeText(this@ScanCubeActivity, "Scan complete", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@ScanCubeActivity, "No STATE received", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Map color letters to app color indices: 0 white (W), 1 yellow (Y), 2 green (G),
    // 3 red (R), 4 orange (O), 5 blue (B)
    private fun letterToIndex(ch: Char): Int? {
        return when (ch.uppercaseChar()) {
            'W' -> 0
            'Y' -> 1
            'G' -> 2
            'R' -> 3
            'O' -> 4
            'B' -> 5
            else -> null
        }
    }

    // Convert a 54-letter state (face order assumed: FRONT, RIGHT, BACK, LEFT, UP, DOWN)
    // into CSV with 6 lines of 9 comma-separated indices each
    private fun convertState54ToCsv(state: String): String? {
        if (state.length != 54) return null
        val indices = IntArray(54)
        for (i in state.indices) {
            val idx = letterToIndex(state[i]) ?: return null
            indices[i] = idx
        }
        val sb = StringBuilder()
        var p = 0
        repeat(6) {
            val line = (0 until 9).joinToString(",") { j -> indices[p + j].toString() }
            sb.append(line)
            if (it < 5) sb.append('\n')
            p += 9
        }
        return sb.toString()
    }

    private fun updateLockButtonTint() {
        // If the lock button is disabled, show gray; otherwise reflect locked/unlocked purple
        if (!this::btnLock.isInitialized || !btnLock.isEnabled) {
            btnLock.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#BDBDBD"))
            return
        }
        val color = if (isLocked) "#FF00FF" else "#80FF00FF"
        btnLock.backgroundTintList = ColorStateList.valueOf(Color.parseColor(color))
    }

    override fun onResume() {
        super.onResume()
        if (SessionStore.isRunning) startTimerTicker() else updateTimerDisplay()
    }

    override fun onPause() {
        super.onPause()
        // Stop posting to handler to avoid leaks; state remains in SessionStore
        uiHandler.removeCallbacks(tickRunnable)
    }

    private fun handleCsvPicked(uri: Uri) {
        val csvText = readTextFromUri(contentResolver, uri) ?: run {
            Toast.makeText(this, "Couldn't read CSV", Toast.LENGTH_LONG).show()
            return
        }

        if (!isValidCubeCsv(csvText)) {
            Toast.makeText(this, "Invalid CSV: 6 rows, 9 integers (0..5) each", Toast.LENGTH_LONG).show()
            return
        }

        // Save into session store and a temp cache file (ephemeral)
        SessionStore.cubeCsv = csvText
        writeTempCsv(csvText)

        showCube(csvText)
    }

    private fun showCube(csvText: String) {
        // Apply CSV to the GL view, then reveal it and hide the button (keep layout by making button INVISIBLE)
        cubeView.loadCubeFromCsvString(csvText)
        scanBtn.visibility = android.view.View.INVISIBLE
        cubeView.visibility = android.view.View.VISIBLE
        subtitle.text = "Connected"
        applyControlsForCubeLoaded()
        // Enable lock now that a cube is present
        setButtonState(btnLock, enabled = true, colorHex = "#80FF00FF")
        updateLockButtonTint()
        if (SessionStore.isRunning) startTimerTicker() else updateTimerDisplay()
    }

    private fun applyDisabledControls() {
        // All disabled gray
        setButtonState(btnStart, enabled = false, colorHex = "#BDBDBD")
        setButtonState(btnStop, enabled = false, colorHex = "#BDBDBD")
        setButtonState(btnSpeed, enabled = false, colorHex = "#BDBDBD")
        // Keep shuffle disabled until a scanned cube is present
        setButtonState(btnShuffle, enabled = false, colorHex = "#BDBDBD")
        // Keep lock disabled until a cube is scanned/imported
        setButtonState(btnLock, enabled = false, colorHex = "#BDBDBD")
    }

    private fun applyControlsForCubeLoaded() {
        // Start available (red), Stop disabled (gray), Speed available (blue)
        setButtonState(btnStart, enabled = true, colorHex = "#D32F2F")
        setButtonState(btnStop, enabled = false, colorHex = "#BDBDBD")
        setButtonState(btnSpeed, enabled = true, colorHex = "#3F51B5")
        // Shuffle is now available (red)
        setButtonState(btnShuffle, enabled = true, colorHex = "#D32F2F")
        // Enable lock when a cube is loaded
        setButtonState(btnLock, enabled = true, colorHex = "#80FF00FF")
        // If we had a previous running state, apply it (timer handled by caller)
        if (SessionStore.isRunning) setRunningUi(true)
    }

    private fun setRunningUi(running: Boolean) {
        if (running) {
            // Stop becomes available (red), Start disabled gray
            setButtonState(btnStop, enabled = true, colorHex = "#D32F2F")
            setButtonState(btnStart, enabled = false, colorHex = "#BDBDBD")
        } else {
            // Start available (red), Stop disabled gray
            setButtonState(btnStart, enabled = true, colorHex = "#D32F2F")
            setButtonState(btnStop, enabled = false, colorHex = "#BDBDBD")
        }
    }

    private fun onStartPressed() {
        if (!btnStart.isEnabled) return
        if (!SessionStore.isRunning) {
            SessionStore.isRunning = true
            SessionStore.runningSinceMs = SystemClock.elapsedRealtime()
        }
        setRunningUi(running = true)
        startTimerTicker()
    }

    private fun onStopPressed() {
        if (!btnStop.isEnabled) return
        if (SessionStore.isRunning) {
            // Accumulate elapsed time
            val since = SessionStore.runningSinceMs
            if (since != null) {
                SessionStore.elapsedMs += (SystemClock.elapsedRealtime() - since)
            }
            SessionStore.runningSinceMs = null
            SessionStore.isRunning = false
        }
        setRunningUi(running = false)
        // Stop updates
        uiHandler.removeCallbacks(tickRunnable)
        updateTimerDisplay()
    }

    private fun startTimerTicker() {
        uiHandler.removeCallbacks(tickRunnable)
        uiHandler.post(tickRunnable)
    }

    private fun updateTimerDisplay() {
        val base = SessionStore.elapsedMs
        val extra = SessionStore.runningSinceMs?.let { SystemClock.elapsedRealtime() - it } ?: 0L
        val totalMs = base + extra
        val seconds = totalMs / 1000.0
        timerText.text = String.format(Locale.US, "%.3f", seconds)
    }

    private fun showSpeedMenu() {
        if (!btnSpeed.isEnabled) return
        val menu = PopupMenu(this, btnSpeed)
        val options = listOf("0.25x", "0.5x", "1x", "1.5x", "2x")
        options.forEachIndexed { idx, label -> menu.menu.add(0, idx, idx, label) }
        menu.setOnMenuItemClickListener { item ->
            val label = options[item.itemId]
            btnSpeed.text = label
            SessionStore.speedLabel = label
            true
        }
        menu.show()
    }

    private fun setButtonState(button: MaterialButton, enabled: Boolean, colorHex: String) {
        button.isEnabled = enabled
        button.backgroundTintList = ColorStateList.valueOf(Color.parseColor(colorHex))
        // Keep text white for contrast
        button.setTextColor(Color.WHITE)
    }

    private fun readTextFromUri(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.openInputStream(uri)?.use { it.reader().readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidCubeCsv(csv: String): Boolean {
        val lines = csv.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size != 6) return false
        for (ln in lines) {
            val parts = ln.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size != 9) return false
            for (p in parts) {
                val v = p.toIntOrNull() ?: return false
                if (v !in 0..5) return false
            }
        }
        return true
    }

    private fun writeTempCsv(csv: String) {
        try {
            // Replace existing temp file if any
            SessionStore.tempCsvFile?.delete()
            val tmp: File = File.createTempFile("cube_import_", ".csv", cacheDir)
            tmp.writeText(csv)
            SessionStore.tempCsvFile = tmp
        } catch (_: Exception) { /* ignore */ }
    }
}
