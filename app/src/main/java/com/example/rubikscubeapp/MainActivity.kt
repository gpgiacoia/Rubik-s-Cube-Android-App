package com.example.rubikscubeapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.rubikscubeapp.gl.CubeGLSurfaceView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private var cubeView: CubeGLSurfaceView? = null
    private lateinit var pcConnectionService: PCConnectionService
    private val serverPort = 12345

    private val PREFS_NAME = "RubiksCubeAppPrefs"
    private val PREF_KEY_IP = "last_connected_ip"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        pcConnectionService = PCConnectionService()

        val connectBtn: Button = findViewById(R.id.connectButton)
        val scanBtn: Button = findViewById(R.id.scanButton)
        val solverBtn: Button = findViewById(R.id.solverButton)

        connectBtn.setOnClickListener {
            if (pcConnectionService.connectionState.value == PCConnectionService.ConnectionState.DISCONNECTED) {
                showConnectDialog()
            } else {
                pcConnectionService.disconnect()
            }
        }

        scanBtn.setOnClickListener {
            pcConnectionService.sendCommand("SCAN")
        }

        solverBtn.setOnClickListener {
            val intent = Intent(this, SolverActivity::class.java)
            startActivity(intent)
        }

        cubeView = findViewById(R.id.cubeBackground)

        val toggleRotationButton: MaterialButton = findViewById(R.id.toggleRotationButton)
        toggleRotationButton.setOnClickListener {
            cubeView?.toggleAutoRotation()
            toggleRotationButton.text = if (cubeView?.isAutoRotationEnabled == true) {
                "Stop Rotation"
            } else {
                "Start Rotation"
            }
        }

        // Initialize the cube view with a temporary CSV representing a solved cube state.
        val csvSolved = buildString {
            appendLine("g,g,g,g,g,g,g,g,g") // FRONT (green)
            appendLine("r,r,r,r,r,r,r,r,r") // RIGHT (red)
            appendLine("b,b,b,b,b,b,b,b,b") // BACK (blue)
            appendLine("o,o,o,o,o,o,o,o,o") // LEFT (orange)
            appendLine("w,w,w,w,w,w,w,w,w") // UP (white)
            appendLine("y,y,y,y,y,y,y,y,y") // DOWN (yellow)
        }
        try {
            val tempFile: File = File.createTempFile("cube_init_", ".csv", cacheDir)
            tempFile.writeText(csvSolved)
            cubeView?.loadCubeFromCsvFile(tempFile)
        } catch (_: Exception) {
            // Ignore init failure; view will show its default state
        }

        val receivedLines = mutableListOf<String>()
        // Observe received data from PCConnectionService
        lifecycleScope.launch {
            pcConnectionService.receivedData.collect {
                line ->
                receivedLines.add(line)
                if (receivedLines.size >= 6) {
                    val csvString = receivedLines.joinToString(separator = "\n")
                    cubeView?.loadCubeFromCsvString(csvString)
                    receivedLines.clear()
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        lifecycleScope.launch {
            pcConnectionService.connectionState.collect {
                state ->
                when (state) {
                    PCConnectionService.ConnectionState.CONNECTED -> {
                        connectBtn.text = "Disconnect"
                        scanBtn.isEnabled = true
                        Toast.makeText(this@MainActivity, "Connected to PC", Toast.LENGTH_SHORT).show()
                    }
                    PCConnectionService.ConnectionState.CONNECTING -> {
                        connectBtn.text = "Connecting..."
                        scanBtn.isEnabled = false
                        Toast.makeText(this@MainActivity, "Connecting to PC...", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        connectBtn.text = "Connect"
                        scanBtn.isEnabled = false
                        Toast.makeText(this@MainActivity, "Disconnected from PC", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showConnectDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_connect, null)
        val ipAddressEditText = dialogView.findViewById<EditText>(R.id.ipAddress)
        val portEditText = dialogView.findViewById<EditText>(R.id.port)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedIp = prefs.getString(PREF_KEY_IP, "")
        ipAddressEditText.setText(savedIp)
        portEditText.setText(serverPort.toString())

        AlertDialog.Builder(this)
            .setTitle("Connect to PC")
            .setView(dialogView)
            .setPositiveButton("Connect") { _, _ ->
                val ipAddress = ipAddressEditText.text.toString()
                val port = portEditText.text.toString().toIntOrNull()

                if (ipAddress.isNotEmpty() && port != null) {
                    // Save the IP for next time
                    prefs.edit().putString(PREF_KEY_IP, ipAddress).apply()
                    pcConnectionService.connect(ipAddress, port)
                } else {
                    Toast.makeText(this, "Invalid IP address or port", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        cubeView?.onResume()
    }

    override fun onPause() {
        cubeView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        pcConnectionService.disconnect()
    }
}