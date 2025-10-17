package com.example.rubikscubeapp.gl

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import kotlin.math.abs
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CubeGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: CubeRenderer

    init {
        setEGLContextClientVersion(2)
        renderer = CubeRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private class CubeRenderer : Renderer {
        private val mvpMatrix = FloatArray(16)
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val rotationMatrix = FloatArray(16)
        private var angle = 0f

        private lateinit var cube: Cube

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.05f, 0.05f, 0.08f, 1.0f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            cube = Cube()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio: Float = width.toFloat() / height
            Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 2f, 10f)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 4.5f, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
            Matrix.setRotateM(rotationMatrix, 0, angle, 1f, 1.2f, 0.8f)
            Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, rotationMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

            cube.draw(mvpMatrix)

            // Update angle; keep it within range
            angle += 0.6f
            if (abs(angle) > 3600f) angle = angle % 360f
        }
    }
}

private class Cube {
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        attribute vec4 aColor;
        varying vec4 vColor;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            vColor = aColor;
        }
    """

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """

    // 8 vertices of a cube
    private val cubeCoords = floatArrayOf(
        -1f, 1f, 1f,   // 0: left-top-front
        -1f, -1f, 1f,  // 1: left-bottom-front
         1f, -1f, 1f,  // 2: right-bottom-front
         1f, 1f, 1f,   // 3: right-top-front
        -1f, 1f, -1f,  // 4: left-top-back
        -1f, -1f, -1f, // 5: left-bottom-back
         1f, -1f, -1f, // 6: right-bottom-back
         1f, 1f, -1f   // 7: right-top-back
    )

    // Each face has 2 triangles = 6 indices. 6 faces
    private val drawOrder = shortArrayOf(
        // Front (red)
        0,1,2, 0,2,3,
        // Right (green)
        3,2,6, 3,6,7,
        // Back (blue)
        7,6,5, 7,5,4,
        // Left (yellow)
        4,5,1, 4,1,0,
        // Top (orange)
        4,0,3, 4,3,7,
        // Bottom (white)
        1,5,6, 1,6,2
    )

    // Color per face (RGBA)
    private val faceColors = arrayOf(
        floatArrayOf(0.9f, 0.1f, 0.1f, 1f), // front - red
        floatArrayOf(0.1f, 0.7f, 0.2f, 1f), // right - green
        floatArrayOf(0.1f, 0.3f, 0.9f, 1f), // back - blue
        floatArrayOf(0.95f, 0.85f, 0.1f, 1f), // left - yellow
        floatArrayOf(1.0f, 0.5f, 0.0f, 1f), // top - orange
        floatArrayOf(0.95f, 0.95f, 0.95f, 1f) // bottom - white
    )

    private val vertexBuffer = java.nio.ByteBuffer.allocateDirect(cubeCoords.size * 4)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(cubeCoords)
            position(0)
        }

    private val drawListBuffer = java.nio.ByteBuffer.allocateDirect(drawOrder.size * 2)
        .order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(drawOrder)
            position(0)
        }

    // Expand face colors so each vertex of each triangle has a color
    private val colorBuffer = run {
        val colors = FloatArray(drawOrder.size * 4)
        var ci = 0
        for (face in 0 until 6) {
            val c = faceColors[face]
            // 6 indices per face = 6 vertices used
            repeat(6) {
                colors[ci++] = c[0]
                colors[ci++] = c[1]
                colors[ci++] = c[2]
                colors[ci++] = c[3]
            }
        }
        java.nio.ByteBuffer.allocateDirect(colors.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(colors)
                position(0)
            }
    }

    private val program: Int

    init {
        val vertexShader: Int = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader: Int = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vertexShader)
            GLES20.glAttachShader(prog, fragmentShader)
            GLES20.glLinkProgram(prog)
        }
    }

    fun draw(mvpMatrix: FloatArray) {
        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        val colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            3 * 4,
            vertexBuffer
        )

        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(
            colorHandle,
            4,
            GLES20.GL_FLOAT,
            false,
            4 * 4,
            colorBuffer
        )

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES,
            drawOrder.size,
            GLES20.GL_UNSIGNED_SHORT,
            drawListBuffer
        )

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}

