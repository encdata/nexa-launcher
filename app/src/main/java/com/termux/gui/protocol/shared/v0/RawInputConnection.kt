package com.termux.gui.protocol.shared.v0

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.*

class RawInputConnection(
    private val keyListener: View.OnKeyListener,
    private val textListener: (String) -> Unit = {}
) : InputConnection {
    private var composingText = ""
    
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        return null
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        return null
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        return null
    }

    override fun getCursorCapsMode(reqModes: Int): Int {
        return 0
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        return null
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (composingText.isNotEmpty() && beforeLength > 0) {
            composingText = ""
        } else {
            if (beforeLength > 0) textListener("\u007f".repeat(beforeLength))
            if (afterLength > 0) textListener("\u001b[3~".repeat(afterLength))
        }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        return deleteSurroundingText(beforeLength, afterLength)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        composingText = text?.toString() ?: ""
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        return true
    }

    override fun finishComposingText(): Boolean {
        if (composingText.isNotEmpty()) textListener(composingText)
        composingText = ""
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text != null) textListener(text.toString())
        composingText = ""
        return true
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean {
        return false
    }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
        return false
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        return false
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        textListener("\r")
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean {
        return false
    }

    override fun beginBatchEdit(): Boolean {
        return false
    }

    override fun endBatchEdit(): Boolean {
        return false
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        keyListener.onKey(null, KeyEvent.KEYCODE_UNKNOWN, event)
        return true
    }

    override fun clearMetaKeyStates(states: Int): Boolean {
        return true
    }

    override fun reportFullscreenMode(enabled: Boolean): Boolean {
        return false
    }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean {
        return false
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        return false
    }

    override fun getHandler(): Handler? {
        return null
    }

    override fun closeConnection() {}

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
        return false
    }
}
