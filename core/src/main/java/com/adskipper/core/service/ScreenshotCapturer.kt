package com.adskipper.core.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

object ScreenshotCapturer {

    /** takeScreenshot on some OEM builds (observed on HyperOS during launch
     *  transitions) occasionally never invokes the callback. Without a
     *  timeout the awaiting detection coroutine would suspend forever,
     *  wedging the pipeline's processing lock for the rest of the splash
     *  window — the ad then plays out untouched. */
    private const val TIMEOUT_MS = 2000L

    /**
     * Takes a full-screen screenshot via the accessibility service and
     * returns a software ARGB_8888 bitmap (usable by ML Kit and JNI).
     * Returns null on failure (e.g. FLAG_SECURE windows) or timeout.
     */
    suspend fun capture(service: AccessibilityService): Bitmap? =
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                service.takeScreenshot(
                    /* displayId = */ 0,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val hardware = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace)
                            result.hardwareBuffer.close()
                            val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                            // The timeout may have fired while the callback was
                            // in flight; only the first resume wins.
                            if (cont.isActive) cont.resume(software)
                        }

                        override fun onFailure(errorCode: Int) {
                            Timber.d("takeScreenshot failed: %d", errorCode)
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        }.also { if (it == null) Timber.d("screenshot unavailable (failure or timeout)") }
}
