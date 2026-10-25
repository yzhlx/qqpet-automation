package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.ActivityBudgetPolicy
import org.junit.Assert.*
import org.junit.Test

class ActivityBudgetPolicyTest {
    @Test fun thirtyMinuteCourseFitsOnlyWithThirtyMinutesRemaining() {
        assertTrue(ActivityBudgetPolicy.canAfford(450, 30, 480))
        assertFalse(ActivityBudgetPolicy.canAfford(451, 30, 480))
    }

    @Test fun workAndStudyMustBothFitTheUserLimit() {
        assertTrue(ActivityBudgetPolicy.canAfford(15, 10 + 30, 55))
        assertFalse(ActivityBudgetPolicy.canAfford(16, 10 + 30, 55))
        assertFalse(ActivityBudgetPolicy.canAfford(30, 30, 45))
    }

    @Test fun aVeryLargeRequestedDurationCannotOverflowIntoAnAllowance() {
        assertFalse(ActivityBudgetPolicy.canAfford(479, Int.MAX_VALUE, 480))
        assertThrows(IllegalArgumentException::class.java) { ActivityBudgetPolicy.canAfford(0, 0, 480) }
        assertThrows(IllegalArgumentException::class.java) { ActivityBudgetPolicy.canAfford(0, 10, 481) }
    }
}
