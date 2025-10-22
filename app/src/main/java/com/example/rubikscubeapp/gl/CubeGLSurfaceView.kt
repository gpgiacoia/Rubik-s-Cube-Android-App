package com.example.rubikscubeapp.gl

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import kotlin.math.abs
import java.io.File
import java.io.Reader
import java.io.BufferedReader
import java.io.StringReader
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CubeGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: RubiksRenderer

    init {
        setEGLContextClientVersion(2)
        // Request RGBA8888 so we have an alpha channel
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        // Make the SurfaceView translucent so clear alpha shows through
        holder.setFormat(PixelFormat.TRANSLUCENT)
        // Place the surface on top so transparency reveals UI beneath
        setZOrderOnTop(true)

        renderer = RubiksRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    // Public helper: load cube state from CSV string. This queues the update on the GL thread
    // and requests a render. CSV format: 6 non-empty rows, each with 9 integers 0..5, comma-separated.
    fun loadCubeFromCsvString(csv: String) {
        queueEvent {
            renderer.applyCubeStateCsv(csv)
        }
        requestRender()
    }

    // Public helper: load cube state from a File. This reads the file on the caller thread
    // and forwards the contents to the GL thread.
    @Suppress("unused")
    fun loadCubeFromCsvFile(file: File) {
        val text = try { file.readText() } catch (_: Exception) { return }
        loadCubeFromCsvString(text)
    }

    private class RubiksRenderer : Renderer {
        private val mvpMatrix = FloatArray(16)
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val rotationMatrix = FloatArray(16)
        private var angle = 0f

        private lateinit var cube: RubiksCube
        private var pendingCsv: String? = null

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Fully transparent clear color (no background)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            cube = RubiksCube()
            // Apply any pending CSV once the cube is ready
            pendingCsv?.let { csv ->
                cube.setCubeFromCsvString(csv)
                pendingCsv = null
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
            Matrix.setRotateM(rotationMatrix, 0, angle, 1f, 1.1f, 0.9f)
            Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, rotationMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)

            cube.draw(mvpMatrix)

            angle += 0.5f
            if (abs(angle) > 3600f) angle = angle % 360f
        }

        // Called on GL thread to apply a CSV string to the cube state
        fun applyCubeStateCsv(csv: String): Boolean {
            return if (!::cube.isInitialized) {
                pendingCsv = csv
                false
            } else {
                cube.setCubeFromCsvString(csv)
            }
        }
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

    // Integer-to-color mapping requested by user:
    // 0=white, 1=yellow, 2=green, 3=red, 4=orange, 5=blue
    private val INT_TO_COLOR = arrayOf(
        floatArrayOf(0.98f, 0.98f, 0.98f, 1f), // 0 white
        floatArrayOf(0.98f, 0.86f, 0.05f, 1f), // 1 yellow
        floatArrayOf(0.13f, 0.66f, 0.20f, 1f), // 2 green (front)
        floatArrayOf(0.85f, 0.10f, 0.10f, 1f), // 3 red (right)
        floatArrayOf(1.0f, 0.50f, 0.0f, 1f),   // 4 orange (left)
        floatArrayOf(0.12f, 0.35f, 0.96f, 1f)  // 5 blue (back)
    )

    // Cube configuration as a 6x9 int array: row per face, 9 stickers per face.
    // Face row order corresponds to the Face enum declaration order: FRONT, RIGHT, BACK, LEFT, UP, DOWN
    private var cubeState: Array<IntArray> = Array(6) { IntArray(9) }

    // Vertex/index/color buffers
    private val baseCube: Mesh
    private var stickers: Mesh

    private val program: Int

    init {
        initDefaultState()

        baseCube = buildBaseCube()
        stickers = buildStickers()

        val vertexShader: Int = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader: Int = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vertexShader)
            GLES20.glAttachShader(prog, fragmentShader)
            GLES20.glLinkProgram(prog)
        }
    }

    private fun initDefaultState() {
        // Fill each face row with the solved color index.
        // Using mapping: FRONT->2, RIGHT->3, BACK->5, LEFT->4, UP->0, DOWN->1
        val faceDefaults = intArrayOf(2, 3, 5, 4, 0, 1)
        for (f in 0..5) {
            for (i in 0..8) {
                cubeState[f][i] = faceDefaults[f]
            }
        }
    }

    fun draw(mvpMatrix: FloatArray) {
        GLES20.glUseProgram(program)
        val positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        val colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // Draw base cube (black body)
        baseCube.bind(positionHandle, colorHandle)
        baseCube.draw()
        baseCube.unbind(positionHandle, colorHandle)

        // Draw stickers on top
        stickers.bind(positionHandle, colorHandle)
        stickers.draw()
        stickers.unbind(positionHandle, colorHandle)
    }

    private fun buildBaseCube(): Mesh {
        // A cube centered at origin, size slightly smaller than stickers' plane depth to avoid z-fighting
        val s = 1.0f
        val vertices = floatArrayOf(
            // Front (+Z)
            -s,  s,  s,
            -s, -s,  s,
             s, -s,  s,
             s,  s,  s,
            // Right (+X)
             s,  s,  s,
             s, -s,  s,
             s, -s, -s,
             s,  s, -s,
            // Back (-Z)
             s,  s, -s,
             s, -s, -s,
            -s, -s, -s,
            -s,  s, -s,
            // Left (-X)
            -s,  s, -s,
            -s, -s, -s,
            -s, -s,  s,
            -s,  s,  s,
            // Up (+Y)
            -s,  s, -s,
            -s,  s,  s,
             s,  s,  s,
             s,  s, -s,
            // Down (-Y)
            -s, -s,  s,
            -s, -s, -s,
             s, -s, -s,
             s, -s,  s
        )
        val indices = shortArrayOf(
            0,1,2, 0,2,3,      // Front
            4,5,6, 4,6,7,      // Right
            8,9,10, 8,10,11,   // Back
            12,13,14, 12,14,15,// Left
            16,17,18, 16,18,19,// Up
            20,21,22, 20,22,23 // Down
        )
        val black = floatArrayOf(0.02f, 0.02f, 0.02f, 1f)
        val colors = FloatArray((vertices.size / 3) * 4) { 0f }
        var ci = 0
        repeat(vertices.size / 3) {
            colors[ci++] = black[0]
            colors[ci++] = black[1]
            colors[ci++] = black[2]
            colors[ci++] = black[3]
        }
        return Mesh(vertices, colors, indices)
    }

    private fun buildStickers(): Mesh {
        // Build 6 faces * 3x3 stickers, each sticker is a quad (two triangles)
        val gapInCell = 0.04f // gap inside each 1/3rd cell
        val depth = 1.001f // small offset from base cube to avoid z-fighting

        val verts = ArrayList<Float>()
        val cols = ArrayList<Float>()
        val idx = ArrayList<Short>()
        var vi: Short = 0

        fun addStickerQuad(face: Face, x0: Float, y0: Float, x1: Float, y1: Float, colorArr: FloatArray) {
            // Map face-local (u,v) in [-1,1] to world xyz on each face
            val p0 = face.mapTo3D(x0, y0, depth)
            val p1 = face.mapTo3D(x0, y1, depth)
            val p2 = face.mapTo3D(x1, y1, depth)
            val p3 = face.mapTo3D(x1, y0, depth)

            // Add 4 vertices with color
            fun addVertex(p: FloatArray) {
                verts.add(p[0]); verts.add(p[1]); verts.add(p[2])
                cols.add(colorArr[0]); cols.add(colorArr[1]); cols.add(colorArr[2]); cols.add(colorArr[3])
            }
            addVertex(p0)
            addVertex(p1)
            addVertex(p2)
            addVertex(p3)

            // Two triangles
            idx.add(vi); idx.add((vi+1).toShort()); idx.add((vi+2).toShort())
            idx.add(vi); idx.add((vi+2).toShort()); idx.add((vi+3).toShort())
            vi = (vi + 4).toShort()
        }

        // For each face create 3x3 grid. Face row order corresponds to cubeState rows.
        for ((faceIndex, face) in Face.entries.withIndex()) {
            for (iy in 0 until 3) {
                for (ix in 0 until 3) {
                    val cellMinX = -1f + (2f/3f)*ix
                    val cellMaxX = -1f + (2f/3f)*(ix+1)
                    val cellMinY = -1f + (2f/3f)*iy
                    val cellMaxY = -1f + (2f/3f)*(iy+1)
                    val x0 = cellMinX + gapInCell
                    val x1 = cellMaxX - gapInCell
                    val y0 = cellMinY + gapInCell
                    val y1 = cellMaxY - gapInCell

                    val stickerIndex = iy*3 + ix
                    val colorIndex = cubeState.getOrNull(faceIndex)?.getOrNull(stickerIndex) ?: 0
                    val colorArr = if (colorIndex in 0..5) INT_TO_COLOR[colorIndex] else INT_TO_COLOR[0]

                    addStickerQuad(face, x0, y0, x1, y1, colorArr)
                }
            }
        }

        val vArr = verts.toFloatArray()
        val cArr = cols.toFloatArray()
        val iArr = ShortArray(idx.size) { idx[it] }
        return Mesh(vArr, cArr, iArr)
    }

    // CSV loaders. The CSV should have exactly 6 non-empty rows, each with 9 comma-separated integers 0..5.
    // Row order used here matches the Face enum order: FRONT, RIGHT, BACK, LEFT, UP, DOWN.
    fun setCubeFromCsvString(csv: String): Boolean {
        val reader: Reader = StringReader(csv)
        val br = BufferedReader(reader)
        val lines = ArrayList<String>()
        br.useLines { seq ->
            seq.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) lines.add(trimmed)
            }
        }
        if (lines.size != 6) return false

        val newState = Array(6) { IntArray(9) }
        for (i in 0 until 6) {
            val parts = lines[i].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size != 9) return false
            for (j in 0 until 9) {
                val num = parts[j].toIntOrNull() ?: return false
                if (num !in 0..5) return false
                newState[i][j] = num
            }
        }

        // Accept and rebuild stickers
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
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(vertices); position(0)
            }
        private val colorBuffer = java.nio.ByteBuffer.allocateDirect(colors.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(colors); position(0)
            }
        private val indexBuffer = java.nio.ByteBuffer.allocateDirect(indices.size * 2)
            .order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().apply {
                put(indices); position(0)
            }
        private val indexCount = indices.size

        fun bind(positionHandle: Int, colorHandle: Int) {
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 3*4, vertexBuffer)
            GLES20.glEnableVertexAttribArray(colorHandle)
            GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 4*4, colorBuffer)
        }
        fun draw() {
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        }
        fun unbind(positionHandle: Int, colorHandle: Int) {
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(colorHandle)
        }
    }

    private fun Face.mapTo3D(u: Float, v: Float, depth: Float): FloatArray {
        // u,v in [-1,1] on face plane. depth is slightly > 1 to avoid z-fighting.
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
