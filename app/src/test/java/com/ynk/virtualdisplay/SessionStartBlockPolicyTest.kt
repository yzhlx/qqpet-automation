package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.AttemptId
import com.ynk.virtualdisplay.automation.BudgetAttempt
import com.ynk.virtualdisplay.automation.PendingActionGuard
import com.ynk.virtualdisplay.automation.PersistentBudgetStore
import com.ynk.virtualdisplay.automation.PlanBudgetLedger
import com.ynk.virtualdisplay.automation.PlanV2
import com.ynk.virtualdisplay.automation.QuietWindow
import com.ynk.virtualdisplay.automation.ReserveStatus
import com.ynk.virtualdisplay.automation.SignInStep
import com.ynk.virtualdisplay.automation.StudyStep
import com.ynk.virtualdisplay.automation.WorkStep
import com.ynk.virtualdisplay.automation.WorkTrigger
import com.ynk.virtualdisplay.automation.DailyBudgetStore
import com.ynk.virtualdisplay.ui.screens.SessionStartBlockPolicy
import java.io.File
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 给【新】段补的尝试号：步骤号的尾巴决定它是什么动作。 */
private val homeStudyId = AttemptId("run-1", "study", 1).value
private val homeSignInId = AttemptId("run-1", "plan-1:sign_in", 1).value

private fun homeAttempt(id: String, status: ReserveStatus, day: String, reason: String? = null) =
    BudgetAttempt(id, day, 10, status, reason)

private val homeGuardedPlan = PlanV2(planId = "plan-1", name = "每日", dailyBudgetMinutes = 100,
    steps = listOf(
        SignInStep("plan-1:sign_in"),
        WorkStep("plan-1:work", "work:竹影武馆/帮武馆跑腿", WorkTrigger.ALWAYS),
        StudyStep("plan-1:study", "study:田径运动课", 2)))

private val homeUnGuardedPlan = PlanV2(planId = "plan-1", name = "只打工", dailyBudgetMinutes = 100,
    steps = listOf(WorkStep("plan-1:work", "work:竹影武馆/帮武馆跑腿", WorkTrigger.ALWAYS)))

/** 主页只有一颗「开始」按钮；有没有被允许点下去，背后的规则就是这一段。 */
class SessionStartBlockPolicyTest {
    // The project's JVM runner works in app/. Keep real persistence fixtures inside the workspace.
    @get:Rule val temporary = TemporaryFolder(File("../.build-tmp").absoluteFile.apply { mkdirs() })

    /** 上次被中断的动作还在悬着：不能再来一次，先把「做没做」告诉蟹啦。 */
    @Test fun anUnconfirmedInterruptionBlocksEveryStart() {
        val file = File(temporary.root, "budget.bin")
        PlanBudgetLedger(PersistentBudgetStore(file)) { "2026-11-04" }.apply {
            reserve(homeStudyId, 10)
            unconfirmed(homeStudyId, "CAPTURE_LOST")
        }
        val decision = SessionStartBlockPolicy.evaluate(
            { DailyBudgetStore(PersistentBudgetStore(file)) { "2026-11-04" } },
            { QuietWindow(enabled = false) },
            { LocalTime.of(12, 0) },
            homeGuardedPlan)
        val refused = decision as SessionStartBlockPolicy.Decision.Refused
        assertEquals("PENDING_ACTION_UNCONFIRMED", refused.reason)
        assertTrue(refused.message.contains("学习"))
        assertTrue(refused.message.contains("运行方案"))
    }

    /** 中断发生在领取类动作上同样要拦，名字要能说出来。 */
    @Test fun anUnconfirmedSignInIsNamedInTheRefusal() {
        val file = File(temporary.root, "budget.bin")
        PlanBudgetLedger(PersistentBudgetStore(file)) { "2026-11-04" }.apply {
            reserve(homeSignInId, 0)
            unconfirmed(homeSignInId, "REWARD_PAGE_LOST")
        }
        val decision = SessionStartBlockPolicy.evaluate(
            { DailyBudgetStore(PersistentBudgetStore(file)) { "2026-11-04" } },
            { QuietWindow(enabled = false) },
            { LocalTime.of(12, 0) },
            homeGuardedPlan)
        val refused = decision as SessionStartBlockPolicy.Decision.Refused
        assertTrue(refused.message.contains("签到"))
    }

    /** 用户说「没有发生」之后就放行了：不是没发生，是它已经核实过。 */
    @Test fun aConfirmedInterruptionDoesNotBlock() {
        val file = File(temporary.root, "budget.bin")
        PlanBudgetLedger(PersistentBudgetStore(file)) { "2026-11-04" }.apply {
            reserve(homeStudyId, 10)
            unconfirmed(homeStudyId, "CAPTURE_LOST")
            confirmOutcome(homeStudyId, false, "用户确认没有发生", 1_700_000L)
        }
        assertEquals(SessionStartBlockPolicy.Decision.Proceed, SessionStartBlockPolicy.evaluate(
            { DailyBudgetStore(PersistentBudgetStore(file)) { "2026-11-04" } },
            { QuietWindow(enabled = false) },
            { LocalTime.of(12, 0) },
            homeGuardedPlan))
    }

    /** 其它方案的待确认动作拦不到这一次开始。 */
    @Test fun anUnrelatedPendingActionDoesNotBlockThisStart() {
        val file = File(temporary.root, "budget.bin")
        PlanBudgetLedger(PersistentBudgetStore(file)) { "2026-11-04" }.apply {
            reserve(homeSignInId, 0)
            unconfirmed(homeSignInId, "CAPTURE_LOST")
        }
        assertEquals(SessionStartBlockPolicy.Decision.Proceed, SessionStartBlockPolicy.evaluate(
            { DailyBudgetStore(PersistentBudgetStore(file)) { "2026-11-04" } },
            { QuietWindow(enabled = false) },
            { LocalTime.of(12, 0) },
            homeUnGuardedPlan))
    }

    /** 落在夜间暂停窗口里，点开始的回答就是暂停提示本身。 */
    @Test fun theQuietWindowBlocksTheStartWithItsOwnWords() {
        val window = QuietWindow(enabled = true, startMinute = 23 * 60, endMinute = 7 * 60)
        val decision = SessionStartBlockPolicy.evaluate(
            { DailyBudgetStore(PersistentBudgetStore(File(temporary.root, "budget.bin"))) { "2026-11-04" } },
            { window },
            { LocalTime.of(23, 30) },
            homeGuardedPlan)
        val refused = decision as SessionStartBlockPolicy.Decision.Refused
        assertEquals("QUIET_WINDOW_ACTIVE", refused.reason)
        assertTrue(refused.message.contains("23:00"))
        assertTrue(refused.message.contains("07:00"))
    }

    /** 都正常：没有待确认，也不在窗口里，照常开始。 */
    @Test fun aCleanStateStarts() {
        assertEquals(SessionStartBlockPolicy.Decision.Proceed, SessionStartBlockPolicy.evaluate(
            { DailyBudgetStore(PersistentBudgetStore(File(temporary.root, "budget.bin"))) { "2026-11-04" } },
            { QuietWindow(enabled = true, startMinute = 23 * 60, endMinute = 7 * 60) },
            { LocalTime.of(12, 0) },
            homeGuardedPlan))
    }
}
