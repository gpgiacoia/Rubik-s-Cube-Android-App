package com.example.rubikscubeapp

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.example.rubikscubeapp.gl.CubeGLSurfaceView
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import android.widget.Toast
import java.io.File

class ScanCubeActivity : AppCompatActivity() {

    private lateinit var scanBtn: MaterialButton
    private lateinit var cubeView: CubeGLSurfaceView
    private lateinit var subtitle: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnSpeed: MaterialButton

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

        // Initial states based on session (if cube already loaded this run)
        if (SessionStore.cubeCsv != null) {
            showCube(SessionStore.cubeCsv!!)
        } else {
            applyDisabledControls()
        }

        // Wire actions
        scanBtn.setOnClickListener { pickCsv.launch(arrayOf("text/csv", "text/*")) }
        btnStart.setOnClickListener { onStartPressed() }
        btnStop.setOnClickListener { onStopPressed() }
        btnSpeed.setOnClickListener { showSpeedMenu() }

        // Restore speed label if set in this run
        if (SessionStore.speedLabel != null) {
            btnSpeed.text = SessionStore.speedLabel
        }

        // Restore running state
        if (SessionStore.isRunning) {
            setRunningUi(running = true)
        }
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
        // If we had a previous running state, apply it
        if (SessionStore.isRunning) setRunningUi(true)
    }

    private fun onStartPressed() {
        if (btnStart.isEnabled) {
            setRunningUi(running = true)
            SessionStore.isRunning = true
        }
    }

    private fun onStopPressed() {
        if (btnStop.isEnabled) {
            setRunningUi(running = false)
            SessionStore.isRunning = false
        }
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
