package com.ynk.virtualdisplay.automation

import android.graphics.Bitmap

/** Positive text evidence shared by ordinary learning observations and recovery. */
object StudyPanelText {
    private val timer=Regex("""(?<!\d)(?:\d{1,2}:)?[0-5]?\d:[0-5]\d(?!\d)""")
    fun confirms(lines: List<String>): Boolean =
        lines.any { it.replace(Regex("\\s+"), "").contains("正在学习") } &&
            lines.any { line -> timer.findAll(line).any { match ->
                match.value.split(':').any { it.toInt() > 0 }
            } }
}

/**
 * Read only the lower panel header band. Chat messages or historical lesson text elsewhere in the
 * page cannot supply a running-panel proof. The caller owns the original frame.
 */
class LearningPanelObserver(private val reader: CourseNameReader) {
    fun confirms(frame: Bitmap): Boolean {
        val header=Bitmap.createBitmap(frame,0,frame.height/2,frame.width,frame.height/4)
        return try { StudyPanelText.confirms(reader.read(header).map { it.text }) }
        finally { if(header!==frame) header.recycle() }
    }
}
