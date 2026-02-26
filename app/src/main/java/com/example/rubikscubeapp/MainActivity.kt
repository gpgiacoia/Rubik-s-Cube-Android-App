package com.example.rubikscubeapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.rubikscubeapp.gl.CubeGLSurfaceView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import android.graphics.Color
import android.view.ViewGroup
import android.util.Log

class MainActivity : AppCompatActivity() {
    private var cubeView: CubeGLSurfaceView? = null
    private var btnShuffle: MaterialButton? = null
    private var btnLock: MaterialButton? = null
    private var isLocked = false

    // Persistent Pi connection fields so handshake and START_SCAN happen on the same socket
    private var piSocket: Socket? = null
    private var piReader: java.io.BufferedReader? = null
    private var piWriter: java.io.BufferedWriter? = null

    // Debug: automatically send START_SCAN immediately after a successful handshake
    // Set to false to restore manual Start Scan UI flow.
    private val DEBUG_AUTO_START_SCAN = false

    private val selectDeviceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val address = result.data?.getStringExtra("device_address")
            if (!address.isNullOrEmpty()) {
                // TODO: initiate connection with selected device address
                Toast.makeText(this, "Selected device: $address", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        //find button in view
        val btn: Button = findViewById(R.id.Start)
        btn.setOnClickListener {
            // Show the connect dialog (ask for Pi IP/port) — after successful connect we will navigate to ScanCubeActivity
            showConnectDialog()
        }

        cubeView = findViewById(R.id.cubeBackground)
        btnShuffle = findViewById(R.id.shuffle)
        btnLock = findViewById(R.id.lock)

        // Initialize the cube view with a temporary CSV representing a solved cube state.
        // Order expected by loader: FRONT, RIGHT, BACK, LEFT, UP, DOWN
        // Color indices: 0 white, 1 yellow, 2 green, 3 red, 4 orange, 5 blue
        val csvSolved = buildString {
            appendLine("2,2,2,2,2,2,2,2,2") // FRONT (green)
            appendLine("3,3,3,3,3,3,3,3,3") // RIGHT (red)
            appendLine("5,5,5,5,5,5,5,5,5") // BACK (blue)
            appendLine("4,4,4,4,4,4,4,4,4") // LEFT (orange)
            appendLine("0,0,0,0,0,0,0,0,0") // UP (white)
            appendLine("1,1,1,1,1,1,1,1,1") // DOWN (yellow)
        }
        try {
            val tempFile: File = File.createTempFile("cube_init_", ".csv", cacheDir)
            tempFile.writeText(csvSolved)
            cubeView?.loadCubeFromCsvFile(tempFile)
        } catch (_: Exception) {
            // Ignore init failure; view will show its default state
        }

        // Home-screen shuffle: always enabled and always performs the solid-blue action
        btnShuffle?.isEnabled = true
        btnShuffle?.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#D32F2F"))
        btnShuffle?.setOnClickListener {
            cubeView?.setSolidBlue()
        }

        // Lock button: toggle locked isometric view
        // Initial tint assumed set in layout; ensure it reflects unlocked state
        btnLock?.isEnabled = true
        updateLockButtonTint()
        btnLock?.setOnClickListener {
            isLocked = !isLocked
            cubeView?.setCubeLocked(isLocked)
            updateLockButtonTint()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun showConnectDialog() {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val ipInput = EditText(this)
        ipInput.hint = "Raspberry Pi IP (e.g. 192.168.1.10)"
        ipInput.setText(SessionStore.deviceIp ?: "")
        val portInput = EditText(this)
        portInput.hint = "Port (e.g. 9000)"
        portInput.setText(SessionStore.devicePort?.toString() ?: "9000")
        layout.addView(ipInput)
        layout.addView(portInput)

        AlertDialog.Builder(this)
            .setTitle("Connect to Raspberry Pi")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                val ip = ipInput.text.toString().trim()
                val port = portInput.text.toString().trim().toIntOrNull() ?: 9000
                attemptConnectToPi(ip, port)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun attemptConnectToPi(ip: String, port: Int) {
        // Do network I/O off the UI thread
        GlobalScope.launch {
            var success = false
            var errMsg: String? = null
            // If an existing socket is open to another host, close it first
            disconnectPi()
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(ip, port), TimeUnit.SECONDS.toMillis(3).toInt())
                // Use buffered reader/writer and newline-terminated lines for handshake
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                writer.write("RPI_HELLO")
                writer.newLine()
                writer.flush()
                val reply = reader.readLine()
                val (accepted, status) = interpretHandshakeReply(reply)
                if (accepted) {
                    success = true
                    if (status == "running") {
                        errMsg = "Device is busy (RUNNING_CUBE_SOLVER)"
                    }
                    // Keep the socket open for subsequent START_SCAN on the same connection
                    Log.d("MainActivity", "Handshake accepted from $ip:$port; storing persistent socket")
                    piSocket = socket
                    piReader = reader
                    piWriter = writer

                    // If debugging, immediately start scan on the same connection so server receives START_SCAN
                    if (DEBUG_AUTO_START_SCAN) {
                        Log.d("MainActivity", "DEBUG_AUTO_START_SCAN enabled — invoking startScanOnPi immediately")
                        startScanOnPi(ip, port)
                    }
                } else {
                    errMsg = "Unexpected reply: ${reply ?: "no reply"}"
                    try { socket.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "connect error", e)
                errMsg = e.localizedMessage ?: e.toString()
            } finally {
                // Do not close socket here if we stored it in piSocket; otherwise it's already closed
                if (piSocket == null) {
                    try { socket.close() } catch (_: Exception) {}
                }
            }

            runOnUiThread {
                if (success) {
                    SessionStore.deviceIp = ip
                    SessionStore.devicePort = port
                    val toastMsg = if (errMsg != null) "Connected to $ip:$port (busy)" else "Connected to $ip:$port"
                    Toast.makeText(this@MainActivity, toastMsg, Toast.LENGTH_SHORT).show()

                    // Navigate to the scan screen now that we have a successful connection
                    startActivity(Intent(this@MainActivity, ScanCubeActivity::class.java))

                } else {
                    // Show a helpful dialog with troubleshooting steps and the error detail
                    val msg = "Failed to connect to $ip:$port.\n\nError: ${errMsg ?: "unknown"}\n\nChecks:\n• Is the Raspberry Pi server running?\n• Is the Pi reachable from this device (same Wi-Fi)?\n• Is the correct port (9000) open and listening?\n• Try: from a laptop: `nc $ip $port` and send RPI_HELLO to confirm."
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Connection failed")
                        .setMessage(msg)
                        .setPositiveButton("Details") { _, _ ->
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Error detail")
                                .setMessage(errMsg ?: "No detail available")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                        .setNegativeButton("OK", null)
                        .show()
                    Log.e("MainActivity", "Failed to connect: ${errMsg ?: "unknown"}")
                }
            }
        }
    }

    private fun startScanOnPi(ip: String, port: Int) {
        GlobalScope.launch {
            var errMsg: String? = null
            var stateStr: String? = null
            // Prefer re-using an existing connection if available
            val socket = piSocket
            val reader = piReader
            val writer = piWriter
            var createdTemporary = false
            var tempSocket: Socket? = null
            var tempReader: java.io.BufferedReader? = null
            var tempWriter: java.io.BufferedWriter? = null
            try {
                if (socket == null || socket.isClosed || reader == null || writer == null) {
                    // No existing connection: open a temporary one for this command
                    tempSocket = Socket()
                    tempSocket.connect(InetSocketAddress(ip, port), TimeUnit.SECONDS.toMillis(5).toInt())
                    tempReader = tempSocket.getInputStream().bufferedReader(Charsets.UTF_8)
                    tempWriter = tempSocket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    // handshake on the temporary connection
                    tempWriter.write("RPI_HELLO")
                    tempWriter.newLine()
                    tempWriter.flush()
                    val reply = tempReader.readLine()
                    val (accepted, status) = interpretHandshakeReply(reply)
                    if (!accepted) {
                        errMsg = "Handshake failed: ${reply ?: "no reply"}"
                        throw java.lang.Exception(errMsg)
                    }
                    createdTemporary = true
                    // send START_SCAN on temporary
                    tempWriter.write("START_SCAN")
                    tempWriter.newLine()
                    tempWriter.flush()
                } else {
                    // Use existing connection: ensure handshake was OK earlier then send START_SCAN
                    // synchronize writes to the shared socket to avoid races
                    synchronized(piSocket!!) {
                        writer.write("START_SCAN")
                        writer.newLine()
                        writer.flush()
                    }
                }

                // read lines until STATE: prefix (from tempReader/tempWriter if created, otherwise from piReader/piWriter)
                val readFrom = if (createdTemporary) tempReader else reader
                Log.d("MainActivity", "Waiting for STATE from Pi (reading from ${if (createdTemporary) "temp" else "persistent"} connection)")
                if (readFrom == null) throw java.lang.Exception("No reader available")
                while (true) {
                    val line = readFrom.readLine() ?: break
                    if (line.trim().isEmpty()) continue
                    Log.d("MainActivity", "Received line from Pi: $line")
                    if (line.startsWith("READY")) {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Pi ready, scanning...", Toast.LENGTH_SHORT).show() }
                        continue
                    }
                    if (line.startsWith("STATE:")) {
                        val payload = line.removePrefix("STATE:").trim()
                        // Handle expected 54-char payload, but also handle server-side fallback like "STATE:FAILED".
                        if (payload.equals("FAILED", ignoreCase = true) || payload.length != 54) {
                            // Collect remaining diagnostic lines (if any) to show a helpful error message.
                            val sb = StringBuilder()
                            if (payload.isNotEmpty()) {
                                sb.append(payload).append("\n")
                            }
                            // Read any subsequent lines the server may have sent (logs / error details)
                            try {
                                while (true) {
                                    val extra = readFrom.readLine() ?: break
                                    if (extra.trim().isEmpty()) continue
                                    sb.append(extra).append("\n")
                                }
                            } catch (_: Exception) {
                                // stop collecting on read errors/timeouts
                            }
                            errMsg = "Scan failed: ${sb.toString().trim()}"
                        } else {
                            stateStr = payload
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "SCAN error", e)
                errMsg = e.localizedMessage ?: e.toString()
            } finally {
                if (createdTemporary) {
                    try { tempSocket?.close() } catch (_: Exception) {}
                }
            }

            runOnUiThread {
                if (errMsg != null) {
                    // Show error in a dialog
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Scan error")
                        .setMessage(errMsg)
                        .setPositiveButton("OK", null)
                        .show()
                } else if (stateStr != null) {
                    // Parse and display the state in the cube view
                    // TODO: implement state parsing and cube view updating
                    Toast.makeText(this@MainActivity, "Scan completed: STATE=${stateStr?.substring(0, 20)}...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun interpretHandshakeReply(reply: String?): Pair<Boolean, String?> {
        if (reply == null) return Pair(false, null)
        val r = reply.trim().uppercase()
        return when {
            r.contains("RPI_ACK") || r.contains("ACK") -> Pair(true, "ok")
            r.contains("RUNNING_CUBE_SOLVER") || r.contains("RUNNING") -> Pair(true, "running")
            else -> Pair(false, r)
        }
    }

    private fun disconnectPi() {
        try {
            piSocket?.close()
        } catch (_: Exception) {}
        piSocket = null
        piReader = null
        piWriter = null
    }

    private fun updateLockButtonTint() {
        val tint = if (isLocked) Color.GRAY else Color.WHITE
        btnLock?.setTextColor(tint)
        btnLock?.iconTint = android.content.res.ColorStateList.valueOf(tint)
    }
}
