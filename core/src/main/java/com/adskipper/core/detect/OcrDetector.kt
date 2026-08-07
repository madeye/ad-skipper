package com.adskipper.core.detect

import android.graphics.Bitmap
import android.graphics.Point
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * L2: on-device OCR (ML Kit, Chinese model) for apps that hide their UI
 * tree (Flutter / Unity / games). ~50-150ms per frame.
 */
class OcrDetector {

    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun findSkipButton(bitmap: Bitmap, keywords: Collection<String>): Point? =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    var point: Point? = null
                    outer@ for (block in result.textBlocks) {
                        for (line in block.lines) {
                            if (!KeywordMatcher.isPlausibleButtonText(line.text)) continue
                            if (KeywordMatcher.matches(line.text, keywords) == null) continue
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
