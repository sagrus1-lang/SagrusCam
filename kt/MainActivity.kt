package top.sagrus.cam

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.*
import android.view.PixelCopy
import android.webkit.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private val html = "<!DOCTYPE html><html><head>" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=8,user-scalable=yes\">" +
        "<style>html,body{margin:0;height:100%;background:#000}iframe{display:block;border:0;width:100%;height:100%}</style></head><body>" +
        "<iframe src=\"https://rtsp.ru/embed/TASDYYEA/\" allow=\"autoplay; fullscreen; encrypted-media; picture-in-picture\" allowfullscreen></iframe>" +
        "</body></html>"

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var videoHost: FrameLayout
    private lateinit var logo: View
    private lateinit var ovl: View
    private lateinit var ovRec: ImageButton
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root); web = findViewById(R.id.web); videoHost = findViewById(R.id.videoHost)
        logo = findViewById(R.id.logo); ovl = findViewById(R.id.ovl); ovRec = findViewById(R.id.ovRec)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        web.setBackgroundColor(0xFF000000.toInt())
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                customView = v; customCb = cb
                root.addView(v, FrameLayout.LayoutParams(-1, -1))
                logo.bringToFront(); ovl.bringToFront()
            }
            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }; customView = null
                customCb?.onCustomViewHidden(); customCb = null
            }
        }
        // Окно видео всегда строго 16:9, чтобы запись и снимок брали только картинку
        videoHost.addOnLayoutChangeListener { _, l, t, r, bt, _, _, _, _ ->
            val hw = r - l; val hh = bt - t
            if (hw > 0 && hh > 0) {
                val w = minOf(hw, hh * 16 / 9); val h = w * 9 / 16
                val lp = web.layoutParams as FrameLayout.LayoutParams
                if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER; web.layoutParams = lp }
            }
        }
        val upd = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (RecordService.recording) updateCrop() }
        web.addOnLayoutChangeListener(upd); root.addOnLayoutChangeListener(upd)

        web.loadDataWithBaseURL("https://sagrus.top/", html, "text/html", "utf-8", null)

        findViewById<View>(R.id.ovShot).setOnClickListener { screenshot() }
        ovRec.setOnClickListener { toggleRec() }
        findViewById<View>(R.id.ovZin).setOnClickListener { web.zoomIn() }
        findViewById<View>(R.id.ovZout).setOnClickListener { web.zoomOut() }
        findViewById<View>(R.id.ovClose).setOnClickListener { exitApp() }
        immersive()
    }

    override fun onResume() {
        super.onResume()
        RecordService.onState = { rec -> runOnUiThread { showRec(rec) } }
        showRec(RecordService.recording)
        immersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onDestroy() { RecordService.onState = null; web.destroy(); super.onDestroy() }

    @Deprecated("Deprecated in Java") override fun onBackPressed() {
        if (customView != null) web.webChromeClient?.onHideCustomView()
        else toast("Для выхода нажмите ✕")
    }

    private fun showRec(on: Boolean) {
        ovRec.isActivated = on
        ovRec.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_rec)
        logo.visibility = if (on) View.INVISIBLE else View.VISIBLE      // логотип не попадает в запись
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** Всегда полный экран без системных панелей. */
    @Suppress("DEPRECATION")
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** Прямоугольник с видео (screen=true: координаты экрана, false: координаты окна). */
    private fun videoRect(screen: Boolean): Rect {
        val loc = IntArray(2)
        if (customView != null) {
            if (screen) root.getLocationOnScreen(loc) else root.getLocationInWindow(loc)
            val rw = root.width; val rh = root.height
            val w = minOf(rw, rh * 16 / 9); val h = w * 9 / 16
            val l = loc[0] + (rw - w) / 2; val t = loc[1] + (rh - h) / 2
            return Rect(l, t, l + w, t + h)
        }
        if (screen) web.getLocationOnScreen(loc) else web.getLocationInWindow(loc)
        return Rect(loc[0], loc[1], loc[0] + web.width, loc[1] + web.height)
    }

    @Suppress("DEPRECATION")
    private fun realSize(): DisplayMetrics { val dm = DisplayMetrics(); windowManager.defaultDisplay.getRealMetrics(dm); return dm }

    private fun updateCrop() {
        val dm = realSize(); val r = videoRect(true)
        RecordService.crop = floatArrayOf(r.left.toFloat() / dm.widthPixels, r.top.toFloat() / dm.heightPixels,
            r.right.toFloat() / dm.widthPixels, r.bottom.toFloat() / dm.heightPixels)
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun restoreOverlays() {
        ovl.visibility = View.VISIBLE
        logo.visibility = if (RecordService.recording) View.INVISIBLE else View.VISIBLE
    }

    private fun screenshot() {
        ovl.visibility = View.INVISIBLE; logo.visibility = View.INVISIBLE   // на снимке только видео
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({
            val r = videoRect(false)
            if (r.width() <= 0 || r.height() <= 0) { restoreOverlays(); return@postDelayed }
            val bmp = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, r, bmp, { res ->
                restoreOverlays()
                if (res == PixelCopy.SUCCESS) saveBitmap(bmp) else toast("Не удалось сделать снимок")
            }, h)
        }, 120)
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
            val dm = realSize(); updateCrop()
            val r = videoRect(true)
            // Только стандартные размеры кадра: 1920x1080 или 1280x720
            val big = r.width() >= 1500
            val ow = if (big) 1920 else 1280
            val oh = if (big) 1080 else 720
            startForegroundService(Intent(this, RecordService::class.java)
                .putExtra(RecordService.EXTRA_CODE, res).putExtra(RecordService.EXTRA_DATA, data)
                .putExtra("sw", dm.widthPixels).putExtra("sh", dm.heightPixels)
                .putExtra("ow", ow).putExtra("oh", oh).putExtra("dpi", dm.densityDpi))
        }
    }

    private fun exitApp() {
        if (RecordService.recording) startService(Intent(this, RecordService::class.java).setAction(RecordService.ACTION_STOP))
        finishAndRemoveTask()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
