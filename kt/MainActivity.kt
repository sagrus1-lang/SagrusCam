package top.sagrus.cam

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
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
    private lateinit var header: View
    private lateinit var bar: View
    private lateinit var ovl: LinearLayout
    private lateinit var btnRec: Button
    private lateinit var ovRec: ImageButton
    private var fullscreen = false
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root); web = findViewById(R.id.web); videoHost = findViewById(R.id.videoHost)
        header = findViewById(R.id.header); bar = findViewById(R.id.bar); ovl = findViewById(R.id.ovl)
        btnRec = findViewById(R.id.btnRec); ovRec = findViewById(R.id.ovRec)
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
                root.addView(v, FrameLayout.LayoutParams(-1, -1)); setFullscreen(true)
            }
            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }; customView = null
                customCb?.onCustomViewHidden(); customCb = null; setFullscreen(false)
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

        click(R.id.btnShot, R.id.ovShot) { screenshot() }
        click(R.id.btnRec, R.id.ovRec) { toggleRec() }
        click(R.id.btnZin, R.id.ovZin) { web.zoomIn() }
        click(R.id.btnZout, R.id.ovZout) { web.zoomOut() }
        click(R.id.btnFull) { setFullscreen(true) }
        click(R.id.ovFull) { if (customView != null) web.webChromeClient?.onHideCustomView() else setFullscreen(false) }
        click(R.id.btnExit) { exitApp() }
        applyOverlay()
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) setFullscreen(true)
    }

    private fun click(vararg ids: Int, f: () -> Unit) {
        ids.forEach { findViewById<View>(it).setOnClickListener { f() } }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if (customView == null) setFullscreen(c.orientation == Configuration.ORIENTATION_LANDSCAPE)
        applyOverlay()
    }

    /** Кнопки в полном экране: снизу (вертикально) или справа (горизонтально), чтобы не лезть на видео. */
    private fun applyOverlay() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        ovl.orientation = if (land) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val lp = ovl.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (land) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.setMargins(0, 0, if (land) dp(8) else 0, if (land) 0 else dp(48))
        ovl.layoutParams = lp
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
        else toast("Для выхода нажмите ВЫХОД")
    }

    private fun showRec(on: Boolean) {
        btnRec.isActivated = on; btnRec.text = if (on) "Стоп" else "Запись"
        btnRec.setCompoundDrawablesWithIntrinsicBounds(0, if (on) R.drawable.ic_stop else R.drawable.ic_rec, 0, 0)
        ovRec.isActivated = on; ovRec.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_rec)
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @Suppress("DEPRECATION")
    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        header.visibility = if (on) View.GONE else View.VISIBLE
        bar.visibility = if (on) View.GONE else View.VISIBLE
        ovl.visibility = if (on) View.VISIBLE else View.GONE
        if (on) ovl.bringToFront()
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

    private fun screenshot() {
        val wasOvl = ovl.visibility == View.VISIBLE
        if (wasOvl) ovl.visibility = View.INVISIBLE
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({
            val r = videoRect(false)
            if (r.width() <= 0 || r.height() <= 0) { if (wasOvl) ovl.visibility = View.VISIBLE; return@postDelayed }
            val bmp = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, r, bmp, { res ->
                if (wasOvl) ovl.visibility = View.VISIBLE
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
            var ow = minOf(1920, r.width()) / 2 * 2
            if (ow < 64) ow = 1280
            val oh = (ow * 9 / 16) / 2 * 2
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
