package com.linedist.app

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecService : Service() {
    companion object {
        @Volatile var listener: ((String, String?) -> Unit)? = null
        private const val TAG = "RecService"
    }

    private var projection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    private var outFile: File? = null
    private var finishing = false

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "start" -> {
                startFg()
                try {
                    val code = intent.getIntExtra("code", 0)
                    val data = intent.getParcelableExtra<Intent>("data")!!
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projection = mpm.getMediaProjection(code, data)
                    projection!!.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() { finish() }
                    }, Handler(Looper.getMainLooper()))

                    var w = intent.getIntExtra("w", 1080)
                    var h = intent.getIntExtra("h", 1920)
                    val dpi = intent.getIntExtra("dpi", 420)
                    val s = minOf(1.0, 2560.0 / maxOf(w, h))
                    w = ((w * s).toInt()) and 1.inv()
                    h = ((h * s).toInt()) and 1.inv()
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    outFile = File(cacheDir, "LineDistribution_$stamp.mp4")
                    recorder = ScreenRecorder(projection!!, w, h, dpi, outFile!!, intent.getBooleanExtra("audio", false))
                    recorder!!.start()
                    listener?.invoke("started", null)
                } catch (e: Exception) {
                    Log.e(TAG, "start failed", e)
                    listener?.invoke("error", e.toString())
                    finish(save = false)
                }
            }
            "stop" -> finish()
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "Recording", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "rec")
            .setContentTitle("Line Distribution")
            .setContentText("Recording video…")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun finish(save: Boolean = true) {
        if (finishing) return
        finishing = true
        Thread {
            try { recorder?.stop() } catch (e: Exception) { Log.e(TAG, "stop", e) }
            try { projection?.stop() } catch (_: Exception) {}
            val f = outFile
            if (save && f != null && f.exists() && f.length() > 0) {
                try {
                    saveToGallery(f)
                    listener?.invoke("saved", null)
                } catch (e: Exception) {
                    listener?.invoke("error", "Save failed: $e")
                }
            } else if (save) listener?.invoke("error", "Empty video")
            f?.delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    private fun saveToGallery(f: File) {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LineDistribution")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
        contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        v.clear(); v.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, v, null, null)
    }
}
