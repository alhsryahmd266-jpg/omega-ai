package com.omega.keyboard.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.widget.EditText

/**
 * A text box that takes pictures the way the chat box of WhatsApp or Telegram does: it tells the keyboard which picture types it
 * accepts and hands every picture it receives to [onPicture]. The keyboard's coloured "send" puts its picture in here, so the
 * whole path (colour text -> picture -> chat box) can be tried (and tested on the emulator) without a chat app.
 */
class PictureField(ctx: Context) : EditText(ctx) {

    /** called on the main thread with the picture's address and type; true when it was taken */
    var onPicture: ((Uri, String) -> Boolean)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        if (Build.VERSION.SDK_INT < 25) return base
        try {
            EditorInfo::class.java.getField("contentMimeTypes").set(outAttrs, arrayOf("image/png", "image/gif", "image/jpeg", "image/webp", "image/webp.wasticker"))
        } catch (_: Throwable) {
            return base
        }
        return object : InputConnectionWrapper(base, false) {
            override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
                if ((flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                    try { info.requestPermission() } catch (_: Throwable) { return false }
                }
                val mime = if (info.description.mimeTypeCount > 0) info.description.getMimeType(0) else "image/*"
                val taken = try { onPicture?.invoke(info.contentUri, mime) ?: false } catch (_: Throwable) { false }
                if ((flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                    try { info.releasePermission() } catch (_: Throwable) {}
                }
                return taken
            }
        }
    }
}
