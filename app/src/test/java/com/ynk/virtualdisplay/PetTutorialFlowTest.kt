package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.ui.screens.TutorialStage
import org.junit.Assert.*
import org.junit.Test

class PetTutorialFlowTest {

    @Test
    fun tutorialStagesHaveFourSequentialSteps() {
        val stages = TutorialStage.entries
        assertEquals(4, stages.size)
        assertEquals(TutorialStage.ROOM_PRECHECK, stages[0])
        assertEquals(TutorialStage.PLAN_CONFIG, stages[1])
        assertEquals(TutorialStage.RUNNING_PAUSE, stages[2])
        assertEquals(TutorialStage.RESULT_CONFIRM, stages[3])
    }

    @Test
    fun tutorialStagesHaveClearTitlesAndSubtitles() {
        TutorialStage.entries.forEach { stage ->
            assertTrue(stage.title.isNotBlank())
            assertTrue(stage.subtitle.isNotBlank())
        }
    }
}
