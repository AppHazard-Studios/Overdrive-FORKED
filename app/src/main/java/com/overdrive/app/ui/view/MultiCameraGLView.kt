package com.overdrive.app.ui.view

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Mosaic quadrant layout in the 2560×1920 recorded file:
 *   Top-left=Rear, Top-right=Left, Bottom-left=Front, Bottom-right=Right
 *   (verified on physical BYD Atto 3 — mosaic order differs from camera names)
 *
 * v0/v1 are swapped from raw image-space to correct the BYD camera's
 * vertically-inverted mosaic output.
 */
enum class CameraView(val label: String, val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    FRONT("Front", 0f,   1f,   0.5f, 0.5f),
    RIGHT("Right", 0.5f, 1f,   1f,   0.5f),
    REAR ("Rear",  0f,   0.5f, 0.5f, 0f  ),
    LEFT ("Left",  0.5f, 0.5f, 1f,   0f  )
}

/**
 * Renders a single mosaic MP4 (decoded via one MediaPlayer) into five screen regions:
 *
 *   Large (left ~74%): the selected primary camera fills this area.
 *   Column (right ~26%): all four cameras stacked in equal rows — including the
 *     primary, so all four are always tappable. Tap any row to promote it to the
 *     large view.
 *
 * Five GL draw calls per frame, one hardware decoder, one texture upload.
 * Call setPrimaryCamera() to change which camera is large.
 */
class MultiCameraGLView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var program = 0

    private val texMatrix = FloatArray(16)
    private var frameAvailable = false
    private val frameLock = Any()

    private var locPosition  = 0
    private var locTexCoord  = 0
    private var locTexMatrix = 0
    private var locSampler   = 0
    private var locIsPrimary = -1
    private var locTexelStep = -1
    private var locCasEnabled = -1

    @Volatile private var casEnabled = true

    @Volatile var primaryCamera: CameraView = CameraView.FRONT
        private set

    /** Called on the main thread once the Surface is ready for MediaPlayer.setSurface(). */
    var onSurfaceReady: ((Surface) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setPrimaryCamera(cam: CameraView) {
        primaryCamera = cam
        requestRender()
    }

    /** Toggle CAS sharpening on/off. Safe to call from any thread. */
    fun setCasEnabled(enabled: Boolean) {
        casEnabled = enabled
        queueEvent {
            if (locCasEnabled >= 0) {
                GLES20.glUseProgram(program)
                GLES20.glUniform1f(locCasEnabled, if (enabled) 1f else 0f)
            }
        }
        requestRender()
    }

    fun isCasEnabled(): Boolean = casEnabled

    // region GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(textureId)
        // Hint the decoder to deliver frames at full mosaic resolution.
        st.setDefaultBufferSize(2560, 1920)
        st.setOnFrameAvailableListener {
            synchronized(frameLock) { frameAvailable = true }
            requestRender()
        }
        surfaceTexture = st
        post { onSurfaceReady?.invoke(Surface(st)) }

        program = buildProgram(VERT_SRC, FRAG_SRC)
        locPosition  = GLES20.glGetAttribLocation(program, "aPosition")
        locTexCoord  = GLES20.glGetAttribLocation(program, "aTexCoord")
        locTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        locSampler   = GLES20.glGetUniformLocation(program, "uSampler")
        locIsPrimary = GLES20.glGetUniformLocation(program, "uIsPrimary")
        locTexelStep = GLES20.glGetUniformLocation(program, "uTexelStep")
        locCasEnabled = GLES20.glGetUniformLocation(program, "uCasEnabled")

        // Texel size and initial CAS state are constant at program creation — set once here.
        GLES20.glUseProgram(program)
        if (locTexelStep >= 0) GLES20.glUniform2f(locTexelStep, 1f / 2560f, 1f / 1920f)
        if (locCasEnabled >= 0) GLES20.glUniform1f(locCasEnabled, if (casEnabled) 1f else 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(frameLock) {
            if (frameAvailable) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(texMatrix)
                frameAvailable = false
            }
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(locTexMatrix, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(locSampler, 0)

        val primary = primaryCamera

        // Large view: left 74% of screen (NDC x: -1 → 0.47), full height — CAS sharpening ON
        drawQuad(-1f, -1f, 0.47f, 1f, primary, isPrimary = true)

        // Right column: all four cameras in equal rows (NDC x: 0.48 → 1).
        // Starts at 0.48 to match the overlay layout boundary (74:26 weight split),
        // eliminating the black strip that appeared inside the column touch targets.
        val rowH = 2f / 4f   // 4 equal rows across NDC height range of 2
        val gap  = 0.005f
        CameraView.values().forEachIndexed { i, cam ->
            val y1 = (1f - i * rowH - gap).coerceAtMost(1f)
            val y0 = (y1 - rowH + gap * 2f).coerceAtLeast(-1f)
            drawQuad(0.48f, y0, 1f, y1, cam, isPrimary = false)
        }
    }

    // endregion

    private fun drawQuad(x0: Float, y0: Float, x1: Float, y1: Float, cam: CameraView, isPrimary: Boolean = false) {
        if (locIsPrimary >= 0) GLES20.glUniform1f(locIsPrimary, if (isPrimary) 1f else 0f)
        // Vertices: (x, y, u, v) — two triangles via TRIANGLE_STRIP
        // y1=top, y0=bottom in NDC
        val verts = floatArrayOf(
            x0, y1, cam.u0, cam.v0,   // top-left
            x1, y1, cam.u1, cam.v0,   // top-right
            x0, y0, cam.u0, cam.v1,   // bottom-left
            x1, y0, cam.u1, cam.v1    // bottom-right
        )
        val buf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(verts); it.position(0) }

        val stride = 4 * 4  // 4 floats × 4 bytes
        GLES20.glEnableVertexAttribArray(locPosition)
        GLES20.glVertexAttribPointer(locPosition, 2, GLES20.GL_FLOAT, false, stride, buf)
        buf.position(2)
        GLES20.glEnableVertexAttribArray(locTexCoord)
        GLES20.glVertexAttribPointer(locTexCoord, 2, GLES20.GL_FLOAT, false, stride, buf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onDetachedFromWindow() {
        surfaceTexture?.release()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val VERT_SRC = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uTexMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}"""

        // CAS (Contrast Adaptive Sharpening) fragment shader.
        // Applied only to the large primary view (uIsPrimary == 1.0) when CAS is enabled
        // (uCasEnabled == 1.0). Thumbnails always use a plain texture lookup.
        // 5 texture samples for primary+CAS, 1 sample otherwise.
        // uTexelStep is set once at program creation to (1/2560, 1/1920).
        private const val FRAG_SRC = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uSampler;
uniform vec2 uTexelStep;
uniform float uIsPrimary;
uniform float uCasEnabled;
varying vec2 vTexCoord;
void main() {
    vec4 col = texture2D(uSampler, vTexCoord);
    if (uIsPrimary > 0.5 && uCasEnabled > 0.5) {
        vec3 c = col.rgb;
        vec3 n = texture2D(uSampler, vTexCoord + vec2(0.0,  uTexelStep.y)).rgb;
        vec3 s = texture2D(uSampler, vTexCoord - vec2(0.0,  uTexelStep.y)).rgb;
        vec3 e = texture2D(uSampler, vTexCoord + vec2( uTexelStep.x, 0.0)).rgb;
        vec3 w = texture2D(uSampler, vTexCoord - vec2( uTexelStep.x, 0.0)).rgb;
        vec3 luma = vec3(0.213, 0.715, 0.072);
        float mnL = min(min(dot(n, luma), dot(s, luma)), min(dot(e, luma), dot(w, luma)));
        float mxL = max(max(dot(n, luma), dot(s, luma)), max(dot(e, luma), dot(w, luma)));
        float amp = sqrt(clamp(min(mnL, 1.0 - mxL) / max(mxL, 0.0001), 0.0, 1.0));
        float wgt = amp * (-0.125);
        col.rgb = clamp((c + wgt * (n + s + e + w)) / (1.0 + 4.0 * wgt), 0.0, 1.0);
    }
    gl_FragColor = col;
}"""

        private fun buildProgram(vertSrc: String, fragSrc: String): Int {
            fun compile(type: Int, src: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, src)
                GLES20.glCompileShader(shader)
                return shader
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertSrc))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragSrc))
            GLES20.glLinkProgram(p)
            return p
        }
    }
}
