package top.sagrus.cam

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

class RecordService : Service() {
    companion object {
        const val ACTION_STOP = "stop"; const val EXTRA_CODE = "code"; const val EXTRA_DATA = "data"
        @Volatile var recording = false
        /** Область видео на экране в долях [left, top, right, bottom]. */
        @Volatile var crop = floatArrayOf(0f, 0f, 1f, 1f)
        var onState: ((Boolean) -> Unit)? = null
        private fun set(v: Boolean) { recording = v; onState?.invoke(v) }
    }
    private var rec: ScreenRecorder? = null
    private var proj: MediaProjection? = null
    private var pfd: ParcelFileDescriptor? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i?.action == ACTION_STOP) { stopRec(false); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "Запись", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "rec").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Идёт запись трансляции").build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        @Suppress("DEPRECATION") val data = i?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (i == null || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            startRec(i.getIntExtra(EXTRA_CODE, 0), data, i.getIntExtra("sw", 1080), i.getIntExtra("sh", 1920),
                i.getIntExtra("ow", 1280), i.getIntExtra("oh", 720), i.getIntExtra("dpi", 320))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка записи: ${e.message}", Toast.LENGTH_LONG).show(); stopRec(true); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRec(code: Int, data: Intent, sw: Int, sh: Int, ow: Int, oh: Int, dpi: Int) {
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        proj = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopRec(false); stopSelf() } }
        }, main)
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "sagrus_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SagrusCam")
        }
        val u: Uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
        val fd = contentResolver.openFileDescriptor(u, "rw")!!; pfd = fd
        val r = ScreenRecorder(p, sw, sh, ow, oh, dpi, fd.fileDescriptor, { msg ->
            main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); stopRec(true); stopSelf() }
        }) { crop }
        rec = r; r.start()
        set(true)
    }

    private fun stopRec(silent: Boolean) {
        val r = rec; rec = null
        val p = proj; proj = null
        r?.stop(); p?.stop()
        pfd?.close(); pfd = null
        if (recording) set(false)
        if (r != null && !silent) Toast.makeText(this, "Видео сохранено: Movies/SagrusCam", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() { stopRec(false); super.onDestroy() }
}
