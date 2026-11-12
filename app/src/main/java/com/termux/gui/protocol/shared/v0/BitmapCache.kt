package com.termux.gui.protocol.shared.v0

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.security.MessageDigest

object BitmapCache {
    private const val maxBytes = 4 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    @Synchronized
    fun decode(bytes: ByteArray): Bitmap? {
        val key = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        cache.get(key)?.let { return it }

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }
}
