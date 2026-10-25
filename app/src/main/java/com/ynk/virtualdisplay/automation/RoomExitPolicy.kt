package com.ynk.virtualdisplay.automation

/** Geometry-only selector; it neither reads pixels nor sends input. Coordinates are display pixels. */
object RoomExitPolicy {
    data class Label(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Target(val x: Float, val y: Float)

    /**
     * Only the calibrated 720x1600 room is supported. Both drawer layouts put the 出门 TEXT inside
     * the lower centre navigation region (expanded ~y1305, collapsed ~y1445). An icon position is
     * deliberately not inferred from the label: the returned point is the label's own centre.
     * Duplicate, malformed or misplaced exact labels invalidate the observation, even when one
     * other candidate looks valid. Page identity must already have passed on this same frame.
     */
    fun select(labels: List<Label>, width: Int, height: Int): Target? {
        if (width != 720 || height != 1600) return null
        val exits = labels.filter { label -> label.text.filterNot { it.isWhitespace() } == "出门" }
        val label = exits.singleOrNull() ?: return null
        if (label.left < 240 || label.right > 480 || label.top < 1180 || label.bottom > 1530) return null
        val w = label.right - label.left
        val h = label.bottom - label.top
        if (w !in 32..180 || h !in 20..100) return null
        return Target((label.left + label.right) / 2f, (label.top + label.bottom) / 2f)
    }

    /** A drawer that moves while OCR runs must be re-observed, never clicked using the old point. */
    fun stable(first: Target, fresh: Target): Boolean =
        kotlin.math.abs(first.x - fresh.x) <= 12f && kotlin.math.abs(first.y - fresh.y) <= 12f
}
