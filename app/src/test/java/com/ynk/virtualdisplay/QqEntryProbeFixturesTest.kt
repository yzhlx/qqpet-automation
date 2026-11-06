package com.ynk.virtualdisplay

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cold-start entry probes against REAL device frames (720x1600), so a template or ROI that
 * does not match the page it is named for fails here instead of on the phone.
 *
 * 2026-11-06 the start from the QQ message tab died three times: first because the history-word
 * template was never shipped, then (INITIAL_PAGE_UNKNOWN) because QQ draws the message tab's
 * search box in a second layout that the first template scores only 0.92 on, then (UNKNOWN on
 * the search page) because the history word had been measured on a frame taken mid-animation.
 * All three are pinned.
 *
 * The matcher is replayed exactly as TemplateMatcher does it: 24x24 box-mean downsample, sum of
 * absolute channel differences, the same coarse vertical drifts, 0.95 gate.
 *
 * Frames (src/test/resources/qq-entry and /health-panel):
 *   message-tab-compact-vd48   message tab, search box y 214-286 (the layout that failed)
 *   message-tab-notice-vd47    message tab, search box y 237-307 (the layout the first probe came from)
 *   qq-main-display45          a message tab recorded on another display
 *   search-page-history-settled-vd48   QQ search page, settled, with the recent-searches word "宠物" (y 612-653)
 *   search-page-animating-vd47   the same page captured while it was still sliding in (word at y 751): the
 *                              frame the first history template was cropped from, NOT what the page settles to
 *   search-results-vd47        QQ search results page
 *   room / bath / room+sheet   pet pages (negatives)
 */
class QqEntryProbeFixturesTest {
    private val gate = 0.95

    private fun frame(resource: String): BufferedImage {
        val stream = javaClass.getResourceAsStream(resource) ?: error("missing fixture $resource")
        return stream.use { ImageIO.read(it) }.also {
            assertTrue("fixture must be a 720x1600 device frame: $resource", it.width == 720 && it.height == 1600)
        }
    }

    private fun template(name: String): BufferedImage {
        val file = File("src/main/assets/pet_experimental/$name.png")
        assertTrue("missing template: ${file.absolutePath}", file.isFile)
        return ImageIO.read(file)
    }

    /** The ROI a probe really uses, read from PetPageProbes so a coordinate move cannot pass silently. */
    private fun roi(name: String): IntArray {
        val text = File("src/main/java/com/ynk/virtualdisplay/automation/PetPageProbes.kt").readText()
        val m = Regex("""experimental\("$name",\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)\)""").find(text)
            ?: error("PetPageProbes has no experimental(\"$name\", ...) probe")
        return m.groupValues.drop(1).map { it.toInt() }.toIntArray()
    }

    private fun downsample(image: BufferedImage): IntArray {
        val size = 24
        val out = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val x0 = x * image.width / size
            val x1 = ((x + 1) * image.width / size).coerceIn(x0 + 1, image.width)
            val y0 = y * image.height / size
            val y1 = ((y + 1) * image.height / size).coerceIn(y0 + 1, image.height)
            var red = 0L; var green = 0L; var blue = 0L; var count = 0L
            for (py in y0 until y1) for (px in x0 until x1) {
                val c = image.getRGB(px, py)
                red += (c shr 16) and 0xFF; green += (c shr 8) and 0xFF; blue += c and 0xFF
                count++
            }
            out[y * size + x] = ((red / count).toInt() shl 16) or ((green / count).toInt() shl 8) or (blue / count).toInt()
        }
        return out
    }

    private fun similarity(live: BufferedImage, tpl: BufferedImage): Double {
        val a = downsample(live); val b = downsample(tpl)
        var diff = 0.0
        for (i in a.indices) {
            diff += abs(((a[i] shr 16) and 0xFF) - ((b[i] shr 16) and 0xFF))
            diff += abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF))
            diff += abs((a[i] and 0xFF) - (b[i] and 0xFF))
        }
        return (1.0 - diff / (24.0 * 24.0 * 3.0 * 255.0)).coerceIn(0.0, 1.0)
    }

    private val coarse = listOf(-64, -56, -48, -40, -32, -24, -16, -8, 8, 16, 24, 32, 40, 48, 56, 64)

    /** TemplateMatcher.match for a coarse (non-exact) probe: stops at the first pass. */
    private fun score(frame: BufferedImage, probe: String): Double {
        val r = roi(probe); val tpl = template(probe)
        val w = r[2] - r[0]; val h = r[3] - r[1]
        var best = similarity(frame.getSubimage(r[0], r[1], w, h), tpl)
        if (best >= gate) return best
        for (dy in coarse) {
            val top = r[1] + dy
            if (top < 0 || top + h > frame.height) continue
            val s = similarity(frame.getSubimage(r[0], top, w, h), tpl)
            if (s > best) best = s
            if (best >= gate) break
        }
        return best
    }

    private val boxProbes = listOf("qq_message_search_box", "qq_message_search_box_compact")
    private fun messageTabScore(frame: BufferedImage) = boxProbes.maxOf { score(frame, it) }

    private val compactTab by lazy { frame("/qq-entry/message-tab-compact-vd48.png") }
    private val noticeTab by lazy { frame("/qq-entry/message-tab-notice-vd47.png") }
    private val otherTab by lazy { frame("/health-panel/qq-main-display45.png") }
    private val searchPage by lazy { frame("/qq-entry/search-page-history-settled-vd48.png") }
    private val searchPageAnimating by lazy { frame("/qq-entry/search-page-animating-vd47.png") }
    private val searchResults by lazy { frame("/qq-entry/search-results-vd47.png") }
    private val room by lazy { frame("/health-panel/room-live-display47-2026-11-06.png") }
    private val bath by lazy { frame("/health-panel/panel-bath-display41.png") }
    private val roomWithSheet by lazy { frame("/health-panel/panel-room-display44-empty-mood.png") }

    @Test fun theLayoutThatFailedOnTheDeviceIsRecognisedAsTheMessageTab() {
        val s = score(compactTab, "qq_message_search_box_compact")
        assertTrue("compact-layout message tab must match its template, got $s", s >= gate)
        val old = score(compactTab, "qq_message_search_box")
        assertTrue("this is why the first template alone failed (it scored $old on this layout)", old < gate)
    }

    @Test fun theFirstLayoutIsStillRecognised() {
        val s = score(noticeTab, "qq_message_search_box")
        assertTrue("notice-layout message tab must match the first template, got $s", s >= gate)
    }

    @Test fun everyRecordedMessageTabIsRecognisedByEitherTemplate() {
        listOf("compact" to compactTab, "notice" to noticeTab, "display45" to otherTab).forEach { (name, f) ->
            val s = messageTabScore(f)
            assertTrue("message tab '$name' must be recognised, got $s", s >= gate)
        }
    }

    @Test fun noOtherPageReadsAsTheMessageTab() {
        listOf("search page" to searchPage, "search page (animating)" to searchPageAnimating,
            "search results" to searchResults, "pet room" to room,
            "bath scene" to bath, "room with health sheet" to roomWithSheet).forEach { (name, f) ->
            boxProbes.forEach { p ->
                val s = score(f, p)
                assertTrue("$p must not read the $name as the message tab, got $s", s < gate)
            }
        }
    }

    @Test fun theHistoryWordIsRecognisedOnTheSettledSearchPage() {
        val s = score(searchPage, "qq_search_history_pet")
        assertTrue("settled search page with the recent-searches word must match, got $s", s >= gate)
    }

    @Test fun theHistoryWordProbeAndTapSitWhereTheSettledPageDrawsIt() {
        val r = roi("qq_search_history_pet")
        assertTrue("ROI must be the settled position (top 612), got ${r.toList()}", r[1] == 612)
        val tapY = (r[1] + r[3]) / 2
        assertTrue("tap y $tapY must land on the word (y 612-653)", tapY in 612..653)
    }

    @Test fun theHalfAnimatedSearchPageIsNotTakenForTheSettledOne() {
        // The page is judged again after it settles (PetNavigation.ENTER_SETTLE_MS); a frame from
        // the slide-in must read as "not yet", never as a match that would tap a wrong y.
        val s = score(searchPageAnimating, "qq_search_history_pet")
        assertTrue("mid-animation frame must not match the settled-page template, got $s", s < gate)
    }

    @Test fun theHistoryWordIsNotSeenOnAnyOtherPage() {
        listOf("compact message tab" to compactTab, "notice message tab" to noticeTab,
            "display45 message tab" to otherTab, "search results" to searchResults, "pet room" to room,
            "bath scene" to bath, "room with health sheet" to roomWithSheet).forEach { (name, f) ->
            val s = score(f, "qq_search_history_pet")
            assertTrue("history word must not be seen on the $name, got $s", s < gate)
        }
    }
}
