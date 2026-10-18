package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [CourseNameReader] backed by ML Kit's on-device Chinese recogniser. The model is bundled, so a
 * scan never needs a network. Recognition runs synchronously here because every caller already
 * decides its own timeout around the frame it captured; an unbounded wait would stall the session.
 */
class MlKitCourseNameReader : CourseNameReader {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun read(frame: Bitmap): List<TextLine> {
        val done = CountDownLatch(1)
        val lines = AtomicReference<List<TextLine>>(emptyList())
        val failure = AtomicReference<Exception>()
        recognizer.process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener { result ->
                lines.set(result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        TextLine(line.text, Rect(box))
                    }
                })
            }
            .addOnFailureListener { failure.set(it) }
            .addOnCompleteListener { done.countDown() }
        check(done.await(RECOGNITION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "COURSE_OCR_TIMEOUT" }
        failure.get()?.let { throw IllegalStateException("COURSE_OCR_FAILED:${it.message}", it) }
        return lines.get()
    }

    companion object {
        const val RECOGNITION_TIMEOUT_MS = 8_000L
    }
}
