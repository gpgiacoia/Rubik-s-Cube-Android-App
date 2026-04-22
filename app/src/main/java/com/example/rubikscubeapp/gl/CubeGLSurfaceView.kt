package com.example.rubikscubeapp.gl

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import kotlin.math.abs
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CubeGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: RubiksRenderer
    private var isLocked = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)

        renderer = RubiksRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * Loads the cube state from a File. This reads the file on the caller thread
     * and forwards the contents to the GL thread via loadCubeFromCsvString.
     */
    fun loadCubeFromCsvFile(file: File) {
        try {
            if (file.exists()) {
                val text = file.readText()
                loadCubeFromCsvString(text)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadCubeFromCsvString(csv: String) {
        queueEvent {
            renderer.applyCubeStateCsv(csv)
        }
        requestRender()
    }
    private fun reorderForRenderer(state: String): String {
        val U = state.substring(0, 9)
        val L = state.substring(9, 18)
        val F = state.substring(18, 27)
        val R = state.substring(27, 36)
        val B = state.substring(36, 45)
        val D = state.substring(45, 54)

        // 🔥 CRITICAL FIX: swap L and F
        return U + F + L + R + B + D
    }
    /**
     * Loads the cube state from a raw 54-character string (e.g., "RBBRBBWBBGRR...")
     */
    fun loadCubeFromRawString(state: String) {
        val corrected = reorderForRenderer(state)

        queueEvent {
            renderer.applyRawStateString(corrected)
        }
        requestRender()
    }

    fun setSolidBlue() {
        queueEvent {
            renderer.setSolidColor(5)
        }
        requestRender()
    }

    fun setCubeLocked(isLocked: Boolean) {
        this.isLocked = isLocked
        queueEvent {
            renderer.setLockedIsometric(isLocked)
        }
        requestRender()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isLocked) return false

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = ev.x
                lastTouchY = ev.y
                performClick()
                queueEvent { renderer.startUserInteraction() }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val x = ev.x
                val y = ev.y
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                lastTouchX = x
                lastTouchY = y
                queueEvent { renderer.applyUserDelta(dx, dy) }
                requestRender()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                queueEvent { renderer.endUserInteraction() }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private class RubiksRenderer : Renderer {
        private val mvpMatrix = FloatArray(16)
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val rotationMatrix = FloatArray(16)
        private var angle = 0f

        private lateinit var cube: RubiksCube
        private var pendingCsv: String? = null
        private var pendingRaw: String? = null

        private var locked = false
        private val isoAngleX = 35.264f
        private val isoAngleY = 45f

        private var userControlled = false
        private var userYaw = 0f
        private var userPitch = 0f
        private val sensitivity = 0.4f

        fun applyRawStateString(state: String): Boolean {
            return if (!::cube.isInitialized) {
                pendingRaw = state
                false
            } else {
                cube.setCubeFromRawString(state)
            }
        }

        fun applyCubeStateCsv(csv: String): Boolean {
            return if (!::cube.isInitialized) {
                pendingCsv = csv
                false
            } else {
                cube.setCubeFromCsvString(csv)
            }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            cube = RubiksCube()
            
            pendingCsv?.let { csv ->
                cube.setCubeFromCsvString(csv)
                pendingCsv = null
            }
            pendingRaw?.let { raw ->
                cube.setCubeFromRawString(raw)
                pendingRaw = null
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio: Float = width.toFloat() / height
            Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 2f, 10f)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 4.8f, 0f, 0f, 0f, 0f, 1.0f, 0.0f)

            if (locked) {
                if (userControlled) {
                    val rotY = FloatArray(16)
                    val rotX = FloatArray(16)
                    Matrix.setRotateM(rotY, 0, userYaw, 0f, 1f, 0f)
                    Matrix.setRotateM(rotX, 0, userPitch, 1f, 0f, 0f)
                    Matrix.multiplyMM(rotationMatrix, 0, rotX, 0, rotY, 0)
                } else {
                    val rotY = FloatArray(16)
                    val rotX = FloatArray(16)
                    Matrix.setRotateM(rotY, 0, isoAngleY, 0f, 1f, 0f)
                    Matrix.setRotateM(rotX, 0, isoAngleX, 1f, 0f, 0f)
                    Matrix.multiplyMM(rotationMatrix, 0, rotX, 0, rotY, 0)
                }
            } else {
                Matrix.setRotateM(rotationMatrix, 0, angle, 1f, 1.1f, 0.9f)
            }

            Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, rotationMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

            cube.draw(mvpMatrix)

            if (!locked) {
                angle += 0.5f
                if (abs(angle) > 3600f) angle = angle % 360f
            }
        }

        fun setSolidColor(colorIndex: Int) {
            val line = (0 until 9).joinToString(",") { colorIndex.toString() }
            val csv = buildString { repeat(6) { appendLine(line) } }
            if (!::cube.isInitialized) pendingCsv = csv
            else cube.setCubeFromCsvString(csv)
        }

        fun setLockedIsometric(isLocked: Boolean) {
            locked = isLocked
            if (!locked) {
                userControlled = false
                userYaw = 0f
                userPitch = 0f
                angle = 0f
            } else {
                userControlled = false
            }
        }

        fun startUserInteraction() { userControlled = true }
        fun applyUserDelta(dx: Float, dy: Float) {
            userYaw += dx * sensitivity
            userPitch += dy * sensitivity
            if (userPitch > 80f) userPitch = 80f
            if (userPitch < -80f) userPitch = -80f
        }
        fun endUserInteraction() {}
    }
}

private class RubiksCube {
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

    // 0=white, 1=yellow, 2=green, 3=red, 4=orange, 5=blue
    private val INT_TO_COLOR = arrayOf(
        floatArrayOf(0.98f, 0.98f, 0.98f, 1f), // 0 white
        floatArrayOf(0.98f, 0.86f, 0.05f, 1f), // 1 yellow
        floatArrayOf(0.13f, 0.66f, 0.20f, 1f), // 2 green (front)
        floatArrayOf(0.85f, 0.10f, 0.10f, 1f), // 3 red (right)
        floatArrayOf(1.0f, 0.50f, 0.0f, 1f),   // 4 orange (left)
        floatArrayOf(0.12f, 0.35f, 0.96f, 1f)  // 5 blue (back)
    )

    private var cubeState: Array<IntArray> = Array(6) { IntArray(9) }
    private val baseCube: Mesh
    private var stickers: Mesh
    private val program: Int

    init {
        initDefaultState()
        baseCube = buildBaseCube()
        stickers = buildStickers()

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vertexShader)
            GLES20.glAttachShader(prog, fragmentShader)
            GLES20.glLinkProgram(prog)
        }
    }

    private fun initDefaultState() {
        val faceDefaults = intArrayOf(2, 3, 5, 4, 0, 1) // FRONT, RIGHT, BACK, LEFT, UP, DOWN
        for (f in 0..5) {
            for (i in 0..8) {
                cubeState[f][i] = faceDefaults[f]
            }
        }
    }

    /**
     * Parses a 54-character string directly into the 6x9 cube state.
     * Maps Pi/Kociemba order (U, R, F, D, L, B) or (U, L, F, R, B, D) to 
     * our internal order (FRONT, RIGHT, BACK, LEFT, UP, DOWN).
     *
     * Input order provided in prompt (assumed U, R, F, D, L, B based on common 54-char layouts):
     * RBBRBBWBB (U)
     * GRRGRRGWW (R)
     * WWOWWOWWO (F)
     * YYYYYYRRR (D)
     * OOBOOBYYB (L)
     * YGGOGGOGG (B)
     */
    fun setCubeFromRawString(state: String): Boolean {
        val cleanState = state.replace(Regex("[^A-Z]"), "")
        if (cleanState.length != 54) return false

        // Extract 9-char chunks
        val u = cleanState.substring(0, 9)
        val r = cleanState.substring(9, 18)
        val f = cleanState.substring(18, 27)
        val d = cleanState.substring(27, 36)
        val l = cleanState.substring(36, 45)
        val b = cleanState.substring(45, 54)

        // Internal order: 0:FRONT, 1:RIGHT, 2:BACK, 3:LEFT, 4:UP, 5:DOWN
        val faces = listOf(f, r, b, l, u, d)
        
        val newState = Array(6) { IntArray(9) }
        for (i in 0 until 6) {
            val faceData = faces[i]
            for (j in 0 until 9) {
                newState[i][j] = when (faceData[j]) {
                    'W' -> 0 // White
                    'Y' -> 1 // Yellow
                    'G' -> 2 // Green
                    'R' -> 3 // Red
                    'O' -> 4 // Orange
                    'B' -> 5 // Blue
                    else -> 0
                }
            }
        }

        cubeState = newState
        stickers = buildStickers()
        return true
    }

    fun draw(mvpMatrix: FloatArray) {
        GLES20.glUseProgram(program)
        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        val colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        baseCube.bind(positionHandle, colorHandle)
        baseCube.draw()
        baseCube.unbind(positionHandle, colorHandle)

        stickers.bind(positionHandle, colorHandle)
        stickers.draw()
        stickers.unbind(positionHandle, colorHandle)
    }

    private fun buildBaseCube(): Mesh {
        val s = 1.0f
        val vertices = floatArrayOf(
            -s,s,s, -s,-s,s, s,-s,s, s,s,s, // Front
            s,s,s, s,-s,s, s,-s,-s, s,s,-s, // Right
            s,s,-s, s,-s,-s, -s,-s,-s, -s,s,-s, // Back
            -s,s,-s, -s,-s,-s, -s,-s,s, -s,s,s, // Left
            -s,s,-s, -s,s,s, s,s,s, s,s,-s, // Up
            -s,-s,s, -s,-s,-s, s,-s,-s, s,-s,s  // Down
        )
        val indices = shortArrayOf(
            0,1,2, 0,2,3, 4,5,6, 4,6,7, 8,9,10, 8,10,11,
            12,13,14, 12,14,15, 16,17,18, 16,18,19, 20,21,22, 20,22,23
        )
        val black = floatArrayOf(0.02f, 0.02f, 0.02f, 1f)
        val colors = FloatArray((vertices.size / 3) * 4)
        for (i in 0 until (vertices.size/3)) {
            System.arraycopy(black, 0, colors, i*4, 4)
        }
        return Mesh(vertices, colors, indices)
    }

    private fun buildStickers(): Mesh {
        val gapInCell = 0.04f
        val depth = 1.001f
        val verts = ArrayList<Float>()
        val cols = ArrayList<Float>()
        val idx = ArrayList<Short>()
        var vi: Short = 0

        fun addStickerQuad(face: Face, x0: Float, y0: Float, x1: Float, y1: Float, colorArr: FloatArray) {
            val pts = arrayOf(
                face.mapTo3D(x0, y0, depth), face.mapTo3D(x0, y1, depth),
                face.mapTo3D(x1, y1, depth), face.mapTo3D(x1, y0, depth)
            )
            for (p in pts) {
                verts.add(p[0]); verts.add(p[1]); verts.add(p[2])
                cols.add(colorArr[0]); cols.add(colorArr[1]); cols.add(colorArr[2]); cols.add(colorArr[3])
            }
            idx.add(vi); idx.add((vi+1).toShort()); idx.add((vi+2).toShort())
            idx.add(vi); idx.add((vi+2).toShort()); idx.add((vi+3).toShort())
            vi = (vi + 4).toShort()
        }

        for ((faceIndex, face) in Face.entries.withIndex()) {
            for (iy in 0 until 3) { // iy=0 is bottom row in GL
                for (ix in 0 until 3) {
                    val x0 = -1f + (2f/3f)*ix + gapInCell
                    val x1 = -1f + (2f/3f)*(ix+1) - gapInCell
                    val y0 = -1f + (2f/3f)*iy + gapInCell
                    val y1 = -1f + (2f/3f)*(iy+1) - gapInCell

                    // Mapping: indices 0..8 represent top-to-bottom, left-to-right.
                    // GL iy=0 (Bottom) should take index 6,7,8
                    // GL iy=2 (Top) should take index 0,1,2
                    val stickerIndex = (2 - iy) * 3 + ix

                    val colorIndex = cubeState[faceIndex][stickerIndex]
                    val colorArr = if (colorIndex in 0..5) INT_TO_COLOR[colorIndex] else INT_TO_COLOR[0]
                    addStickerQuad(face, x0, y0, x1, y1, colorArr)
                }
            }
        }
        return Mesh(verts.toFloatArray(), cols.toFloatArray(), idx.toShortArray())
    }

    fun setCubeFromCsvString(csv: String): Boolean {
        val lines = csv.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size != 6) return false
        val newState = Array(6) { IntArray(9) }
        for (i in 0 until 6) {
            val parts = lines[i].split(',').mapNotNull { it.trim().toIntOrNull() }
            if (parts.size != 9) return false
            newState[i] = parts.toIntArray()
        }
        cubeState = newState
        stickers = buildStickers()
        return true
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }

    private enum class Face { FRONT, RIGHT, BACK, LEFT, UP, DOWN }
    private class Mesh(vertices: FloatArray, colors: FloatArray, indices: ShortArray) {
        private val vertexBuffer = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }
        private val colorBuffer = java.nio.ByteBuffer.allocateDirect(colors.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(colors); position(0) }
        private val indexBuffer = java.nio.ByteBuffer.allocateDirect(indices.size * 2)
            .order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices); position(0) }
        private val indexCount = indices.size

        fun bind(positionHandle: Int, colorHandle: Int) {
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(colorHandle)
            GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, colorBuffer)
        }
        fun draw() { GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer) }
        fun unbind(positionHandle: Int, colorHandle: Int) {
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(colorHandle)
        }
    }

    private fun Face.mapTo3D(u: Float, v: Float, depth: Float): FloatArray {
        return when (this) {
            Face.FRONT -> floatArrayOf(u, v, depth)
            Face.BACK  -> floatArrayOf(-u, v, -depth)
            Face.RIGHT -> floatArrayOf(depth, v, -u)
            Face.LEFT  -> floatArrayOf(-depth, v, u)
            Face.UP    -> floatArrayOf(u, depth, -v)
            Face.DOWN  -> floatArrayOf(u, -depth, v)
        }
    }
}