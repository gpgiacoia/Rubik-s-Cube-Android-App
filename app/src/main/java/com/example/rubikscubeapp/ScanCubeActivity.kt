package com.example.rubikscubeapp

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.*
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.example.rubikscubeapp.gl.CubeGLSurfaceView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*

class ScanCubeActivity : AppCompatActivity() {

    private lateinit var scanBtn: MaterialButton
    private lateinit var cubeView: CubeGLSurfaceView
    private lateinit var subtitle: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnSpeed: MaterialButton
    private lateinit var btnShuffle: MaterialButton
    private lateinit var btnLock: MaterialButton
    private lateinit var btnBack: MaterialButton
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_cube)

        // Initialize Views
        scanBtn = findViewById(R.id.scanCube)
        cubeView = findViewById(R.id.cubeSurface)
        subtitle = findViewById(R.id.subtitle)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnShuffle = findViewById(R.id.shuffle)
        btnLock = findViewById(R.id.lock)
        btnBack = findViewById(R.id.back)
        timerText = findViewById(R.id.timerText)

        // Set initial state
        applyDisabledControls()
        updateTimerDisplay()

        // Listeners
        scanBtn.setOnClickListener { performPiScan() }
        btnStart.setOnClickListener { onStartPressed() }
        btnStop.setOnClickListener { onStopPressed() }
        btnSpeed.setOnClickListener { showSpeedMenu() }

        btnShuffle.setOnClickListener {
            if (btnShuffle.isEnabled) cubeView.setSolidBlue()
        }

        btnLock.setOnClickListener {
            isLocked = !isLocked
            cubeView.setCubeLocked(isLocked)
            updateLockButtonTint()
        }

        btnBack.setOnClickListener {
            Log.d("ScanCubeActivity", "Back button clicked")
            finish() 
        }

        // Force Back button to front to ensure it's clickable
        btnBack.bringToFront()
    }

    private fun performPiScan() {
        val ip = SessionStore.deviceIp ?: "192.168.4.1"
        val port = SessionStore.devicePort ?: 9000

        Toast.makeText(this, "Scanning cube...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            var state54: String? = null
            var error: String? = null

            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 5000)
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()

                writer.write("RPI_HELLO\n")
                writer.flush()
                reader.readLine() 

                writer.write("START_SCAN\n")
                writer.flush()

                while (true) {
                    val line = reader.readLine() ?: break
                    Log.d("PI", line)
                    if (line.startsWith("STATE:")) {
                        state54 = line.removePrefix("STATE:").trim()
                        break
                    }
                }
                socket.close()
            } catch (e: Exception) {
                error = e.message
            }

            withContext(Dispatchers.Main) {
                if (error != null) {
                    Toast.makeText(this@ScanCubeActivity, error, Toast.LENGTH_LONG).show()
                } else if (state54 != null) {
                    handleState(state54)
                } else {
                    Toast.makeText(this@ScanCubeActivity, "No state received", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleState(state: String) {
        Log.d("CUBE_RAW", state)
        val csv = convertState54ToCsvFixed(state)
        if (csv == null) {
            Toast.makeText(this, "Invalid cube data", Toast.LENGTH_LONG).show()
            return
        }
        Log.d("CUBE_CSV", csv)
        SessionStore.cubeCsv = csv
        writeTempCsv(csv)
        showCube(csv)
        Toast.makeText(this, "Scan complete", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // 🔥 FIXED MAPPING (Pi Output -> Renderer Order + Face Rotations)
    // =========================================================================
    private fun convertState54ToCsvFixed(state: String): String? {
        if (state.length != 54) return null

        // 1. Extract substrings from Pi output
        var u = state.substring(0, 9)   // White (UP)
        val l = state.substring(9, 18)  // Orange (LEFT)
        val b = state.substring(18, 27) // Blue (BACK)
        val f = state.substring(27, 36) // Green (FRONT)
        val r = state.substring(36, 45) // Red (RIGHT)
        var d = state.substring(45, 54) // Yellow (DOWN)

        // 🔥 FIX: Rotate Top (UP) face 90 degrees Clockwise
        u = rotate90CW(u)
        
        // 🔥 FIX: Rotate Bottom (DOWN) face 90 degrees Counter-Clockwise
        // Based on user feedback: RRRRRRGGG -> RRGRRGRRG
        d = rotate90CCW(d)

        // 2. Map to 3D Renderer order: 0:FRONT, 1:RIGHT, 2:BACK, 3:LEFT, 4:UP, 5:DOWN
        val orderedFaces = listOf(f, r, b, l, u, d)

        val sb = StringBuilder()
        for ((index, faceData) in orderedFaces.withIndex()) {
            val row = faceData.map { ch ->
                letterToIndex(ch) ?: 0
            }
            sb.append(row.joinToString(","))
            if (index < 5) sb.append("\n")
        }
        return sb.toString()
    }

    private fun rotate90CW(s: String): String {
        val c = s.toCharArray()
        // 0 1 2    6 3 0
        // 3 4 5 -> 7 4 1
        // 6 7 8    8 5 2
        return String(charArrayOf(
            c[6], c[3], c[0],
            c[7], c[4], c[1],
            c[8], c[5], c[2]
        ))
    }

    private fun rotate90CCW(s: String): String {
        val c = s.toCharArray()
        // 0 1 2    2 5 8
        // 3 4 5 -> 1 4 7
        // 6 7 8    0 3 6
        return String(charArrayOf(
            c[2], c[5], c[8],
            c[1], c[4], c[7],
            c[0], c[3], c[6]
        ))
    }

    private fun letterToIndex(ch: Char): Int? {
        return when (ch.uppercaseChar()) {
            'W' -> 0
            'Y' -> 1
            'G' -> 2
            'R' -> 3
            'O' -> 4
            'B' -> 5
            else -> 0
        }
    }

    private fun showCube(csv: String) {
        cubeView.loadCubeFromCsvString(csv)
        scanBtn.visibility = android.view.View.INVISIBLE
        cubeView.visibility = android.view.View.VISIBLE
        subtitle.text = "Connected"
        applyControlsForCubeLoaded()
    }

    private fun applyDisabledControls() {
        setButtonState(btnStart, false)
        setButtonState(btnStop, false)
        setButtonState(btnSpeed, false)
        setButtonState(btnShuffle, false)
        setButtonState(btnLock, true)
        setButtonState(btnBack, true) 
        updateLockButtonTint()
    }

    private fun applyControlsForCubeLoaded() {
        setButtonState(btnStart, true)
        setButtonState(btnStop, false)
        setButtonState(btnSpeed, true)
        setButtonState(btnShuffle, true)
        setButtonState(btnLock, true)
        setButtonState(btnBack, true)
        updateLockButtonTint()
    }

    private fun setButtonState(btn: MaterialButton, enabled: Boolean) {
        btn.isEnabled = enabled
        val color = if (enabled) Color.parseColor("#D32F2F") else Color.parseColor("#BDBDBD")
        btn.backgroundTintList = ColorStateList.valueOf(color)
        btn.invalidate()
    }

    private fun updateLockButtonTint() {
        val color = if (isLocked) Color.MAGENTA else Color.parseColor("#4C00FF")
        btnLock.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun onStartPressed() {
        SessionStore.isRunning = true
        SessionStore.runningSinceMs = SystemClock.elapsedRealtime()
        uiHandler.post(tickRunnable)
        
        setButtonState(btnStart, false)
        setButtonState(btnStop, true)
    }

    private fun onStopPressed() {
        SessionStore.isRunning = false
        uiHandler.removeCallbacks(tickRunnable)
        
        setButtonState(btnStart, true)
        setButtonState(btnStop, false)
    }

    private fun updateTimerDisplay() {
        timerText.text = String.format(Locale.US, "%.3f", SessionStore.elapsedMs / 1000.0)
    }

    private fun showSpeedMenu() {
        val menu = PopupMenu(this, btnSpeed)
        listOf("0.25x", "0.5x", "1x", "2x").forEachIndexed { i, s ->
            menu.menu.add(0, i, i, s)
        }
        menu.setOnMenuItemClickListener {
            btnSpeed.text = it.title
            true
        }
        menu.show()
    }

    private fun writeTempCsv(csv: String) {
        val file = File.createTempFile("cube_", ".csv", cacheDir)
        file.writeText(csv)
        SessionStore.tempCsvFile = file
    }
}
