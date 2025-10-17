package com.example.rubikscubeapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.rubikscubeapp.DeviceListActivity
import com.example.rubikscubeapp.gl.CubeGLSurfaceView

class MainActivity : AppCompatActivity() {
    private var cubeView: CubeGLSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        //find button in view
        val btn: Button = findViewById(R.id.Start)
        btn.setOnClickListener {
            startActivity(Intent(this, DeviceListActivity::class.java))
        }

        cubeView = findViewById(R.id.cubeBackground)

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