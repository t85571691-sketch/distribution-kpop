package com.linedist.app

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/** Screen (H.264) + internal audio (AAC) -> MP4. */
class ScreenRecorder(
    private val mp: MediaProjection,
    private val w: Int, private val h: Int, private val dpi: Int,
    private val outFile: File,
    withAudio: Boolean
) {
    private val TAG = "ScreenRecorder"
    private val RATE = 44100
    private val lock = Object()
    private lateinit var muxer: MediaMuxer
    private var vEnc: MediaCodec? = null
    private var aEnc: MediaCodec? = null
    private var vd: VirtualDisplay? = null
    private var ar: AudioRecord? = null
    private var vTrack = -1
    private var aTrack = -1
    private var muxStarted = false
    private var wantAudio = withAudio
    @Volatile private var stopping = false
    private var vThread: Thread? = null
    private var aThread: Thread? = null
    private var baseUs = 0L
    private var lastV = -1L
    private var lastA = -1L

    private class Pending(val audio: Boolean, val data: ByteBuffer, val info: MediaCodec.BufferInfo)
    private val pending = ArrayList<Pending>()

    fun start() {
        baseUs = System.nanoTime() / 1000
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val vf = MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 16_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val ve = MediaCodec.createEncoderByType("video/avc")
        ve.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = ve.createInputSurface()
        ve.start()
        vEnc = ve

        if (wantAudio) {
            try { setupAudio() } catch (e: Exception) {
                Log.w(TAG, "No internal audio, video only", e)
                wantAudio = false
                try { ar?.release() } catch (_: Exception) {}
                try { aEnc?.release() } catch (_: Exception) {}
                ar = null; aEnc = null
            }
        }

        vd = mp.createVirtualDisplay("rec", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)

        vThread = Thread { drainVideo() }.also { it.start() }
        if (wantAudio) aThread = Thread { audioLoop() }.also { it.start() }
    }

    private fun setupAudio() {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val af = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(af)
            .setBufferSizeInBytes(maxOf(min * 4, 32768))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord not initialized")
        ar = rec
        val fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val e = MediaCodec.createEncoderByType("audio/mp4a-latm")
        e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        e.start()
        aEnc = e
    }

    private fun tryStartMuxer() { // call with lock held
        if (muxStarted || vTrack < 0 || (wantAudio && aTrack < 0)) return
        muxer.start(); muxStarted = true
        for (p in pending) muxer.writeSampleData(if (p.audio) aTrack else vTrack, p.data, p.info)
        pending.clear()
    }

    private fun write(audio: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        var pts = maxOf(0L, info.presentationTimeUs - baseUs)
        if (audio) { if (pts <= lastA) pts = lastA + 1; lastA = pts }
        else { if (pts <= lastV) pts = lastV + 1; lastV = pts }
        synchronized(lock) {
            buf.position(info.offset); buf.limit(info.offset + info.size)
            if (!muxStarted) {
                val copy = ByteBuffer.allocate(info.size); copy.put(buf); copy.flip()
                val i = MediaCodec.BufferInfo(); i.set(0, info.size, pts, info.flags)
                pending.add(Pending(audio, copy, i))
            } else {
                val i = MediaCodec.BufferInfo(); i.set(info.offset, info.size, pts, info.flags)
                muxer.writeSampleData(if (audio) aTrack else vTrack, buf, i)
            }
        }
    }

    private fun drainVideo() {
        val enc = vEnc!!
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(lock) { vTrack = muxer.addTrack(enc.outputFormat); tryStartMuxer() }
                } else if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) write(false, buf, info)
                    enc.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) { Log.e(TAG, "drainVideo", e) }
    }

    private fun audioLoop() {
        val enc = aEnc!!; val rec = ar!!
        val info = MediaCodec.BufferInfo()
        val startUs = System.nanoTime() / 1000
        var samples = 0L
        var eosSent = false
        try {
            rec.startRecording()
            while (true) {
                if (!eosSent) {
                    val inIdx = enc.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = enc.getInputBuffer(inIdx)!!
                        ib.clear()
                        val pts = startUs + samples * 1_000_000L / RATE
                        if (stopping) {
                            enc.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        } else {
                            val n = rec.read(ib, minOf(ib.capacity(), 4096))
                            if (n > 0) { samples += n / 4; enc.queueInputBuffer(inIdx, 0, n, pts, 0) }
                            else enc.queueInputBuffer(inIdx, 0, 0, pts, 0)
                        }
                    }
                }
                while (true) {
                    val idx = enc.dequeueOutputBuffer(info, 0)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(lock) { aTrack = muxer.addTrack(enc.outputFormat); tryStartMuxer() }
                    } else if (idx >= 0) {
                        val buf = enc.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) write(true, buf, info)
                        enc.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    } else break
                }
            }
        } catch (e: Exception) { Log.e(TAG, "audioLoop", e) }
    }

    fun stop() {
        stopping = true
        try { vd?.release() } catch (_: Exception) {}
        try { vEnc?.signalEndOfInputStream() } catch (_: Exception) {}
        vThread?.join(6000)
        aThread?.join(6000)
        try { ar?.stop() } catch (_: Exception) {}
        try { ar?.release() } catch (_: Exception) {}
        try { vEnc?.stop(); vEnc?.release() } catch (_: Exception) {}
        try { aEnc?.stop(); aEnc?.release() } catch (_: Exception) {}
        synchronized(lock) {
            try { if (muxStarted) muxer.stop() } catch (e: Exception) { Log.e(TAG, "mux stop", e) }
            try { muxer.release() } catch (_: Exception) {}
        }
    }
}
