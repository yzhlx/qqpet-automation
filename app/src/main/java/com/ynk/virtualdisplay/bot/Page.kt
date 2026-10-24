package com.ynk.virtualdisplay.bot

/** Every screen the bot can recognise. Anything else is [UNKNOWN]. */
enum class Page {
    /** QQ's own search page: the pet mini-program was closed. */
    QQ_HOST,
    /** The pet's room. The bot's home: every action starts and ends here. */
    ROOM,
    HEALTH_PANEL,
    FOOD_SCENE,
    BATH_SCENE,
    OUTDOOR,
    ACADEMY,
    COURSE_LIST,
    COURSE_DETAIL,
    /** A lesson is running (bottom "正在学习" panel on the outdoor map). */
    LEARNING,
    /** The lesson report card. */
    RESULT,
    /** Level-up popup after a lesson. */
    UPGRADE_POPUP,
    /** Care level-up popup on the food / bath scene. */
    CARE_UPGRADE_POPUP,
    /** Any page of the career town (work). Recognised by OCR, not by template. */
    TOWN,
    UNKNOWN,
}

data class Point(val x: Float, val y: Float)

/** What one look at the screen tells the bot. */
data class Observation(val page: Page, val atMs: Long)
