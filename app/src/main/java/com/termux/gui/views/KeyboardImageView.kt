package com.termux.gui.views

import android.content.Context
import android.view.inputmethod.*
import androidx.appcompat.widget.AppCompatImageView
import com.termux.gui.protocol.shared.v0.RawInputConnection

/**
 * ImageView with InputConnection to get KeyEvents.
 */
class KeyboardImageView(c: Context) : AppCompatImageView(c) {
    private var keyListener: OnKeyListener? = null
    var textListener: ((String) -> Unit)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        val l = keyListener
        return if (l != null) {
            outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            RawInputConnection(l) { textListener?.invoke(it) }
        } else {
            super.onCreateInputConnection(outAttrs)
        }
    }

    override fun setOnKeyListener(l: OnKeyListener?) {
        keyListener = l;
        super.setOnKeyListener(l)
    }
    
}
