package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.PayrollNavigationPolicy as Policy
import org.junit.Assert.*
import org.junit.Test

class PayrollNavigationPolicyTest {
    private val proof = Policy.Proof(true, "帮武馆跑腿", 10, 68)
    // Dark pixels cropped from payroll-observe.png (2026-10-21), bbox [31,141,51,179).
    // The runtime must locate them; these fixture coordinates are never a navigation fallback.
    private val arrow = listOf(
        ".................###", "................####", "...............#####", "..............#####.",
        ".............#####..", "............#####...", "...........#####....", "..........#####.....",
        ".........#####......", "........#####.......", ".......#####........", "......#####.........",
        ".....#####..........", "....#####...........", "...#####............", "..#####.............",
        ".#####..............", ".####...............", "####................", "####................",
        ".####...............", ".#####..............", "..#####.............", "...#####............",
        "....#####...........", ".....#####..........", "......#####.........", ".......#####........",
        "........#####.......", ".........#####......", "..........#####.....", "...........#####....",
        "............#####...", ".............#####..", "..............#####.", "...............#####",
        "................####", ".................###",
    )
    private fun frame(left: Int = 31, top: Int = 141, flipped: Boolean = false): (Int, Int) -> Int = { x, y ->
        val row = arrow.getOrNull(y - top)
        val column = if (flipped) 19 - (x - left) else x - left
        if (row != null && row.getOrNull(column) == '#') 0xff333333.toInt() else 0xffffedb8.toInt()
    }
    private fun locate(p: Policy.Proof = proof, expected: Policy.Proof? = proof, pixels: (Int, Int) -> Int = frame()) =
        Policy.returnTarget(p, expected, 720, 1600, pixels)

    @Test fun realPayrollHasNoLegacyTextCloseLabel() {
        val labels = setOf("继续", "确定", "知道了")
        val lines = listOf("工资明细", "帮武馆跑腿(10分钟)", "基础工资68", "金币 +68", "分享", "继续打工")
        assertTrue(lines.none { it in labels })
    }
    @Test fun locatesObservedBackArrowWithoutLegacyCloseButton() {
        val target = locate()
        assertNotNull(target)
        assertEquals(40.5f, target!!.x, 0.6f)
        assertEquals(159.5f, target.y, 0.6f)
    }
    @Test fun followsMovedArrowInsteadOfRememberingFixtureCoordinates() {
        val target = locate(pixels = frame(56, 150))
        assertNotNull(target)
        assertEquals(65.5f, target!!.x, 0.6f)
        assertEquals(168.5f, target.y, 0.6f)
    }
    @Test fun requiresCurrentPayrollIdentity() { assertNull(locate(p = proof.copy(payroll = false))) }
    @Test fun requiresPreviouslyMatchedCompletion() { assertNull(locate(expected = null)) }
    @Test fun requiresSameNamedOptionAndMinutes() {
        assertNull(locate(p = proof.copy(option = "陪练基础课")))
        assertNull(locate(p = proof.copy(minutes = 45)))
        assertNull(locate(p = proof.copy(option = null)))
        assertNull(locate(p = proof.copy(minutes = null)))
    }
    @Test fun rejectsUnreadZeroOrContradictoryAward() {
        assertNull(locate(p = proof.copy(award = null)))
        assertNull(locate(p = proof.copy(award = 0)))
        assertNull(locate(p = proof.copy(award = -1)))
        assertNull(locate(p = proof.copy(award = 67)))
    }
    @Test fun rejectsMissingArrowAndRightChevron() {
        assertNull(locate(pixels = { _, _ -> 0xffffedb8.toInt() }))
        assertNull(locate(pixels = frame(flipped = true)))
    }
    @Test fun rejectsAmbiguousTwoBackArrows() {
        val one = frame(); val two = frame(59, 150)
        assertNull(locate(pixels = { x, y -> if (one(x, y) == 0xff333333.toInt() || two(x, y) == 0xff333333.toInt()) 0xff333333.toInt() else 0xffffedb8.toInt() }))
    }
    @Test fun rejectsRectangleInsteadOfArrow() {
        assertNull(locate(pixels = { x, y -> if (x in 31..50 && y in 141..178) 0xff333333.toInt() else 0xffffedb8.toInt() }))
    }
    @Test fun rejectsInvalidDimensions() {
        assertNull(Policy.returnTarget(proof, proof, 0, 1600, frame()))
    }
    @Test fun detailIsTypedButNeverReportedAsHome() {
        val page = Policy.exitPage(false, false, true, false)
        assertEquals(Policy.Page.WORK_DETAIL, page)
        assertFalse(Policy.ExitResult(page, "detail").homeReached)
    }
    @Test fun successfulReturnRequiresOneConfirmedDestination() {
        assertEquals(Policy.Page.OUTDOOR, Policy.exitPage(true, false, false, false))
        assertEquals(Policy.Page.ROOM, Policy.exitPage(false, true, false, false))
        assertTrue(Policy.ExitResult(Policy.Page.ROOM, "room").homeReached)
        assertEquals(Policy.Page.UNKNOWN, Policy.exitPage(false, false, false, false))
        assertEquals(Policy.Page.UNKNOWN, Policy.exitPage(true, true, false, false))
        assertEquals(Policy.Page.UNKNOWN, Policy.exitPage(true, false, false, true))
    }
}
