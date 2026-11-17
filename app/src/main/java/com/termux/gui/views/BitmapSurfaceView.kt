package com.termux.gui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import android.view.SurfaceView

class BitmapSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private data class Frame(val bitmap: Bitmap, val lock: Any)

    @Volatile private var frame: Frame? = null
    private val surfaceLock = Any()
    private val renderThread = HandlerThread("nexa-surface-render").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val drawTask = Runnable { drawFrame() }
    private var surfaceReady = false

    init {
        holder.addCallback(this)
        setWillNotDraw(true)
    }

    fun setFrame(bitmap: Bitmap, lock: Any = bitmap) {
        frame = Frame(bitmap, lock)
        scheduleDraw()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(surfaceLock) { surfaceReady = true }
        scheduleDraw()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        scheduleDraw()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(surfaceLock) { surfaceReady = false }
    }

    private fun drawFrame() {
        synchronized(surfaceLock) {
            if (!surfaceReady) return
            val current = frame ?: return
            synchronized(current.lock) {
                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) {
                        canvas.drawColor(0xff000000.toInt())
                        canvas.drawBitmap(current.bitmap, null,
                            Rect(0, 0, canvas.width, canvas.height), null)
                    }
                } finally {
                    if (canvas != null) holder.unlockCanvasAndPost(canvas)
                }
            }
        }
    }

    private fun scheduleDraw() {
        renderHandler.removeCallbacks(drawTask)
        renderHandler.post(drawTask)
    }

    override fun onDetachedFromWindow() {
        synchronized(surfaceLock) { surfaceReady = false }
        renderThread.quitSafely()
        super.onDetachedFromWindow()
    }
}
