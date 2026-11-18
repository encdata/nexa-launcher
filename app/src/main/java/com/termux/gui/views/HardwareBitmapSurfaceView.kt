package com.termux.gui.views

import android.content.Context
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.HandlerThread
import com.termux.gui.hbuffers.HBuffers

class HardwareBitmapSurfaceView(context: Context) : HardwareBufferSurfaceView(context) {
    private data class Frame(val bitmap: Bitmap, val lock: Any)

    private val frameLock = Any()
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private val frameTask = Runnable { presentLatestFrame() }
    private var pendingFrame: Frame? = null
    private var buffer: HardwareBuffer? = null
    @Volatile private var detached = false

    init {
        startRenderThread()
    }

    private fun startRenderThread() {
        val thread = HandlerThread("nexa-hardware-frame").apply { start() }
        renderThread = thread
        renderHandler = Handler(thread.looper)
    }

    fun setFrame(bitmap: Bitmap, lock: Any) {
        synchronized(frameLock) {
            if (detached) return
            pendingFrame = Frame(bitmap, lock)
            renderHandler?.let {
                it.removeCallbacks(frameTask)
                it.post(frameTask)
            }
        }
    }

    fun clearFrame() {
        val frame = synchronized(frameLock) {
            val current = pendingFrame
            pendingFrame = null
            renderHandler?.removeCallbacks(frameTask)
            current
        }
        if (frame != null) synchronized(frame.lock) {}
    }

    private fun presentLatestFrame() {
        if (detached) return
        val frame = synchronized(frameLock) { pendingFrame } ?: return
        synchronized(frame.lock) {
            synchronized(RENDER_LOCK) {
                if (detached || synchronized(frameLock) { pendingFrame !== frame }) return
                val current = buffer
                if (current == null || current.width != frame.bitmap.width
                    || current.height != frame.bitmap.height) {
                    val replacement = HardwareBuffer.create(
                        frame.bitmap.width,
                        frame.bitmap.height,
                        HardwareBuffer.RGBA_8888,
                        1,
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                            or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                            or HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                    )
                    if (HBuffers.copyBitmapToBuffer(frame.bitmap, replacement) != 0) {
                        replacement.close()
                        return
                    }
                    setBuffer(replacement)
                    buffer = replacement
                    current?.close()
                } else {
                    if (HBuffers.copyBitmapToBuffer(frame.bitmap, current) != 0) return
                    refreshBufferContents()
                }
            }
        }
        val hasNewFrame = synchronized(frameLock) { pendingFrame !== frame }
        if (hasNewFrame) {
            synchronized(frameLock) {
                renderHandler?.let {
                    it.removeCallbacks(frameTask)
                    it.post(frameTask)
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        synchronized(frameLock) {
            detached = false
            if (renderThread?.isAlive != true) startRenderThread()
        }
    }

    override fun onDetachedFromWindow() {
        synchronized(frameLock) {
            detached = true
            pendingFrame = null
            renderHandler?.removeCallbacks(frameTask)
            renderThread?.quitSafely()
            renderHandler = null
            renderThread = null
        }
        synchronized(RENDER_LOCK) {
            super.onDetachedFromWindow()
            clearBuffer()
            buffer?.close()
            buffer = null
        }
    }
}
