package com.example.rubikscubeapp

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.rubikscubeapp.gl.CubeGLSurfaceView
import com.google.android.material.button.MaterialButton

class SolverActivity : AppCompatActivity() {

    private var cubeView: CubeGLSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_solver)

        cubeView = findViewById(R.id.cubeSolverBackground)

        val toggleRotationButton: MaterialButton = findViewById(R.id.toggleSolverRotationButton)
        toggleRotationButton.setOnClickListener {
            cubeView?.toggleAutoRotation()
            toggleRotationButton.text = if (cubeView?.isAutoRotationEnabled == true) {
                "Stop Rotation"
            } else {
                "Start Rotation"
            }
        }

        findViewById<Button>(R.id.scramble_button).setOnClickListener {
            cubeView?.scramble()
        }

        findViewById<RadioButton>(R.id.slowButton).setOnClickListener {
            // Placeholder for slow animation
            Toast.makeText(this, "Slow speed selected", Toast.LENGTH_SHORT).show()
            cubeView?.solve("") // Placeholder
        }

        findViewById<RadioButton>(R.id.mediumButton).setOnClickListener {
            // Placeholder for medium animation
            Toast.makeText(this, "Medium speed selected", Toast.LENGTH_SHORT).show()
            cubeView?.solve("") // Placeholder
        }

        findViewById<RadioButton>(R.id.fastButton).setOnClickListener {
            // Placeholder for fast animation
            Toast.makeText(this, "Fast speed selected", Toast.LENGTH_SHORT).show()
            cubeView?.solve("") // Placeholder
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.solver_main)) { v, insets ->
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
        super.onPause()
        cubeView?.onPause()
    }
}
