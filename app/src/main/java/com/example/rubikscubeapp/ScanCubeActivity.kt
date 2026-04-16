package com.example.rubikscubeapp


import android.os.*import android.content.res.ColorStateList
import android.graphics.Color
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.example.rubikscubeapp.gl.CubeGLSurfaceView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import java.io.File          // <--- Add this line
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*
import kotlin.text.append
import kotlin.text.map

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_cube)

        scanBtn = findViewById(R.id.scanCube)
        cubeView = findViewById(R.id.cubeSurface)
        subtitle = findViewById(R.id.subtitle)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnShuffle = findViewById(R.id.shuffle)
        btnLock = findViewById(R.id.lock)
        timerText = findViewById(R.id.timerText)

        applyDisabledControls()
        updateTimerDisplay()

        scanBtn.setOnClickListener { performPiScan() }
        btnStart.setOnClickListener { onStartPressed() }
        btnStop.setOnClickListener { onStopPressed() }
        btnSpeed.setOnClickListener { showSpeedMenu() }

        btnShuffle.setOnClickListener {
            if (btnShuffle.isEnabled) cubeView.setSolidBlue()
        }

        btnLock.setOnClickListener {
            if (!btnLock.isEnabled) return@setOnClickListener
            isLocked = !isLocked
            cubeView.setCubeLocked(isLocked)
            updateLockButtonTint()
        }
    }

    // =========================
    // PI COMMUNICATION
    // =========================
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

                reader.readLine() // handshake ignored safely

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

    // =========================
    // FIXED CUBE PARSING (ROTATION CORRECTED)
    // =========================
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

    // =========================
    // 🔥 FIXED MAPPING WITH ROTATION SUPPORT
    // =========================

    // =========================
    // 🔥 UPDATED MAPPING (Matches Pi Output + App Renderer)
    // =========================
    private fun convertState54ToCsvFixed(state: String): String? {
        if (state.length != 54) return null

        // Pi sequence: U(0-8), L(9-17), F(18-26), R(27-35), B(36-44), D(45-53)
        val u = state.substring(0, 9)
        val f = state.substring(9, 18)
        val l = state.substring(18, 27)
        val r = state.substring(27, 36)
        val b = state.substring(36, 45)
        val d = state.substring(45, 54)

        // The 3D Renderer (CubeGLSurfaceView) enum order is:
        // 0:FRONT, 1:RIGHT, 2:BACK, 3:LEFT, 4:UP, 5:DOWN

        // CHANGE THIS LINE: Swap 'b' and 'u' to fix the Blue/White flip
        // Previous: listOf(f, r, b, l, u, d)
        val orderedFaces = listOf(f, r, u, l, b, d)

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

    private fun letterToIndex(ch: Char): Int? {
        // Matches CubeGLSurfaceView: 0=W, 1=Y, 2=G, 3=R, 4=O, 5=B
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

    private fun applyRotationFix(face: String, s: String): String {
        val grid = s.toCharArray()
        return when (face) {
            // BACK and DOWN are often captured upside down by dual-cam Pi setups
            "BACK"  -> rotate180(grid)
            "DOWN"  -> rotate180(grid)
            // If faces look 'tilted', change rotate0 to rotate90 or rotate270
            else    -> String(grid)
        }
    }


    // =========================
    // 🔥 ROTATION FIX (THIS IS THE IMPORTANT PART)
    // =========================

    // grid helpers (3x3)
    private fun rotate0(c: CharArray) = String(c)

    private fun rotate180(c: CharArray): String {
        return String(charArrayOf(
            c[8], c[7], c[6],
            c[5], c[4], c[3],
            c[2], c[1], c[0]
        ))
    }

    private fun rotate90(c: CharArray): String {
        return String(charArrayOf(
            c[6], c[3], c[0],
            c[7], c[4], c[1],
            c[8], c[5], c[2]
        ))
    }

    private fun rotate270(c: CharArray): String {
        return String(charArrayOf(
            c[2], c[5], c[8],
            c[1], c[4], c[7],
            c[0], c[3], c[6]
        ))
    }

    // =========================
    // UI
    // =========================
    private fun showCube(csv: String) {
        cubeView.loadCubeFromCsvString(csv)
        scanBtn.visibility = android.view.View.INVISIBLE
        cubeView.visibility = android.view.View.VISIBLE
        subtitle.text = "Connected"
        applyControlsForCubeLoaded()
    }

    private fun applyDisabledControls() {
        setButton(btnStart, false)
        setButton(btnStop, false)
        setButton(btnSpeed, false)
        setButton(btnShuffle, false)
        setButton(btnLock, false)
    }

    private fun applyControlsForCubeLoaded() {
        setButton(btnStart, true)
        setButton(btnStop, false)
        setButton(btnSpeed, true)
        setButton(btnShuffle, true)
        setButton(btnLock, true)
    }

    private fun setButton(btn: MaterialButton, enabled: Boolean) {
        btn.isEnabled = enabled
        btn.backgroundTintList = ColorStateList.valueOf(
            if (enabled) Color.DKGRAY else Color.GRAY
        )
    }

    private fun updateLockButtonTint() {
        btnLock.backgroundTintList = ColorStateList.valueOf(
            if (isLocked) Color.MAGENTA else Color.GREEN
        )
    }

    private fun onStartPressed() {
        SessionStore.isRunning = true
        SessionStore.runningSinceMs = SystemClock.elapsedRealtime()
        uiHandler.post(tickRunnable)
    }

    private fun onStopPressed() {
        SessionStore.isRunning = false
        uiHandler.removeCallbacks(tickRunnable)
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