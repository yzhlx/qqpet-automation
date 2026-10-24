package com.ynk.virtualdisplay.bot

import org.junit.Assert.assertEquals
import org.junit.Test

class BotConfigCodecTest {
    @Test fun roundTrips() {
        val c = BotConfig(course = "田径运动课", courseMinutes = 30, lessons = 3, energyTarget = 80, cleanTarget = 60, workWhenShort = false)
        assertEquals(c, BotConfigCodec.decode(BotConfigCodec.encode(c)))
    }

    @Test fun defaultCourseIsEmptyText() {
        assertEquals(null, BotConfigCodec.decode(BotConfigCodec.encode(BotConfig())).course)
    }

    @Test fun missingOrBrokenKeysTakeDefaults() {
        assertEquals(BotConfig(lessons = 2), BotConfigCodec.decode("lessons=2\nenergyTarget=abc\nnoise"))
    }
}
