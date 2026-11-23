package com.linedist.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.webkit.*
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null

    companion object {
        const val REQ_CAP = 1001
        const val REQ_FILE = 1002
        const val REQ_PERM = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                    "error" -> js("window.__recError&&window.__recError(${org.json.JSONObject.quote(msg ?: "")})")
                }
            }
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

    inner class Bridge {
        @JavascriptInterface fun start() { runOnUiThread { askPermissions() } }
        @JavascriptInterface fun stop() {
            runOnUiThread {
                startService(Intent(this@MainActivity, RecService::class.java).setAction("stop"))
            }
        }
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
            REQ_FILE -> { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); fileCb = null }
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
