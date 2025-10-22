package com.example.rubikscubeapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.rubikscubeapp.gl.CubeGLSurfaceView
import java.io.File

class MainActivity : AppCompatActivity() {
    private var cubeView: CubeGLSurfaceView? = null

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
            val intent = Intent(this, DeviceListActivity::class.java)
            selectDeviceLauncher.launch(intent)
        }

        cubeView = findViewById(R.id.cubeBackground)

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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        cubeView?.onResume()
    }

    override fun onPause() {
        cubeView?.onPause()
        super.onPause()
    }
}