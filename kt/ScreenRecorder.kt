package top.sagrus.cam

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.opengl.*
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Экран -> OpenGL (вырезается только область видео) -> H.264, плюс внутренний звук (AAC) -> mp4.
 * crop() возвращает область видео в долях экрана [left, top, right, bottom].
 */
class ScreenRecorder(private val proj: MediaProjection, private val srcW: Int, private val srcH: Int,
                     private val outW: Int, private val outH: Int, private val dpi: Int,
                     private val fd: FileDescriptor, private val onError: (String) -> Unit,
                     private val crop: () -> FloatArray) {
    private val lock = Object()
    private val frameLock = Object()
    private var frames = 0
    private var muxer: MediaMuxer? = null
    private var vTrack = -1; private var aTrack = -1
    private var started = false
    @Volatile private var running = false
    private var vEnc: MediaCodec? = null; private var aEnc: MediaCodec? = null
    private var audio: AudioRecord? = null; private var vd: VirtualDisplay? = null
    @Volatile private var inSurface: Surface? = null
    private var gl: Thread? = null
    private var baseUs = 0L
    private val last = longArrayOf(-1, -1)
    private val threads = mutableListOf<Thread>()
    private fun nowUs() = System.nanoTime() / 1000

    @SuppressLint("MissingPermission")
    fun start() {
        muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (outW >= 1920) 8_000_000 else 5_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val ve = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC); vEnc = ve
        ve.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encSurface = ve.createInputSurface(); ve.start()

        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val ae = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC); aEnc = ae
        ae.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); ae.start()

        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audio = AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(cfg)
            .setBufferSizeInBytes(maxOf(min, 32768)).build()

        baseUs = nowUs(); running = true
        val ready = CountDownLatch(1)
        val g = thread(name = "gl") { renderLoop(encSurface, ready) }
        gl = g; threads += g
        if (!ready.await(4, TimeUnit.SECONDS) || inSurface == null) throw IllegalStateException("не удалось запустить OpenGL")
        vd = proj.createVirtualDisplay("rec", srcW, srcH, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inSurface, null, null)
        audio!!.startRecording()
        threads += thread(name = "venc") {
            while (running) { drain(ve, true, false); Thread.sleep(5) }
            try { g.join(2000) } catch (_: Exception) {}
            try { ve.signalEndOfInputStream() } catch (_: Exception) {}
            drain(ve, true, true)
        }
        threads += thread(name = "aenc") {
            val buf = ByteArray(8192)
            while (running) {
                val n = audio!!.read(buf, 0, buf.size)
                if (n > 0) {
                    val i = ae.dequeueInputBuffer(10_000)
                    if (i >= 0) { val ib = ae.getInputBuffer(i)!!; ib.clear(); ib.put(buf, 0, n); ae.queueInputBuffer(i, 0, n, nowUs(), 0) }
                }
                drain(ae, false, false)
            }
            val i = ae.dequeueInputBuffer(100_000)
            if (i >= 0) ae.queueInputBuffer(i, 0, 0, nowUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(ae, false, true)
        }
    }

    private fun sh(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s
    }

    private fun renderLoop(encSurface: Surface, ready: CountDownLatch) {
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        var esurf: EGLSurface = EGL14.EGL_NO_SURFACE
        var st: SurfaceTexture? = null
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2); EGL14.eglInitialize(dpy, ver, 0, ver, 1)
            val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE)
            val cfg = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
            EGL14.eglChooseConfig(dpy, attrs, 0, cfg, 0, 1, num, 0)
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            esurf = EGL14.eglCreateWindowSurface(dpy, cfg[0], encSurface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(dpy, esurf, esurf, ctx)

            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, sh(GLES20.GL_VERTEX_SHADER,
                "uniform mat4 uST;attribute vec4 aPos;attribute vec4 aTex;varying vec2 vTex;void main(){gl_Position=aPos;vTex=(uST*aTex).xy;}"))
            GLES20.glAttachShader(prog, sh(GLES20.GL_FRAGMENT_SHADER,
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 vTex;uniform samplerExternalOES sTex;void main(){gl_FragColor=texture2D(sTex,vTex);}"))
            GLES20.glLinkProgram(prog)
            val aPos = GLES20.glGetAttribLocation(prog, "aPos"); val aTex = GLES20.glGetAttribLocation(prog, "aTex")
            val uST = GLES20.glGetUniformLocation(prog, "uST")

            val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val s = SurfaceTexture(tex[0]); st = s
            s.setDefaultBufferSize(srcW, srcH)
            s.setOnFrameAvailableListener { synchronized(frameLock) { frames++; frameLock.notifyAll() } }
            inSurface = Surface(s)
            ready.countDown()

            val vb = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); vb.position(0)
            val tb = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val mat = FloatArray(16)
            var haveFrame = false; var lastDraw = 0L; var fails = 0
            while (running) {
                var got = false
                synchronized(frameLock) {
                    if (frames == 0) frameLock.wait(50)
                    if (frames > 0) { frames = 0; got = true }
                }
                try {
                    if (got) { s.updateTexImage(); s.getTransformMatrix(mat); haveFrame = true }
                    else if (!haveFrame || System.nanoTime() - lastDraw < 100_000_000L) continue   // повтор кадра минимум 10 раз/с
                    val c = crop()
                    if (c[2] <= c[0] || c[3] <= c[1]) continue
                    tb.clear(); tb.put(floatArrayOf(c[0], 1f - c[3], c[2], 1f - c[3], c[0], 1f - c[1], c[2], 1f - c[1])); tb.position(0)
                    GLES20.glViewport(0, 0, outW, outH)
                    GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glUseProgram(prog)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
                    GLES20.glUniformMatrix4fv(uST, 1, false, mat, 0)
                    GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, vb)
                    GLES20.glEnableVertexAttribArray(aTex); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, tb)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    // время кадра берём из того же таймера, что и у звука, — рассинхрона и "замёрзшей" картинки не будет
                    EGLExt.eglPresentationTimeANDROID(dpy, esurf, System.nanoTime())
                    EGL14.eglSwapBuffers(dpy, esurf)
                    lastDraw = System.nanoTime(); fails = 0
                } catch (e: Exception) {
                    if (++fails >= 10) throw e
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onError("Сбой видео: ${e.javaClass.simpleName} ${e.message ?: ""}")
        } finally {
            ready.countDown()
            try { st?.release() } catch (_: Exception) {}
            try { inSurface?.release() } catch (_: Exception) {}
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (esurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, esurf)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            }
        }
    }

    private fun drain(enc: MediaCodec, video: Boolean, end: Boolean) {
        val info = MediaCodec.BufferInfo(); val deadline = System.currentTimeMillis() + 3000
        while (true) {
            val i = enc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end || System.currentTimeMillis() > deadline) return; continue }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) {
                    val t = muxer!!.addTrack(enc.outputFormat)
                    if (video) vTrack = t else aTrack = t
                    if (vTrack >= 0 && aTrack >= 0) { muxer!!.start(); started = true; lock.notifyAll() }
                }
                continue
            }
            if (i < 0) continue
            val data = enc.getOutputBuffer(i)
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
            if (info.size > 0 && data != null) {
                val t = if (video) 0 else 1
                synchronized(lock) {
                    while (!started && running) lock.wait(50)
                    if (started) {
                        var pts = maxOf(0L, info.presentationTimeUs - baseUs)
                        if (pts <= last[t]) pts = last[t] + 1
                        last[t] = pts; info.presentationTimeUs = pts
                        data.position(info.offset); data.limit(info.offset + info.size)
                        muxer!!.writeSampleData(if (video) vTrack else aTrack, data, info)
                    }
                }
            }
            enc.releaseOutputBuffer(i, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    fun stop() {
        running = false
        try { vd?.release() } catch (_: Exception) {}
        threads.forEach { try { it.join(5000) } catch (_: Exception) {} }
        try { audio?.stop() } catch (_: Exception) {}; audio?.release()
        try { vEnc?.stop() } catch (_: Exception) {}; vEnc?.release()
        try { aEnc?.stop() } catch (_: Exception) {}; aEnc?.release()
        try { if (started) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
    }
}
