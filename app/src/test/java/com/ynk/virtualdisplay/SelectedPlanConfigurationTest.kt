package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Test

class SelectedPlanConfigurationTest {
    private fun selected(steps:List<PlanStep>)=PlanV2(planId="selected",name="chosen",dailyBudgetMinutes=321,steps=steps)

    @Test fun allSevenSubsetsUseOnlySelectedValuesWithoutImplicitFunding() {
        for(mask in 1..7) {
            val steps=buildList<PlanStep> {
                if(mask and 1!=0) add(WorkStep("w","work:chosen"))
                if(mask and 2!=0) add(CareStep("c",75,85))
                if(mask and 4!=0) add(StudyStep("s","study:chosen",4))
            }
            val source=selected(steps)
            val config=SelectedPlanConfiguration.toSessionPlan(source)
            assertFalse(config.workBeforeStudy)
            assertEquals(321,config.dailyBudgetMinutes)
            assertEquals(if(mask and 1!=0) "work:chosen" else null,config.workOptionId)
            assertEquals(if(mask and 2!=0) 75 else SessionPlan.DEFAULT_THRESHOLD,config.energyThreshold)
            assertEquals(if(mask and 2!=0) 85 else SessionPlan.DEFAULT_THRESHOLD,config.cleanlinessThreshold)
            assertEquals(if(mask and 4!=0) "study:chosen" else SessionPlan.DEFAULT_COURSE_ID,config.courseId)
            assertEquals(if(mask and 4!=0) 4 else 1,config.rounds)
            assertEquals(if(mask and 4!=0) 4 else 0,SelectedPlanConfiguration.studyRounds(source))
        }
    }

    @Test fun workAndCareAreNotCountedAsLessons() {
        val source=selected(listOf(WorkStep("w","work:chosen"),CareStep("c",90,90)))
        assertEquals(0,SelectedPlanConfiguration.studyRounds(source))
    }

    @Test fun studyOnlyNeverAcquiresAWorkOption() {
        val source=selected(listOf(StudyStep("s","study:chosen",10)))
        assertNull(SelectedPlanConfiguration.toSessionPlan(source).workOptionId)
        assertEquals(10,SelectedPlanConfiguration.studyRounds(source))
    }

    @Test(expected=IllegalArgumentException::class) fun legacyProfileRequiresItsExplicitAdapter() {
        val legacy=selected(listOf(CareStep("c",90,90))).copy(executionProfile=ExecutionProfile.LEGACY_MANAGED)
        SelectedPlanConfiguration.toSessionPlan(legacy)
    }
}
