package com.example.rubikscubeapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ScanCubeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_cube)

        // Back button uses the same MaterialButton style/behavior as DeviceListActivity
        findViewById<MaterialButton>(R.id.Back)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // The rest of the controls are disabled per spec; wire up scan button placeholder
        findViewById<MaterialButton>(R.id.scanCube)?.setOnClickListener {
            // TODO: Implement scanning flow
        }
    }
}

