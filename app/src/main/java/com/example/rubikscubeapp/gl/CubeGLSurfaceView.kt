package com.example.rubikscubeapp.gl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import java.io.File
import java.io.Reader
import java.io.BufferedReader
import java.io.StringReader
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import org.worldcubeassociation.tnoodle.scrambles.PuzzleRegistry

private const val TOUCH_SCALE_FACTOR: Float = 180.0f / 320f

class CubeGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: RubiksRenderer

    private var previousX: Float = 0f
    private var previousY: Float = 0f
    var isAutoRotationEnabled: Boolean = true

    init {
        setEGLContextClientVersion(2)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setEGLConfigChooser(ComponentSizeChooser(8, 8, 8, 8, 16, 0))
        setZOrderOnTop(true)

        renderer = RubiksRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun performMove(move: String) {
        queueEvent {
            renderer.performMove(move)
        }
        requestRender()
    }

    fun scramble() {
        queueEvent {
            renderer.scramble()
        }
        requestRender()
    }

    fun solve(solution: String) {
        queueEvent {
            renderer.solve(solution)
        }
        requestRender()
    }

    fun toggleAutoRotation() {
        isAutoRotationEnabled = !isAutoRotationEnabled
        renderer.setAutoRotation(isAutoRotationEnabled)
        renderMode = if (isAutoRotationEnabled) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (isAutoRotationEnabled) {
            return super.onTouchEvent(e)
        }

        val x: Float = e.x
        val y: Float = e.y

        when (e.action) {
            MotionEvent.ACTION_MOVE -> {
                val dx = x - previousX
                val dy = y - previousY
                renderer.angleX += dx * TOUCH_SCALE_FACTOR
                renderer.angleY += dy * TOUCH_SCALE_FACTOR
                requestRender()
            }
        }

        previousX = x
        previousY = y
        return true
    }

    fun loadCubeFromCsvString(csv: String) {
        queueEvent {
            renderer.applyCubeStateCsv(csv)
        }
        requestRender()
    }

    fun loadCubeFromCsvFile(file: File) {
        val text = try { file.readText() } catch (_: Exception) { return }
        loadCubeFromCsvString(text)
    }

    private class RubiksRenderer : Renderer {
        private val mvpMatrix = FloatArray(16)
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val rotationMatrix = FloatArray(16)

        private var autoAngle = 0f
        var angleX = 0f
        var angleY = 0f
        var isAutoRotating = true

        private lateinit var cube: RubiksCube
        private var pendingCsv: String? = null

        fun setAutoRotation(enabled: Boolean) {
            isAutoRotating = enabled
        }

        fun performMove(move: String) {
            if (!::cube.isInitialized) return
            when (move) {
                "U" -> cube.rotate(Face.UP, clockwise = true)
                "U'" -> cube.rotate(Face.UP, clockwise = false)
                "D" -> cube.rotate(Face.DOWN, clockwise = true)
                "D'" -> cube.rotate(Face.DOWN, clockwise = false)
                "F" -> cube.rotate(Face.FRONT, clockwise = true)
                "F'" -> cube.rotate(Face.FRONT, clockwise = false)
                "B" -> cube.rotate(Face.BACK, clockwise = true)
                "B'" -> cube.rotate(Face.BACK, clockwise = false)
                "L" -> cube.rotate(Face.LEFT, clockwise = true)
                "L'" -> cube.rotate(Face.LEFT, clockwise = false)
                "R" -> cube.rotate(Face.RIGHT, clockwise = true)
                "R'" -> cube.rotate(Face.RIGHT, clockwise = false)
            }
        }

        fun scramble() {
            if (!::cube.isInitialized) return
            cube.scramble()
        }

        fun solve(solution: String) {
            if (!::cube.isInitialized) return
            // Placeholder for solver
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            cube = RubiksCube()
            pendingCsv?.let { cube.setCubeFromCsvString(it); pendingCsv = null }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio: Float = width.toFloat() / height
            Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 3f, 25f)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 14.0f, 0f, 0f, 0f, 0f, 1.0f, 0.0f)

            if (isAutoRotating) {
                Matrix.setRotateM(rotationMatrix, 0, autoAngle, 1f, 1.1f, 0.9f)
                autoAngle += 0.5f
                if (abs(autoAngle) > 3600f) autoAngle %= 360f
            } else {
                Matrix.setIdentityM(rotationMatrix, 0)
                Matrix.rotateM(rotationMatrix, 0, angleX, 0f, 1f, 0f)
                Matrix.rotateM(rotationMatrix, 0, angleY, 1f, 0f, 0f)
            }

            Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, rotationMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0)
            cube.draw(mvpMatrix)
        }

        fun applyCubeStateCsv(csv: String): Boolean {
            return if (!::cube.isInitialized) {
                pendingCsv = csv
                false
            } else {
                cube.setCubeFromCsvString(csv)
            }
        }
    }

    private open class ComponentSizeChooser(
        private val mRedSize: Int, private val mGreenSize: Int, private val mBlueSize: Int,
        private val mAlphaSize: Int, private val mDepthSize: Int, private val mStencilSize: Int
    ) : GLSurfaceView.EGLConfigChooser {

        private val mValue = IntArray(1)

        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig? {
            val attribs = intArrayOf(
                EGL10.EGL_RED_SIZE, mRedSize,
                EGL10.EGL_GREEN_SIZE, mGreenSize,
                EGL10.EGL_BLUE_SIZE, mBlueSize,
                EGL10.EGL_ALPHA_SIZE, mAlphaSize,
                EGL10.EGL_DEPTH_SIZE, mDepthSize,
                EGL10.EGL_STENCIL_SIZE, mStencilSize,
                EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL10.EGL_NONE
            )
            val num_config = IntArray(1)
            egl.eglChooseConfig(display, attribs, null, 0, num_config)
            val numConfigs = num_config[0]

            if (numConfigs <= 0) {
                throw IllegalArgumentException("No configs match configSpec")
            }

            val configs = arrayOfNulls<EGLConfig>(numConfigs)
            egl.eglChooseConfig(display, attribs, configs, numConfigs, num_config)

            return chooseConfig(egl, display, configs.filterNotNull().toTypedArray())
        }

        private fun chooseConfig(egl: EGL10, display: EGLDisplay, configs: Array<EGLConfig>): EGLConfig? {
            for (config in configs) {
                val d = findConfigAttrib(egl, display, config, EGL10.EGL_DEPTH_SIZE, 0)
                val s = findConfigAttrib(egl, display, config, EGL10.EGL_STENCIL_SIZE, 0)
                if (d >= mDepthSize && s >= mStencilSize) {
                    val r = findConfigAttrib(egl, display, config, EGL10.EGL_RED_SIZE, 0)
                    val g = findConfigAttrib(egl, display, config, EGL10.EGL_GREEN_SIZE, 0)
                    val b = findConfigAttrib(egl, display, config, EGL10.EGL_BLUE_SIZE, 0)
                    val a = findConfigAttrib(egl, display, config, EGL10.EGL_ALPHA_SIZE, 0)
                    if (r == mRedSize && g == mGreenSize && b == mBlueSize && a == mAlphaSize) {
                        return config
                    }
                }
            }
            return null
        }

        private fun findConfigAttrib(egl: EGL10, display: EGLDisplay, config: EGLConfig, attribute: Int, defaultValue: Int): Int {
            return if (egl.eglGetConfigAttrib(display, config, attribute, mValue)) {
                mValue[0]
            } else defaultValue
        }
    }
}

private enum class Face { FRONT, RIGHT, BACK, LEFT, UP, DOWN }

private class RubiksCube {
    private val vertexShaderCode = "uniform mat4 uMVPMatrix; attribute vec4 vPosition; attribute vec4 aColor; varying vec4 vColor; void main() { gl_Position = uMVPMatrix * vPosition; vColor = aColor; }"
    private val fragmentShaderCode = "precision mediump float; varying vec4 vColor; void main() { gl_FragColor = vColor; }"

    private val CHAR_TO_COLOR = mapOf(
        'w' to floatArrayOf(0.98f, 0.98f, 0.98f, 1f), 'y' to floatArrayOf(0.98f, 0.86f, 0.05f, 1f),
        'g' to floatArrayOf(0.13f, 0.66f, 0.20f, 1f), 'r' to floatArrayOf(0.85f, 0.10f, 0.10f, 1f),
        'o' to floatArrayOf(1.0f, 0.50f, 0.0f, 1f),   'b' to floatArrayOf(0.12f, 0.35f, 0.96f, 1f)
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
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    private fun initDefaultState() {
        val faceDefaults = charArrayOf('g', 'r', 'b', 'o', 'w', 'y')
        for (f in 0..5) {
            val colorIndex = charToColorIndex(faceDefaults[f])
            cubeState[f].fill(colorIndex)
        }
    }

    fun scramble() {
        // Reset cube to solved state first
        initDefaultState()
        stickers = buildStickers()

        // Generate official WCA scramble using TNoodle
        val puzzle = PuzzleRegistry.THREE
        val scrambleString = puzzle.scrambler.generateScramble()

        // Print to Android logcat AND console
        android.util.Log.d("RubiksCube", "=================================")
        android.util.Log.d("RubiksCube", "SCRAMBLE: $scrambleString")
        android.util.Log.d("RubiksCube", "=================================")
        println("SCRAMBLE: $scrambleString")

        // Apply each move from the scramble
        scrambleString.split(" ").forEach { move ->
            val trimmedMove = move.trim()
            if (trimmedMove.isNotEmpty()) {
                applyMove(trimmedMove)
            }
        }

        stickers = buildStickers()
    }

    private fun validateCubeState() {
        // Count each color - should have exactly 9 of each
        val colorCounts = IntArray(6)
        for (face in cubeState) {
            for (sticker in face) {
                if (sticker in 0..5) {
                    colorCounts[sticker]++
                }
            }
        }

        val colorNames = arrayOf("white", "yellow", "green", "red", "orange", "blue")
        for (i in colorCounts.indices) {
            if (colorCounts[i] != 9) {
                println("WARNING: Invalid cube state! ${colorNames[i]} has ${colorCounts[i]} stickers (should be 9)")
            }
        }
    }

    private fun applyMove(move: String) {
        when (move) {
            "U" -> rotate(Face.UP, true)
            "U'" -> rotate(Face.UP, false)
            "U2" -> {
                rotate(Face.UP, true)
                rotate(Face.UP, true)
            }
            "D" -> rotate(Face.DOWN, true)
            "D'" -> rotate(Face.DOWN, false)
            "D2" -> {
                rotate(Face.DOWN, true)
                rotate(Face.DOWN, true)
            }
            "F" -> rotate(Face.FRONT, true)
            "F'" -> rotate(Face.FRONT, false)
            "F2" -> {
                rotate(Face.FRONT, true)
                rotate(Face.FRONT, true)
            }
            "B" -> rotate(Face.BACK, true)
            "B'" -> rotate(Face.BACK, false)
            "B2" -> {
                rotate(Face.BACK, true)
                rotate(Face.BACK, true)
            }
            "L" -> rotate(Face.LEFT, true)
            "L'" -> rotate(Face.LEFT, false)
            "L2" -> {
                rotate(Face.LEFT, true)
                rotate(Face.LEFT, true)
            }
            "R" -> rotate(Face.RIGHT, true)
            "R'" -> rotate(Face.RIGHT, false)
            "R2" -> {
                rotate(Face.RIGHT, true)
                rotate(Face.RIGHT, true)
            }
        }
    }

    fun rotate(face: Face, clockwise: Boolean) {
        rotateFaceStickers(face, clockwise)
        rotateAdjacentStickers(face, clockwise)
        stickers = buildStickers()
    }

    // COMPLETE CORRECT ROTATION LOGIC - Replace both functions in your RubiksCube class

    private fun rotateFaceStickers(face: Face, clockwise: Boolean) {
        val faceState = cubeState[face.ordinal]
        val temp = faceState.clone()
        if (clockwise) {
            faceState[0] = temp[6]; faceState[1] = temp[3]; faceState[2] = temp[0]
            faceState[3] = temp[7]; faceState[4] = temp[4]; faceState[5] = temp[1]
            faceState[6] = temp[8]; faceState[7] = temp[5]; faceState[8] = temp[2]
        } else {
            faceState[0] = temp[2]; faceState[1] = temp[5]; faceState[2] = temp[8]
            faceState[3] = temp[1]; faceState[4] = temp[4]; faceState[5] = temp[7]
            faceState[6] = temp[0]; faceState[7] = temp[3]; faceState[8] = temp[6]
        }
    }

    private fun rotateAdjacentStickers(face: Face, clockwise: Boolean) {
        // Direct array access - Face enum: FRONT=0, RIGHT=1, BACK=2, LEFT=3, UP=4, DOWN=5
        val front = cubeState[0]
        val right = cubeState[1]
        val back = cubeState[2]
        val left = cubeState[3]
        val up = cubeState[4]
        val down = cubeState[5]

        when (face) {
            Face.FRONT -> {
                val temp = intArrayOf(up[6], up[7], up[8])
                if (clockwise) {
                    up[6] = left[8]; up[7] = left[5]; up[8] = left[2]
                    left[2] = down[0]; left[5] = down[1]; left[8] = down[2]
                    down[0] = right[6]; down[1] = right[3]; down[2] = right[0]
                    right[0] = temp[0]; right[3] = temp[1]; right[6] = temp[2]
                } else {
                    up[6] = right[0]; up[7] = right[3]; up[8] = right[6]
                    right[0] = down[2]; right[3] = down[1]; right[6] = down[0]
                    down[0] = left[2]; down[1] = left[5]; down[2] = left[8]
                    left[2] = temp[2]; left[5] = temp[1]; left[8] = temp[0]
                }
            }

            Face.BACK -> {
                val temp = intArrayOf(up[0], up[1], up[2])
                if (clockwise) {
                    up[0] = right[2]; up[1] = right[5]; up[2] = right[8]
                    right[2] = down[8]; right[5] = down[7]; right[8] = down[6]
                    down[6] = left[0]; down[7] = left[3]; down[8] = left[6]
                    left[0] = temp[2]; left[3] = temp[1]; left[6] = temp[0]
                } else {
                    up[0] = left[6]; up[1] = left[3]; up[2] = left[0]
                    left[0] = down[6]; left[3] = down[7]; left[6] = down[8]
                    down[6] = right[8]; down[7] = right[5]; down[8] = right[2]
                    right[2] = temp[0]; right[5] = temp[1]; right[8] = temp[2]
                }
            }

            Face.RIGHT -> {
                val temp = intArrayOf(up[2], up[5], up[8])
                if (clockwise) {
                    up[2] = front[2]; up[5] = front[5]; up[8] = front[8]
                    front[2] = down[2]; front[5] = down[5]; front[8] = down[8]
                    down[2] = back[6]; down[5] = back[3]; down[8] = back[0]
                    back[0] = temp[2]; back[3] = temp[1]; back[6] = temp[0]
                } else {
                    up[2] = back[6]; up[5] = back[3]; up[8] = back[0]
                    back[0] = down[8]; back[3] = down[5]; back[6] = down[2]
                    down[2] = front[2]; down[5] = front[5]; down[8] = front[8]
                    front[2] = temp[0]; front[5] = temp[1]; front[8] = temp[2]
                }
            }

            Face.LEFT -> {
                val temp = intArrayOf(up[0], up[3], up[6])
                if (clockwise) {
                    up[0] = back[8]; up[3] = back[5]; up[6] = back[2]
                    back[2] = down[6]; back[5] = down[3]; back[8] = down[0]
                    down[0] = front[0]; down[3] = front[3]; down[6] = front[6]
                    front[0] = temp[0]; front[3] = temp[1]; front[6] = temp[2]
                } else {
                    up[0] = front[0]; up[3] = front[3]; up[6] = front[6]
                    front[0] = down[0]; front[3] = down[3]; front[6] = down[6]
                    down[0] = back[8]; down[3] = back[5]; down[6] = back[2]
                    back[2] = temp[2]; back[5] = temp[1]; back[8] = temp[0]
                }
            }

            Face.UP -> {
                val temp = intArrayOf(front[0], front[1], front[2])
                if (clockwise) {
                    front[0] = right[0]; front[1] = right[1]; front[2] = right[2]
                    right[0] = back[0]; right[1] = back[1]; right[2] = back[2]
                    back[0] = left[0]; back[1] = left[1]; back[2] = left[2]
                    left[0] = temp[0]; left[1] = temp[1]; left[2] = temp[2]
                } else {
                    front[0] = left[0]; front[1] = left[1]; front[2] = left[2]
                    left[0] = back[0]; left[1] = back[1]; left[2] = back[2]
                    back[0] = right[0]; back[1] = right[1]; back[2] = right[2]
                    right[0] = temp[0]; right[1] = temp[1]; right[2] = temp[2]
                }
            }

            Face.DOWN -> {
                val temp = intArrayOf(front[6], front[7], front[8])
                if (clockwise) {
                    front[6] = left[6]; front[7] = left[7]; front[8] = left[8]
                    left[6] = back[6]; left[7] = back[7]; left[8] = back[8]
                    back[6] = right[6]; back[7] = right[7]; back[8] = right[8]
                    right[6] = temp[0]; right[7] = temp[1]; right[8] = temp[2]
                } else {
                    front[6] = right[6]; front[7] = right[7]; front[8] = right[8]
                    right[6] = back[6]; right[7] = back[7]; right[8] = back[8]
                    back[6] = left[6]; back[7] = left[7]; back[8] = left[8]
                    left[6] = temp[0]; left[7] = temp[1]; left[8] = temp[2]
                }
            }
        }
    }

// The cube state indexing for reference:
// Each face has 9 stickers indexed like this:
// 0 1 2
// 3 4 5
// 6 7 8

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
            -s,  s,  s, -s, -s,  s,  s, -s,  s,  s,  s,  s, // Front
             s,  s,  s,  s, -s,  s,  s, -s, -s,  s,  s, -s, // Right
             s,  s, -s,  s, -s, -s, -s, -s, -s, -s,  s, -s, // Back
            -s,  s, -s, -s, -s, -s, -s, -s,  s, -s,  s,  s, // Left
            -s,  s, -s, -s,  s,  s,  s,  s,  s,  s,  s, -s, // Up
            -s, -s,  s, -s, -s, -s,  s, -s, -s,  s, -s,  s  // Down
        )
        val indices = shortArrayOf(
            0,1,2, 0,2,3, 4,5,6, 4,6,7, 8,9,10, 8,10,11,
            12,13,14, 12,14,15, 16,17,18, 16,18,19, 20,21,22, 20,22,23
        )
        val black = floatArrayOf(0.02f, 0.02f, 0.02f, 1f)
        val colors = FloatArray((vertices.size / 3) * 4) { i -> black[i % 4] }
        return Mesh(vertices, colors, indices)
    }

    private fun buildStickers(): Mesh {
        val verts = ArrayList<Float>()
        val cols = ArrayList<Float>()
        val idx = ArrayList<Short>()
        var vi: Short = 0

        for ((faceIndex, face) in Face.entries.withIndex()) {
            for (iy in 0 until 3) {
                for (ix in 0 until 3) {
                    val gapInCell = 0.04f
                    val cellMinX = -1f + (2f / 3f) * ix
                    val cellMaxX = -1f + (2f / 3f) * (ix + 1)
                    val cellMinY = -1f + (2f / 3f) * iy
                    val cellMaxY = -1f + (2f / 3f) * (iy + 1)
                    val x0 = cellMinX + gapInCell
                    val x1 = cellMaxX - gapInCell
                    val y0 = cellMinY + gapInCell
                    val y1 = cellMaxY - gapInCell

                    val stickerIndex = iy * 3 + ix
                    val colorIndex = cubeState[faceIndex][stickerIndex]
                    val colorFloatArray = CHAR_TO_COLOR.values.toList()[colorIndex]

                    addStickerQuad(face, x0, y0, x1, y1, colorFloatArray, verts, cols, idx, vi)
                    vi = (vi + 4).toShort()
                }
            }
        }
        return Mesh(verts.toFloatArray(), cols.toFloatArray(), idx.toShortArray())
    }

    private fun addStickerQuad(face: Face, x0: Float, y0: Float, x1: Float, y1: Float, colorArr: FloatArray, verts: ArrayList<Float>, cols: ArrayList<Float>, idx: ArrayList<Short>, vi: Short) {
        val p0 = face.mapTo3D(x0, y0, 1.001f); val p1 = face.mapTo3D(x0, y1, 1.001f); val p2 = face.mapTo3D(x1, y1, 1.001f); val p3 = face.mapTo3D(x1, y0, 1.001f)
        verts.addAll(p0.asIterable()); verts.addAll(p1.asIterable()); verts.addAll(p2.asIterable()); verts.addAll(p3.asIterable())
        repeat(4) { cols.addAll(colorArr.asIterable()) }
        idx.add(vi); idx.add((vi + 1).toShort()); idx.add((vi + 2).toShort())
        idx.add(vi); idx.add((vi + 2).toShort()); idx.add((vi + 3).toShort())
    }

    fun setCubeFromCsvString(csv: String): Boolean {
        val reader = BufferedReader(StringReader(csv.trim()))
        val lines = reader.readLines()
        if (lines.size != 6) return false

        val newState = Array(6) { IntArray(9) }
        for (i in 0 until 6) {
            val parts = lines[i].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size != 9) return false
            for (j in 0 until 9) {
                val colorIndex = charToColorIndex(parts[j][0])
                if (colorIndex == -1) return false
                newState[i][j] = colorIndex
            }
        }
        cubeState = newState
        stickers = buildStickers()
        return true
    }

    private fun charToColorIndex(char: Char): Int = when (char) {
        'w' -> 0; 'y' -> 1; 'g' -> 2; 'r' -> 3; 'o' -> 4; 'b' -> 5
        else -> -1
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, shaderCode)
            GLES20.glCompileShader(it)
        }
    }

    private fun Array<Int>.sliceArray(i1: Int, i2: Int, i3: Int): IntArray {
        return intArrayOf(this[i1], this[i2], this[i3])
    }

    private class Mesh(vertices: FloatArray, colors: FloatArray, indices: ShortArray) {
        private val vertexBuffer = java.nio.ByteBuffer.allocateDirect(vertices.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).position(0)
        private val colorBuffer = java.nio.ByteBuffer.allocateDirect(colors.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(colors).position(0)
        private val indexBuffer = java.nio.ByteBuffer.allocateDirect(indices.size * 2).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().put(indices).position(0)
        private val indexCount = indices.size

        fun bind(positionHandle: Int, colorHandle: Int) {
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 3 * 4, vertexBuffer)
            GLES20.glEnableVertexAttribArray(colorHandle)
            GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 4 * 4, colorBuffer)
        }
        fun draw() = GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        fun unbind(p: Int, c: Int) { GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(c) }
    }

    private fun Face.mapTo3D(u: Float, v: Float, d: Float): FloatArray = when (this) {
        Face.FRONT -> floatArrayOf(u, v, d); Face.BACK -> floatArrayOf(-u, v, -d)
        Face.RIGHT -> floatArrayOf(d, v, -u); Face.LEFT -> floatArrayOf(-d, v, u)
        Face.UP -> floatArrayOf(u, d, -v); Face.DOWN -> floatArrayOf(u, -d, v)
    }
}