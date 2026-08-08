package com.adskipper.core.detect

import android.graphics.Bitmap
import android.graphics.Point
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * L2: on-device OCR (ML Kit, Chinese model) for apps that hide their UI
 * tree (Flutter / Unity / games). ~50-150ms per frame.
 *
 * The bundled ML Kit artifact ships its model in the APK and needs no
 * Google Play services, but on stripped-down ROMs client construction can
 * still throw — that must disable L2 only, never abort the pipeline pass
 * it happens in (the caller treats null as "L2 missed" and falls through
 * to L3).
 */
class OcrDetector {

    suspend fun findSkipButton(
        bitmap: Bitmap,
        keywords: Collection<String>,
        excluded: Collection<String> = emptySet(),
    ): Point? {
        val client = recognizer ?: return null
        return suspendCancellableCoroutine { cont ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    var point: Point? = null
                    outer@ for (block in result.textBlocks) {
                        for (line in block.lines) {
                            if (!KeywordMatcher.isPlausibleButtonText(line.text)) continue
                            if (KeywordMatcher.matches(line.text, keywords, excluded) == null) continue
                            val box = line.boundingBox ?: continue
                            point = Point(box.centerX(), box.centerY())
                            break@outer
                        }
                    }
                    cont.resume(point)
                }
                .addOnFailureListener { e ->
                    Timber.w(e, "OCR failed")
                    cont.resume(null)
                }
        }
    }

    private companion object {
        /** Process-wide client: ML Kit recognizers are long-lived by design,
         *  and the previous per-pass getClient leaked one client per
         *  detection. Built lazily so a construction failure is paid (and
         *  logged) once, after which L2 stays off for the process. */
        val recognizer: TextRecognizer? by lazy {
            try {
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            } catch (t: Throwable) {
                Timber.e(t, "OCR client unavailable, L2 disabled")
                null
            }
        }
    }
}
