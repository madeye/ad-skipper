package com.adskipper.core.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.TextView
import timber.log.Timber

/**
 * Debug overlay shown on hit. Uses TYPE_ACCESSIBILITY_OVERLAY, which an
 * accessibility service can display WITHOUT SYSTEM_ALERT_WINDOW.
 */
class DebugOverlay(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    fun update(text: String) {
        handler.post {
            try {
                val wm = service.getSystemService(WindowManager::class.java)
                val v = view ?: TextView(service).apply {
                    setBackgroundColor(0xCC000000.toInt())
                    setTextColor(0xFF00FF00.toInt())
                    textSize = 12f
                    setPadding(24, 12, 24, 12)
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT,
                    )
                    wm.addView(this, lp)
                    view = this
                }
                v.text = text
            } catch (t: Throwable) {
                Timber.w(t, "overlay update failed")
            }
        }
    }

    fun dismiss() {
        handler.post {
            view?.let {
                try {
                    service.getSystemService(WindowManager::class.java).removeView(it)
                } catch (t: Throwable) {
                    Timber.w(t, "overlay dismiss failed")
                }
            }
            view = null
        }
    }
}
