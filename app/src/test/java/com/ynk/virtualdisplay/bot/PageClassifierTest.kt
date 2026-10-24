package com.ynk.virtualdisplay.bot

import org.junit.Assert.assertEquals
import org.junit.Test

class PageClassifierTest {
    private fun only(name: String) = mapOf(name to 0.99)

    @Test fun eachProbeNamesItsPage() {
        assertEquals(Page.ROOM, PageClassifier.classify(only("room")))
        assertEquals(Page.OUTDOOR, PageClassifier.classify(only("outdoor")))
        assertEquals(Page.QQ_HOST, PageClassifier.classify(only("qq_host_search")))
        assertEquals(Page.RESULT, PageClassifier.classify(only("result")))
    }

    @Test fun belowThresholdIsNoMatch() {
        assertEquals(Page.UNKNOWN, PageClassifier.classify(mapOf("room" to 0.969)))
    }

    @Test fun learningPanelWinsOverTheOutdoorMapUnderIt() {
        assertEquals(Page.LEARNING, PageClassifier.classify(mapOf("outdoor" to 0.99, "learning" to 0.98)))
    }

    @Test fun popupWinsOverThePageUnderIt() {
        assertEquals(Page.UPGRADE_POPUP, PageClassifier.classify(mapOf("academy" to 0.99, "upgrade" to 0.98)))
        assertEquals(Page.CARE_UPGRADE_POPUP, PageClassifier.classify(mapOf("food_scene" to 0.99, "care_upgrade" to 0.98)))
    }

    @Test fun courseDetailWinsOverTheListOnTheSameScreen() {
        assertEquals(Page.COURSE_DETAIL, PageClassifier.classify(mapOf("course_list" to 0.99, "course_detail" to 0.99)))
    }

    @Test fun townIsOnlyAskedWhenNoTemplateMatched() {
        var asked = 0
        assertEquals(Page.ROOM, PageClassifier.classify(only("room")) { asked++; true })
        assertEquals(0, asked)
        assertEquals(Page.TOWN, PageClassifier.classify(emptyMap()) { true })
    }
}
