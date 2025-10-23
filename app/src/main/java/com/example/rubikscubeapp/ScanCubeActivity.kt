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

class ScanCubeActivity : AppCompatActivity() {

    private lateinit var scanBtn: MaterialButton
    private lateinit var cubeView: CubeGLSurfaceView
    private lateinit var subtitle: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnSpeed: MaterialButton
    private lateinit var timerText: TextView

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
        findViewById<MaterialButton>(R.id.Back)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        scanBtn = findViewById(R.id.scanCube)
        cubeView = findViewById(R.id.cubeSurface)
        subtitle = findViewById(R.id.subtitle)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnSpeed = findViewById(R.id.btnSpeed)
        timerText = findViewById(R.id.timerText)

        // Initial states based on session (if cube already loaded this run)
        if (SessionStore.cubeCsv != null) {
            showCube(SessionStore.cubeCsv!!)
        } else {
            applyDisabledControls()
            updateTimerDisplay()
        }

        // Wire actions
        scanBtn.setOnClickListener { pickCsv.launch(arrayOf("text/csv", "text/*")) }
        btnStart.setOnClickListener { onStartPressed() }
        btnStop.setOnClickListener { onStopPressed() }
        btnSpeed.setOnClickListener { showSpeedMenu() }

        // Restore speed label if set in this run
        SessionStore.speedLabel?.let { btnSpeed.text = it }

        // Restore running state
        if (SessionStore.isRunning) {
            setRunningUi(running = true)
            startTimerTicker()
        }
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
        if (SessionStore.isRunning) startTimerTicker() else updateTimerDisplay()
    }

    private fun applyDisabledControls() {
        // All disabled gray
        setButtonState(btnStart, enabled = false, colorHex = "#BDBDBD")
        setButtonState(btnStop, enabled = false, colorHex = "#BDBDBD")
        setButtonState(btnSpeed, enabled = false, colorHex = "#BDBDBD")
    }

    private fun applyControlsForCubeLoaded() {
        // Start available (red), Stop disabled (gray), Speed available (blue)
        setButtonState(btnStart, enabled = true, colorHex = "#D32F2F")
        setButtonState(btnStop, enabled = false, colorHex = "#BDBDBD")
        setButtonState(btnSpeed, enabled = true, colorHex = "#3F51B5")
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
