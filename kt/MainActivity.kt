package top.sagrus.cam

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.view.PixelCopy
import android.webkit.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private val url = "https://sagrus.top/"
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var bar: View
    private lateinit var fab: View
    private lateinit var btnRec: Button
    private var fullscreen = false
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private val js = "var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
        "m.content='width=device-width,initial-scale=1,minimum-scale=0.5,maximum-scale=6,user-scalable=yes';"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root); web = findViewById(R.id.web); bar = findViewById(R.id.bar)
        fab = findViewById(R.id.fab); btnRec = findViewById(R.id.btnRec)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, u: String?) { v.evaluateJavascript(js, null) }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                customView = v; customCb = cb
                root.addView(v, FrameLayout.LayoutParams(-1, -1)); setFullscreen(true)
            }
            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }; customView = null
                customCb?.onCustomViewHidden(); customCb = null; setFullscreen(false)
            }
        }
        web.loadUrl(url)
        findViewById<View>(R.id.btnShot).setOnClickListener { screenshot() }
        btnRec.setOnClickListener { toggleRec() }
        findViewById<View>(R.id.btnZin).setOnClickListener { web.zoomIn() }
        findViewById<View>(R.id.btnZout).setOnClickListener { web.zoomOut() }
        findViewById<View>(R.id.btnFull).setOnClickListener { setFullscreen(true) }
        fab.setOnClickListener { if (customView != null) web.webChromeClient?.onHideCustomView() else setFullscreen(false) }
        findViewById<View>(R.id.btnExit).setOnClickListener { exitApp() }
    }

    override fun onResume() {
        super.onResume()
        RecordService.onState = { rec -> runOnUiThread { showRec(rec) } }
        showRec(RecordService.recording)
    }

    override fun onDestroy() { RecordService.onState = null; web.destroy(); super.onDestroy() }

    @Deprecated("Deprecated in Java") override fun onBackPressed() {
        if (customView != null) web.webChromeClient?.onHideCustomView()
        else if (fullscreen) setFullscreen(false)
        else toast("Для выхода нажмите ✖ Выход")
    }

    private fun showRec(on: Boolean) {
        btnRec.isActivated = on; btnRec.text = if (on) "⏹\nСтоп" else "⏺\nЗапись"
    }

    @Suppress("DEPRECATION")
    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        bar.visibility = if (on) View.GONE else View.VISIBLE
        fab.visibility = if (on) View.VISIBLE else View.GONE
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(!on)
            window.insetsController?.let {
                if (on) { it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else it.show(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (on) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION else 0
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun screenshot() {
        val loc = IntArray(2); web.getLocationInWindow(loc)
        val r = Rect(loc[0], loc[1], loc[0] + web.width, loc[1] + web.height)
        val bmp = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, r, bmp, { res ->
            if (res == PixelCopy.SUCCESS) saveBitmap(bmp) else toast("Не удалось сделать снимок")
        }, Handler(Looper.getMainLooper()))
    }

    private fun saveBitmap(bmp: Bitmap) {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "sagrus_${stamp()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SagrusCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) { toast("Ошибка сохранения"); return }
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        toast("Снимок сохранён: Pictures/SagrusCam")
    }

    private fun toggleRec() {
        if (RecordService.recording) {
            startService(Intent(this, RecordService::class.java).setAction(RecordService.ACTION_STOP)); return
        }
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 2) else askProjection()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askProjection()
        else toast("Нужно разрешение на звук")
    }

    private fun askProjection() {
        val m = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION") startActivityForResult(m.createScreenCaptureIntent(), 1)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK && data != null) {
            val v = window.decorView
            startForegroundService(Intent(this, RecordService::class.java)
                .putExtra(RecordService.EXTRA_CODE, res).putExtra(RecordService.EXTRA_DATA, data)
                .putExtra("w", v.width).putExtra("h", v.height).putExtra("dpi", resources.displayMetrics.densityDpi))
        }
    }

    private fun exitApp() {
        if (RecordService.recording) startService(Intent(this, RecordService::class.java).setAction(RecordService.ACTION_STOP))
        finishAndRemoveTask()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
