package com.adskipper.core.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

object ScreenshotCapturer {

    /**
     * Takes a full-screen screenshot via the accessibility service and
     * returns a software ARGB_8888 bitmap (usable by ML Kit and JNI).
     * Returns null on failure (e.g. FLAG_SECURE windows).
     */
    suspend fun capture(service: AccessibilityService): Bitmap? =
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
                        cont.resume(software)
                    }

                    override fun onFailure(errorCode: Int) {
                        Timber.d("takeScreenshot failed: %d", errorCode)
                        cont.resume(null)
                    }
                },
            )
        }
}
