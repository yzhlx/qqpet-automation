package com.ynk.virtualdisplay

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The room page identity probe must match the CURRENT pet skin and must not match the QQ
 * host pages. It used to ship a template cropped from an older skin (a blue bed and a cat),
 * so on the halloween skin it scored 0.68 against the real room and every managed session died
 * with INITIAL_PAGE_UNKNOWN even though the pet room was open on the virtual display.
 *
 * The false 'room fixture' that hid this was itself a QQ message-list frame: the calibration
 * had cropped the template from a page that was not the room at all, which is why the template
 * matched its own source perfectly while never matching the room.
 *
 * The ROI is asserted against PetPageProbes.room so this test fails if the coordinates move
 * without the template being recalibrated.
 *
 * Fixtures are real device frames, 720x1600:
 *   /health-panel/room-live-display46.png              the room, health sheet closed (this device)
 *   /health-panel/panel-room-display44-empty-mood.png  the room, health sheet open
 *   /health-panel/qq-main-display45.png                a QQ message list (negative)
 *   /health-panel/panel-bath-display41.png              the bath scene (negative)
 */
class RoomPageProbeSkinTest {

    private fun image(resource: String): BufferedImage {
        val stream = javaClass.getResourceAsStream(resource) ?: error("missing fixture " + resource)
        return stream.use { ImageIO.read(it) }
    }

    /** Box-average downsample to 24x24: the shape TemplateMatcher compares. */
    private fun downsample(image: BufferedImage, size: Int): IntArray {
        val out = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val x0 = x * image.width / size
            val x1 = ((x + 1) * image.width / size).coerceIn(x0 + 1, image.width)
            val y0 = y * image.height / size
            val y1 = ((y + 1) * image.height / size).coerceIn(y0 + 1, image.height)
            var red = 0L; var green = 0L; var blue = 0L; var count = 0L
            for (py in y0 until y1) for (px in x0 until x1) {
                val color = image.getRGB(px, py)
                red += (color shr 16) and 0xFF; green += (color shr 8) and 0xFF; blue += color and 0xFF
                count++
            }
            out[y * size + x] = ((red / count).toInt() shl 16) or ((green / count).toInt() shl 8) or
                (blue / count).toInt()
        }
        return out
    }

    private fun similarity(live: BufferedImage, template: BufferedImage): Double {
        val a = downsample(live, 24); val b = downsample(template, 24)
        var diff = 0.0
        for (i in a.indices) {
            diff += abs(((a[i] shr 16) and 0xFF) - ((b[i] shr 16) and 0xFF)).toDouble()
            diff += abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF)).toDouble()
            diff += abs((a[i] and 0xFF) - (b[i] and 0xFF)).toDouble()
        }
        val max = 24.0 * 24.0 * 3.0 * 255.0
        return (1.0 - diff / max).coerceIn(0.0, 1.0)
    }

    private fun score(frame: BufferedImage, template: BufferedImage, rect: IntArray): Double {
        val live = frame.getSubimage(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
        var best = similarity(live, template)
        if (best >= 0.95) return best
        for (dy in listOf(-64,-56,-48,-40,-32,-24,-16,-8,8,16,24,32,40,48,56,64)) {
            val top = rect[1] + dy
            if (top < 0 || top + rect[3] - rect[1] > frame.height) continue
            val shifted = frame.getSubimage(rect[0], top, rect[2] - rect[0], rect[3] - rect[1])
            val s = similarity(shifted, template)
            if (s > best) best = s
        }
        return best
    }

    /** Reads the shipped template directly from the repo (JVM tests have no Android assets). */
    private val template: BufferedImage by lazy { read("room.png") }

    /** The second shipped skin: the cat room this account is on since 2026-11-06. */
    private val catTemplate: BufferedImage by lazy { read("room_skin_cat.png") }

    private fun read(name: String): BufferedImage {
        val file = java.io.File("src/main/assets/pet_experimental/" + name)
        assertTrue("missing room template: " + file.absolutePath, file.exists())
        return ImageIO.read(file)
    }

    /** The ROI PetPageProbes.room uses; asserted so a coordinate move cannot pass silently. */
    private val roomRect = intArrayOf(20, 720, 120, 840)

    @Test fun theRoomRoiIsTheOneTheCodeUses() {
        val source = java.io.File("src/main/java/com/ynk/virtualdisplay/automation/PetPageProbes.kt")
        val text = source.readText()
        assertTrue(
            "PetPageProbes.room ROI must stay 20,720,120,840 unless the template is recalibrated",
            text.contains("experimental(\"room\", 20, 720, 120, 840)"),
        )
    }

    @Test fun theProbeMatchesTheLiveRoomOnTheCurrentSkin() {
        val live = image("/health-panel/room-live-display46.png")
        val s = score(live, template, roomRect)
        assertTrue("room probe must match the live room page, got " + s, s >= 0.95)
    }

    /**
     * The account switched from the halloween skin to the cat skin on 2026-11-06. The halloween
     * template scored 0.63 on the cat-skin room, so every managed session died with
     * INITIAL_PAGE_UNKNOWN while the pet room was open. The cat skin therefore ships its own
     * template at the same ROI, and the room is proven by whichever skin is live.
     */
    @Test fun theCatSkinProbeMatchesTheCatSkinRoom() {
        val live = image("/health-panel/room-live-display47-cat-skin.png")
        val s = score(live, catTemplate, roomRect)
        assertTrue("cat-skin probe must match the cat-skin room, got " + s, s >= 0.95)
    }

    @Test fun theCatSkinProbeDoesNotMatchTheHalloweenRoom() {
        val live = image("/health-panel/room-live-display46.png")
        val s = score(live, catTemplate, roomRect)
        assertTrue("cat-skin probe must not claim the halloween room, got " + s, s < 0.95)
    }

    @Test fun theCatSkinProbeDoesNotMatchTheQqHostPages() {
        val qqMain = image("/health-panel/qq-main-display45.png")
        val s = score(qqMain, catTemplate, roomRect)
        assertTrue("QQ message list must not read as the cat-skin room, got " + s, s < 0.95)
    }

    @Test fun theCatSkinProbeDoesNotMatchTheBathScene() {
        val bath = image("/health-panel/panel-bath-display41.png")
        val s = score(bath, catTemplate, roomRect)
        assertTrue("bath scene must not read as the cat-skin room, got " + s, s < 0.95)
    }

    @Test fun theProbeAlsoMatchesTheRoomWithTheHealthSheetOpen() {
        val sheet = image("/health-panel/panel-room-display44-empty-mood.png")
        val s = score(sheet, template, roomRect)
        assertTrue("room probe must survive the health sheet, got " + s, s >= 0.95)
    }

    @Test fun theProbeDoesNotMatchTheQqHostPages() {
        val qqMain = image("/health-panel/qq-main-display45.png")
        val s = score(qqMain, template, roomRect)
        assertTrue("QQ message list must not read as the room page, got " + s, s < 0.95)
    }

    @Test fun theProbeDoesNotMatchTheBathScene() {
        val bath = image("/health-panel/panel-bath-display41.png")
        val s = score(bath, template, roomRect)
        assertTrue("bath scene must not read as the room page, got " + s, s < 0.95)
    }

    @Test fun theTemplateIsNotTheOldSkin() {
        val oldSkin = java.io.File("src/test/resources/health-panel/room-halloween-display45.png")
        assertTrue("the mislabelled QQ frame must not come back", !oldSkin.exists())
    }

    @Test fun theProbeLeavesRoomUnderTheShippingGate() {
        val live = image("/health-panel/room-live-display46.png")
        val qqMain = image("/health-panel/qq-main-display45.png")
        val room = score(live, template, roomRect)
        val qq = score(qqMain, template, roomRect)
        assertTrue("room must clear the 0.95 gate with margin, got " + room, room >= 0.97)
        assertTrue("QQ page must stay clearly below the gate, got " + qq, qq <= 0.90)
    }
}
