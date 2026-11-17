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
    private val renderThread = HandlerThread("nexa-hardware-frame").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val frameTask = Runnable { presentLatestFrame() }
    private var pendingFrame: Frame? = null
    private var buffer: HardwareBuffer? = null

    fun setFrame(bitmap: Bitmap, lock: Any) {
        synchronized(frameLock) { pendingFrame = Frame(bitmap, lock) }
        renderHandler.removeCallbacks(frameTask)
        renderHandler.post(frameTask)
    }

    private fun presentLatestFrame() {
        val frame = synchronized(frameLock) { pendingFrame } ?: return
        synchronized(frame.lock) {
            synchronized(RENDER_LOCK) {
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
            renderHandler.removeCallbacks(frameTask)
            renderHandler.post(frameTask)
        }
    }

    override fun onDetachedFromWindow() {
        renderThread.quitSafely()
        renderThread.join()
        super.onDetachedFromWindow()
        buffer?.close()
        buffer = null
    }
}
