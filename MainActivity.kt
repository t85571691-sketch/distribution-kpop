package com.linedist.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val picked = ArrayList<Picked>()
    private var exporter: VideoExporter? = null

    companion object {
        const val REQ_CAP = 1001
        const val REQ_FILE = 1002
        const val REQ_PERM = 1003
        const val REQ_NOTIF = 1004
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        goImmersive()
        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE); true
                } catch (e: Exception) { fileCb = null; false }
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidRec")

        RecService.listener = { event, msg ->
            web.post {
                when (event) {
                    "started" -> web.postDelayed({ js("window.__recReady&&window.__recReady()") }, 800)
                    "saved" -> js("window.__recDone&&window.__recDone()")
                    "error" -> js("window.__recError&&window.__recError(${JSONObject.quote(msg ?: "")})")
                }
            }
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    @Suppress("DEPRECATION")
    private fun goImmersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

    private fun remember(uris: Array<Uri>?) {
        if (uris == null) return
        for (u in uris) {
            var name = ""
            var size = -1L
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: ""
                        size = if (c.isNull(1)) -1L else c.getLong(1)
                    }
                }
            } catch (_: Exception) {}
            if (name.isNotEmpty()) picked.add(Picked(u, name, size))
        }
        while (picked.size > 40) picked.removeAt(0)
    }

    inner class Bridge {
        // ---- live screen-capture fallback ----
        @JavascriptInterface fun start() { runOnUiThread { askPermissions() } }
        @JavascriptInterface fun stop() {
            runOnUiThread {
                startService(Intent(this@MainActivity, RecService::class.java).setAction("stop"))
            }
        }

        // ---- offline MP4 export ----
        @JavascriptInterface fun exportBegin(json: String) {
            runOnUiThread {
                try {
                    val ex = VideoExporter(this@MainActivity, JSONObject(json), ArrayList(picked)) { code ->
                        web.post { web.evaluateJavascript(code, null) }
                    }
                    exporter = ex
                    ex.begin()
                } catch (t: Throwable) {
                    js("window.__expFail&&window.__expFail(" + JSONObject.quote(t.toString()) + ")")
                }
            }
        }
        @JavascriptInterface fun exportFrame(n: Int, total: Int, ptsMs: Double) { exporter?.frame(n, total, ptsMs) }
        @JavascriptInterface fun exportEnd() { exporter?.finish() }
        @JavascriptInterface fun exportCancel() { exporter?.cancel(); exporter = null }
    }

    override fun onPause() {
        super.onPause()
        exporter?.let { if (it.failed == null) it.failed = "The app went to the background. Keep it open while exporting." }
    }

    private fun askPermissions() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) askCapture() else requestPermissions(need.toTypedArray(), REQ_PERM)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_PERM) askCapture() // if audio was denied, it records video only
    }

    private fun askCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAP)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            REQ_FILE -> {
                val uris = WebChromeClient.FileChooserParams.parseResult(res, data)
                remember(uris)
                fileCb?.onReceiveValue(uris)
                fileCb = null
            }
            REQ_CAP -> {
                if (res == RESULT_OK && data != null) {
                    val dm = DisplayMetrics()
                    windowManager.defaultDisplay.getRealMetrics(dm)
                    val i = Intent(this, RecService::class.java).setAction("start")
                        .putExtra("code", res).putExtra("data", data)
                        .putExtra("w", dm.widthPixels).putExtra("h", dm.heightPixels)
                        .putExtra("dpi", dm.densityDpi)
                        .putExtra("audio", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    startForegroundService(i)
                } else js("window.__recCancel&&window.__recCancel()")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
    }

    override fun onDestroy() {
        RecService.listener = null
        super.onDestroy()
    }
}
