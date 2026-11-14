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
    private val roomRect = intArrayOf(130, 600, 250, 660)

    /** The cat-skin probe keeps the old chair-arm ROI; it is traceability only, not identity. */
    private val catRect = intArrayOf(20, 720, 120, 840)

    /** The pre-9.3.80 room ROI, now covered by QQ's hat icon. Kept to pin the defect. */
    private val oldRoomRect = intArrayOf(20, 720, 120, 840)

    @Test fun theRoomRoiIsTheOneTheCodeUses() {
        val source = java.io.File("src/main/java/com/ynk/virtualdisplay/automation/PetPageProbes.kt")
        val text = source.readText()
        assertTrue(
            "PetPageProbes.room ROI must stay 130,600,250,660 unless the template is recalibrated",
            text.contains("experimental(\"room\", 130, 600, 250, 660)"),
        )
    }

    @Test fun theProbeMatchesTheLiveRoomOnTheCurrentSkin() {
        val live = image("/health-panel/room-live-display46.png")
        val s = score(live, template, roomRect)
        assertTrue("room probe must match the live room page, got " + s, s >= 0.95)
    }

    /**
     * The frame kept as the cat-skin room is not the pet room at all: it is a QQ short-video feed
     * page, with its own like, comment and share chrome visible. room_skin_cat.png was cropped
     * from it, so it matches that page exactly and nothing else, and it scored 0.672 on the live
     * room frame captured 2026-11-06. While it sat in PetPageProbes.roomProbes any QQ video page
     * read as the pet room and the flows ran room actions on it. Measured by replaying the
     * shipping matcher over both frames.
     */
    @Test fun theCatSkinTemplateIsNotTheLiveRoom() {
        val live = image("/health-panel/room-live-display47-2026-11-06.png")
        val s = score(live, catTemplate, catRect)
        assertTrue("the video-feed template must not claim the live room, got " + s, s < 0.95)
    }

    /** The shipped room template against the room that is on the display right now. */
    @Test fun theShippedRoomTemplateMatchesTheLiveRoom() {
        val live = image("/health-panel/room-live-display47-2026-11-06.png")
        val s = score(live, template, roomRect)
        assertTrue("room must match the live room frame, got " + s, s >= 0.95)
    }

    /**
     * Regression lock for the defect itself: a QQ short-video page used to satisfy the room
     * identity set. The room template must never read that page as the pet room.
     */
    @Test fun theVideoFeedFrameIsNotReadAsRoom() {
        val video = image("/health-panel/room-live-display47-cat-skin.png")
        val s = score(video, template, roomRect)
        assertTrue("a QQ short-video page must not read as the room, got " + s, s < 0.95)
    }

    /**
     * Room identity is the shipped template alone. Adding a second template here is only safe
     * when that template was cropped from a real room frame; this test fails if a template of
     * unproven origin is put back into the identity set.
     */
    @Test fun roomIdentityUsesOnlyTheShippedTemplate() {
        val source = java.io.File("src/main/java/com/ynk/virtualdisplay/automation/PetPageProbes.kt")
        val text = source.readText()
        assertTrue(
            "roomProbes must stay listOf(room) unless a second template is proven on a real room frame",
            text.contains("val roomProbes: List<PetProfile.Probe> = listOf(room)"),
        )
    }

    @Test fun theCatSkinProbeDoesNotMatchTheHalloweenRoom() {
        val live = image("/health-panel/room-live-display46.png")
        val s = score(live, catTemplate, catRect)
        assertTrue("cat-skin probe must not claim the halloween room, got " + s, s < 0.95)
    }

    @Test fun theCatSkinProbeDoesNotMatchTheQqHostPages() {
        val qqMain = image("/health-panel/qq-main-display45.png")
        val s = score(qqMain, catTemplate, catRect)
        assertTrue("QQ message list must not read as the cat-skin room, got " + s, s < 0.95)
    }

    @Test fun theCatSkinProbeDoesNotMatchTheBathScene() {
        val bath = image("/health-panel/panel-bath-display41.png")
        val s = score(bath, catTemplate, catRect)
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

    // ---- QQ 9.3.80 left icon column (issue #5) ------------------------------------------------

    /**
     * QQ 9.3.80 added an icon to its left column and the hat icon moved into the old ROI. This
     * frame is the live 9.3.80 room (display 3, 2026-11-14) with the header and the account
     * avatar icon painted grey; the ROI pixels are untouched.
     */
    @Test fun theProbeMatchesTheQq9380RoomLayout() {
        val live = image("/health-panel/room-qq9380-display3.png")
        val s = score(live, template, roomRect)
        assertTrue("room probe must match the QQ 9.3.80 room, got " + s, s >= 0.95)
    }

    /** The defect itself: the old chair-arm ROI is covered by QQ's hat icon on 9.3.80. */
    @Test fun theOldRoiIsCoveredByQqChromeOnQq9380() {
        val live = image("/health-panel/room-qq9380-display3.png")
        val old = image("/health-panel/room-live-display47-2026-11-06.png")
        val oldTemplate = old.getSubimage(oldRoomRect[0], oldRoomRect[1],
            oldRoomRect[2] - oldRoomRect[0], oldRoomRect[3] - oldRoomRect[1])
        val s = score(live, oldTemplate, oldRoomRect)
        assertTrue("the old ROI must be the reason 9.3.80 failed (expected < 0.95), got " + s, s < 0.95)
    }

    /** The new ROI must stay right of QQ's left icon column, which now reaches y≈800. */
    @Test fun theRoiStaysRightOfQqLeftIconColumnInBothLayouts() {
        val iconColumnRight = 128 // icon circles end at x≈123, the red badge included
        assertTrue("room ROI must start right of QQ's icon column", roomRect[0] >= iconColumnRight)
    }

    /** Old-layout rooms recorded on this device must not regress (androidTest pet_pages). */
    @Test fun everyRecordedRoomFrameStillMatches() {
        val dir = java.io.File("src/androidTest/assets/pet_pages")
        val rooms = dir.listFiles { f -> f.name.startsWith("room-") }!!.sortedBy { it.name }
        assertTrue("expected the recorded room frames", rooms.size >= 5)
        for (f in rooms) {
            val s = score(ImageIO.read(f), template, roomRect)
            assertTrue("recorded room frame ${f.name} must still match, got $s", s >= 0.95)
        }
    }

    /** No recorded non-room page may read as the room with the moved ROI. */
    @Test fun noRecordedNonRoomFrameMatches() {
        val frames = buildList {
            java.io.File("src/androidTest/assets/pet_pages").listFiles { f ->
                f.name.startsWith("bath-") || f.name.startsWith("food-") || f.name.startsWith("academy-")
            }!!.forEach { add(it) }
            java.io.File("src/test/resources/qq-entry").listFiles()!!.forEach { add(it) }
            java.io.File("src/test/resources/exchange-dialog").listFiles()!!.forEach { add(it) }
            add(java.io.File("src/test/resources/health-panel/qq-main-display45.png"))
            add(java.io.File("src/test/resources/health-panel/panel-bath-display41.png"))
            add(java.io.File("src/test/resources/health-panel/room-live-display47-cat-skin.png"))
        }.filter { it.name.endsWith(".png") }
        var checked = 0
        for (f in frames) {
            val img = ImageIO.read(f)
            if (img.width != 720 || img.height != 1600) continue
            checked++
            val s = score(img, template, roomRect)
            assertTrue("non-room frame ${f.name} must not read as room, got $s", s < 0.90)
        }
        assertTrue("expected at least 15 full 720x1600 non-room frames, checked $checked", checked >= 15)
    }

    /**
     * Pages a flow reaches from the 9.3.80 room, captured live on display 3 (2026-11-14). The
     * outdoor map is the one the review flagged: its grass is the same green as the chair back
     * and initialise() tests room before outdoor. Measured 0.8709 (drift +56), food 0.8624,
     * bath 0.8478. Food and bath have the header and account avatar painted grey.
     */
    @Test fun qq9380ScenesReachedFromTheRoomAreNotTheRoom() {
        for (name in listOf("scene-qq9380-outdoor-display3.png", "scene-qq9380-food-display3.png",
                "scene-qq9380-bath-display3.png")) {
            val s = score(image("/health-panel/" + name), template, roomRect)
            assertTrue("9.3.80 scene $name must not read as room, got $s", s < 0.90)
        }
    }
}
