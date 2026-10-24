package com.ynk.virtualdisplay.bot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchTest {
    @Test fun exactAndPaddedMatch() {
        assertTrue(TextMatch.matches(" 出门 ", "出门"))
        assertTrue(TextMatch.matches("|蹦蹦跳跳体能课", "蹦蹦跳跳体能课"))
    }

    @Test fun oneSwappedCharacterIsToleratedOnLongNames() {
        assertTrue(TextMatch.matches("世界地理谋", "世界地理课"))
        assertFalse(TextMatch.matches("世界历史谋", "世界地理课"))
    }

    @Test fun shortLabelsMustBeExact() {
        assertFalse(TextMatch.matches("出口", "出门"))
        assertFalse(TextMatch.matches("出门去", "出门"))
    }
}
