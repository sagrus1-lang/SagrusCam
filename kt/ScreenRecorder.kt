package top.sagrus.cam

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import java.io.FileDescriptor
import kotlin.concurrent.thread

/** Экран (H.264) + внутренний звук приложения (AAC) -> mp4. */
class ScreenRecorder(private val proj: MediaProjection, private val w: Int, private val h: Int,
                     private val dpi: Int, private val fd: FileDescriptor) {
    private val lock = Object()
    private var muxer: MediaMuxer? = null
    private var vTrack = -1; private var aTrack = -1
    private var started = false
    @Volatile private var running = false
    private var vEnc: MediaCodec? = null; private var aEnc: MediaCodec? = null
    private var audio: AudioRecord? = null; private var vd: VirtualDisplay? = null
    private var baseUs = 0L
    private val last = longArrayOf(-1, -1)
    private val threads = mutableListOf<Thread>()
    private fun nowUs() = System.nanoTime() / 1000

    @SuppressLint("MissingPermission")
    fun start() {
        muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val ve = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC); vEnc = ve
        ve.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = ve.createInputSurface(); ve.start()

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

        vd = proj.createVirtualDisplay("rec", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        baseUs = nowUs(); running = true
        audio!!.startRecording()
        threads += thread(name = "venc") {
            while (running) { drain(ve, true, false); Thread.sleep(5) }
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
        threads.forEach { try { it.join(5000) } catch (_: Exception) {} }
        try { audio?.stop() } catch (_: Exception) {}; audio?.release()
        vd?.release()
        try { vEnc?.stop() } catch (_: Exception) {}; vEnc?.release()
        try { aEnc?.stop() } catch (_: Exception) {}; aEnc?.release()
        try { if (started) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
    }
}
