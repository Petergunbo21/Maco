package com.example.macrorecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager

/** Foreground service that mirrors the screen into [Capture] so scripts can read colors. */
class CaptureService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.macrorecorder.STOP_CAPTURE"
        private const val CHANNEL = "capture"
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var bmp: Bitmap? = null
    private var lastFrame = 0L

    private val thread = HandlerThread("capture").also { it.start() }
    private val handler = Handler(thread.looper)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Macro Recorder")
            .setContentText("Screen capture is on (for color detection)")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        projection = p
        // Android 14 needs a callback registered before the virtual display is created.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)
        build()
        return START_NOT_STICKY
    }

    /** (Re)creates the frame reader at the current screen size. Also runs after rotation. */
    @Suppress("DEPRECATION")
    private fun build() {
        val p = projection ?: return
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels
        val h = dm.heightPixels

        reader?.close()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
        reader = r
        bmp = null

        val d = display
        if (d == null) {
            display = p.createVirtualDisplay(
                "macro-capture", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
            )
        } else {
            d.resize(w, h, dm.densityDpi)
            d.surface = r.surface
        }
    }

    private fun onFrame(rd: ImageReader) {
        val img = rd.acquireLatestImage() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastFrame < 40) return // about 25 frames per second is plenty
            lastFrame = now

            val plane = img.planes[0]
            val bw = plane.rowStride / plane.pixelStride
            var b = bmp
            if (b == null || b.width != bw || b.height != img.height) {
                b = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
                bmp = b
            }
            synchronized(Capture.lock) {
                plane.buffer.rewind()
                b.copyPixelsFromBuffer(plane.buffer)
                Capture.width = img.width
                Capture.height = img.height
                Capture.bitmap = b
            }
        } finally {
            img.close()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection != null) handler.post { build() }
    }

    override fun onDestroy() {
        Capture.clear()
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null
        reader = null
        projection = null
        thread.quitSafely()
        super.onDestroy()
    }
}
