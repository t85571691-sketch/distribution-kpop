package com.linedist.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Picked(val uri: Uri, val name: String, val size: Long)

/**
 * Offline export: the page is stepped on a virtual clock, every frame is copied from the window
 * with PixelCopy and encoded to H.264 with an exact timestamp. Audio is transcoded from the
 * original file, then both are remuxed into one MP4 and saved to the gallery.
 */
class VideoExporter(
    private val act: Activity,
    private val params: JSONObject,
    private val picked: List<Picked>,
    private val callJs: (String) -> Unit
) {
    private val TAG = "VideoExporter"
    private val fps = params.optInt("fps", 30)
    private val audioLenUs = (params.optDouble("audioLen", 10.0) * 1_000_000).toLong()
    private val thread = HandlerThread("export").also { it.start() }
    private val handler = Handler(thread.looper)
    private val nm = act.getSystemService(NotificationManager::class.java)
    private val tmpV = File(act.cacheDir, "exp_video.mp4")
    private val tmpA = File(act.cacheDir, "exp_audio.m4a")
    private val info = MediaCodec.BufferInfo()

    private var w = 0
    private var h = 0
    private var enc: MediaCodec? = null
    private var vmux: MediaMuxer? = null
    private var vTrack = -1
    private var vStarted = false
    private var bmp: Bitmap? = null
    private var px: IntArray = IntArray(0)
    private var hasAudio = false
    private var lastPts = 0L
    private var released = false
    @Volatile var failed: String? = null

    private fun fmt(width: Int, height: Int): MediaFormat {
        val f = MediaFormat.createVideoFormat("video/avc", width, height)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val br = (width.toLong() * height * fps * 0.15).toLong().coerceIn(2_000_000L, 20_000_000L).toInt()
        f.setInteger(MediaFormat.KEY_BIT_RATE, br)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        return f
    }

    fun begin() {
        handler.post {
            try {
                val ch = NotificationChannel("exp", "Export", NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(ch)
                act.runOnUiThread {
                    Toast.makeText(act, "Exporting MP4... keep the app open and the screen on", Toast.LENGTH_LONG).show()
                }
                val dv = act.window.decorView
                var tw = dv.width and 1.inv()
                var th = dv.height and 1.inv()
                if (tw < 2 || th < 2) throw IllegalStateException("Window not ready")
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                var name: String? = null
                while (true) {
                    name = list.findEncoderForFormat(fmt(tw, th))
                    if (name != null) break
                    tw = ((tw * 0.85).toInt()) and 1.inv()
                    th = ((th * 0.85).toInt()) and 1.inv()
                    if (tw < 240) throw IllegalStateException("No H.264 encoder available")
                }
                w = tw; h = th
                val e = MediaCodec.createByCodecName(name!!)
                e.configure(fmt(w, h), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                e.start()
                enc = e
                vmux = MediaMuxer(tmpV.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                px = IntArray(w * h)

                // ---- audio: transcode original file to AAC + loudness envelope for the pulse effect ----
                val env = ArrayList<Float>()
                val srcs = params.optJSONArray("srcs")
                if (srcs != null) {
                    for (i in 0 until srcs.length()) {
                        val o = srcs.getJSONObject(i)
                        val n = o.optString("name")
                        val s = o.optLong("size", -1)
                        val m = picked.lastOrNull { it.name == n && (s < 0 || it.size < 0 || it.size == s) }
                            ?: picked.lastOrNull { it.name == n } ?: continue
                        env.clear()
                        val okA = try { AudioPrep.run(act, m.uri, audioLenUs, tmpA, fps, env) } catch (t: Throwable) {
                            Log.w(TAG, "audio failed", t); false
                        }
                        if (okA) { hasAudio = true; break }
                    }
                }
                if (!hasAudio && srcs != null && srcs.length() > 0) {
                    act.runOnUiThread {
                        Toast.makeText(act, "No audio found for the video (silent export)", Toast.LENGTH_LONG).show()
                    }
                }
                var p = 1f
                if (env.isNotEmpty()) {
                    val sorted = env.sorted()
                    p = sorted[((sorted.size * 0.98).toInt()).coerceAtMost(sorted.size - 1)].coerceAtLeast(1e-4f)
                }
                val sb = StringBuilder()
                for (i in env.indices) {
                    if (i > 0) sb.append(',')
                    val lvl = Math.sqrt((env[i] / p).toDouble()).toFloat().coerceAtMost(1f)
                    sb.append((lvl * 55).toInt())
                }
                callJs("window.__expBegun&&window.__expBegun('" + sb.toString() + "')")
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun frame(n: Int, total: Int) {
        val f = failed
        if (f != null) {
            callJs("window.__expFail&&window.__expFail(" + JSONObject.quote(f) + ")")
            return
        }
        act.runOnUiThread {
            try {
                PixelCopy.request(act.window, bmp!!, { res ->
                    try {
                        if (res != PixelCopy.SUCCESS) {
                            throw IllegalStateException("Screen copy error $res (keep the app open and the screen on)")
                        }
                        encodeFrame(n)
                        if (n % 15 == 0) progress(n, total)
                        callJs("window.__expFrameDone&&window.__expFrameDone()")
                    } catch (t: Throwable) {
                        fail(t)
                    }
                }, handler)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    private fun encodeFrame(n: Int) {
        val e = enc!!
        bmp!!.getPixels(px, 0, w, 0, 0, w, h)
        var idx = -1
        var tries = 0
        while (idx < 0) {
            idx = e.dequeueInputBuffer(20_000)
            drain(0, false)
            if (++tries > 400) throw IllegalStateException("Encoder stalled")
        }
        val img = e.getInputImage(idx) ?: throw IllegalStateException("No input image")
        fillImage(img)
        val pts = n * 1_000_000L / fps
        lastPts = pts
        e.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0)
        drain(0, false)
    }

    private fun fillImage(img: android.media.Image) {
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val yb = yP.buffer
        val ub = uP.buffer
        val vb = vP.buffer
        val ys = yP.rowStride
        val us = uP.rowStride
        val up = uP.pixelStride
        val vs = vP.rowStride
        val vpix = vP.pixelStride
        val row0 = ByteArray(w)
        val row1 = ByteArray(w)
        var j = 0
        while (j < h) {
            val o0 = j * w
            val o1 = (j + 1) * w
            var i = 0
            while (i < w) {
                val a = px[o0 + i]
                val b = px[o0 + i + 1]
                val c = px[o1 + i]
                val d = px[o1 + i + 1]
                val ar = (a shr 16) and 255; val ag = (a shr 8) and 255; val ab = a and 255
                val br = (b shr 16) and 255; val bg = (b shr 8) and 255; val bb = b and 255
                val cr = (c shr 16) and 255; val cg = (c shr 8) and 255; val cb = c and 255
                val dr = (d shr 16) and 255; val dg = (d shr 8) and 255; val db = d and 255
                row0[i] = (((47 * ar + 157 * ag + 16 * ab) shr 8) + 16).toByte()
                row0[i + 1] = (((47 * br + 157 * bg + 16 * bb) shr 8) + 16).toByte()
                row1[i] = (((47 * cr + 157 * cg + 16 * cb) shr 8) + 16).toByte()
                row1[i + 1] = (((47 * dr + 157 * dg + 16 * db) shr 8) + 16).toByte()
                val r = (ar + br + cr + dr) shr 2
                val g = (ag + bg + cg + dg) shr 2
                val bl = (ab + bb + cb + db) shr 2
                val u = (((-26 * r - 87 * g + 112 * bl) shr 8) + 128).coerceIn(0, 255)
                val v = (((112 * r - 102 * g - 10 * bl) shr 8) + 128).coerceIn(0, 255)
                ub.put((j shr 1) * us + (i shr 1) * up, u.toByte())
                vb.put((j shr 1) * vs + (i shr 1) * vpix, v.toByte())
                i += 2
            }
            yb.position(j * ys); yb.put(row0, 0, w)
            yb.position((j + 1) * ys); yb.put(row1, 0, w)
            j += 2
        }
    }

    private fun drain(timeoutUs: Long, untilEos: Boolean) {
        val e = enc ?: return
        var spins = 0
        while (true) {
            val i = e.dequeueOutputBuffer(info, timeoutUs)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos || ++spins > 600) return
                continue
            }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                vTrack = vmux!!.addTrack(e.outputFormat)
                vmux!!.start()
                vStarted = true
                continue
            }
            if (i >= 0) {
                val buf = e.getOutputBuffer(i)!!
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && vStarted) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    vmux!!.writeSampleData(vTrack, buf, info)
                }
                e.releaseOutputBuffer(i, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }
    }

    private fun progress(n: Int, total: Int) {
        try {
            val nt = Notification.Builder(act, "exp")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Exporting MP4")
                .setContentText((n * 100 / total.coerceAtLeast(1)).toString() + "%")
                .setProgress(total, n, false)
                .setOngoing(true)
                .build()
            nm.notify(2, nt)
        } catch (_: Throwable) {}
    }

    fun finish() {
        handler.post {
            try {
                val e = enc!!
                var idx = -1
                var tries = 0
                while (idx < 0) {
                    idx = e.dequeueInputBuffer(20_000)
                    drain(0, false)
                    if (++tries > 400) throw IllegalStateException("Encoder stalled at end")
                }
                e.queueInputBuffer(idx, 0, 0, lastPts + 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(10_000, true)
                e.stop(); e.release(); enc = null
                if (vStarted) vmux!!.stop()
                vmux!!.release(); vmux = null
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val out = File(act.cacheDir, "LineDistribution_$stamp.mp4")
                Remux.merge(tmpV, if (hasAudio) tmpA else null, out)
                saveToGallery(out)
                out.delete()
                cleanup()
                callJs("window.__expFinished&&window.__expFinished(true,'')")
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun cancel() {
        handler.post { cleanup() }
    }

    fun fail(t: Throwable) {
        Log.e(TAG, "export failed", t)
        val msg = (t.message ?: t.toString())
        if (failed == null) failed = msg
        try { cleanup() } catch (_: Throwable) {}
        callJs("window.__expFail&&window.__expFail(" + JSONObject.quote(msg) + ")")
    }

    private fun cleanup() {
        if (released) return
        released = true
        try { enc?.stop() } catch (_: Throwable) {}
        try { enc?.release() } catch (_: Throwable) {}
        try { vmux?.release() } catch (_: Throwable) {}
        enc = null; vmux = null
        tmpV.delete(); tmpA.delete()
        try { nm.cancel(2) } catch (_: Throwable) {}
        bmp?.recycle(); bmp = null
        thread.quitSafely()
    }

    private fun saveToGallery(f: File) {
        val cr = act.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LineDistribution")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
        cr.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        v.clear(); v.put(MediaStore.Video.Media.IS_PENDING, 0)
        cr.update(uri, v, null, null)
    }
}

object Remux {
    private fun pick(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (m != null && m.startsWith(prefix)) return i
        }
        throw IllegalStateException("Track not found: $prefix")
    }

    fun merge(video: File, audio: File?, out: File) {
        val vx = MediaExtractor()
        vx.setDataSource(video.absolutePath)
        var ax: MediaExtractor? = null
        if (audio != null && audio.exists() && audio.length() > 0) {
            ax = MediaExtractor()
            ax.setDataSource(audio.absolutePath)
        }
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vt = pick(vx, "video/")
            vx.selectTrack(vt)
            val vi = mux.addTrack(vx.getTrackFormat(vt))
            var ai = -1
            if (ax != null) {
                val at = pick(ax, "audio/")
                ax.selectTrack(at)
                ai = mux.addTrack(ax.getTrackFormat(at))
            }
            mux.start()
            val buf = ByteBuffer.allocate(3 * 1024 * 1024)
            val bi = MediaCodec.BufferInfo()
            var vDone = false
            var aDone = ax == null
            while (!vDone || !aDone) {
                val useV = !vDone && (aDone || vx.sampleTime <= ax!!.sampleTime)
                val ex = if (useV) vx else ax!!
                val n = ex.readSampleData(buf, 0)
                if (n < 0) {
                    if (useV) vDone = true else aDone = true
                    continue
                }
                bi.set(0, n, ex.sampleTime, ex.sampleFlags)
                mux.writeSampleData(if (useV) vi else ai, buf, bi)
                ex.advance()
            }
            mux.stop()
        } finally {
            try { mux.release() } catch (_: Throwable) {}
            vx.release()
            ax?.release()
        }
    }
}

object AudioPrep {
    /** Decode [uri]'s audio, re-encode to AAC (max [durationUs]) into [out], fill [env] with RMS per video frame. */
    fun run(ctx: Context, uri: Uri, durationUs: Long, out: File, fps: Int, env: MutableList<Float>): Boolean {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        var muxStarted = false
        try {
            ex.setDataSource(ctx, uri, null)
            var tr = -1
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (m != null && m.startsWith("audio/")) { tr = i; break }
            }
            if (tr < 0) return false
            ex.selectTrack(tr)
            val inFmt = ex.getTrackFormat(tr)
            val d = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(inFmt, null, null, 0)
            d.start()

            val dInfo = MediaCodec.BufferInfo()
            val eInfo = MediaCodec.BufferInfo()
            var sr = 0
            var chn = 0
            var track = -1
            var inDone = false
            var decEos = false
            var encEosQueued = false
            var encEos = false
            var pushed = 0L
            var envWin = 1
            var envSum = 0.0
            var envCnt = 0

            fun setup(rate: Int, channels: Int) {
                sr = rate; chn = channels; envWin = maxOf(1, rate / fps)
                val f = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, channels)
                f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                f.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                val e = MediaCodec.createEncoderByType("audio/mp4a-latm")
                e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                e.start()
                enc = e
                mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            fun drainEnc() {
                val e = enc ?: return
                while (true) {
                    val i = e.dequeueOutputBuffer(eInfo, 0)
                    if (i == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux!!.addTrack(e.outputFormat)
                        mux!!.start()
                        muxStarted = true
                        continue
                    }
                    if (i >= 0) {
                        val b = e.getOutputBuffer(i)!!
                        if ((eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && eInfo.size > 0 && muxStarted) {
                            b.position(eInfo.offset)
                            b.limit(eInfo.offset + eInfo.size)
                            mux!!.writeSampleData(track, b, eInfo)
                        }
                        e.releaseOutputBuffer(i, false)
                        if ((eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { encEos = true; return }
                    }
                }
            }

            fun push(bytes: ByteArray) {
                val e = enc!!
                val unit = 2 * chn
                var off = 0
                var spin = 0
                while (off < bytes.size) {
                    val ii = e.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val b = e.getInputBuffer(ii)!!
                        b.clear()
                        var n = minOf(b.capacity(), bytes.size - off)
                        n -= n % unit
                        if (n <= 0) break
                        b.put(bytes, off, n)
                        e.queueInputBuffer(ii, 0, n, pushed * 1_000_000L / sr, 0)
                        off += n
                        pushed += (n / unit).toLong()
                    } else if (++spin > 2000) {
                        throw IllegalStateException("Audio encoder stalled")
                    }
                    drainEnc()
                }
            }

            var guard = 0L
            while (!encEos) {
                if (!inDone) {
                    val ii = d.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val b = d.getInputBuffer(ii)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0 || ex.sampleTime > durationUs + 1_000_000L) {
                            d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            d.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                if (!decEos) {
                    val oi = d.dequeueOutputBuffer(dInfo, 5_000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = d.outputFormat
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) return false
                        val c = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (c > 2) return false
                        if (enc == null) setup(of.getInteger(MediaFormat.KEY_SAMPLE_RATE), c)
                    } else if (oi >= 0) {
                        if (enc == null) {
                            val c = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (c > 2) return false
                            setup(inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), c)
                        }
                        val ob = d.getOutputBuffer(oi)!!
                        if (dInfo.size > 0 && pushed * 1_000_000L / sr < durationUs) {
                            val bytes = ByteArray(dInfo.size)
                            ob.position(dInfo.offset)
                            ob.get(bytes, 0, dInfo.size)
                            var p = 0
                            while (p + 2 * chn <= bytes.size) {
                                var s = 0
                                for (k in 0 until chn) {
                                    val lo = bytes[p].toInt() and 255
                                    val hi = bytes[p + 1].toInt()
                                    s += (hi shl 8) or lo
                                    p += 2
                                }
                                val m = s.toDouble() / chn / 32768.0
                                envSum += m * m
                                envCnt++
                                if (envCnt >= envWin) {
                                    env.add(Math.sqrt(envSum / envCnt).toFloat())
                                    envSum = 0.0; envCnt = 0
                                }
                            }
                            push(bytes)
                        }
                        d.releaseOutputBuffer(oi, false)
                        if ((dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decEos = true
                    }
                }
                drainEnc()
                if (!encEosQueued && enc != null && (decEos || pushed * 1_000_000L / maxOf(sr, 1) >= durationUs)) {
                    val e = enc!!
                    val ei = e.dequeueInputBuffer(20_000)
                    if (ei >= 0) {
                        e.queueInputBuffer(ei, 0, 0, pushed * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encEosQueued = true
                    }
                }
                if (decEos && enc == null) return false
                if (++guard > 5_000_000L) return false
            }
            if (muxStarted) { mux!!.stop(); muxStarted = false; return env.isNotEmpty() }
            return false
        } finally {
            try { dec?.stop() } catch (_: Throwable) {}
            try { dec?.release() } catch (_: Throwable) {}
            try { enc?.stop() } catch (_: Throwable) {}
            try { enc?.release() } catch (_: Throwable) {}
            try { mux?.release() } catch (_: Throwable) {}
            ex.release()
        }
    }
}
