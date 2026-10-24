package com.ynk.virtualdisplay

import com.ynk.virtualdisplay.automation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlanV2StoreTest {
    // The project's JVM runner works in app/. Keep real persistence fixtures inside the workspace.
    @get:Rule val temporary = TemporaryFolder(File("../.build-tmp").absoluteFile.apply { mkdirs() })

    private fun plan(steps: List<PlanStep> = listOf(StudyStep("s", "study:田径运动课", 2))) = PlanV2(
        planId = "plan-1", name = "护理\"与\\学习🐈", dailyBudgetMinutes = 100, steps = steps)

    @Test fun everyNonemptyCombinationKeepsTheFixedStepOrderAfterReopening() {
        val expectedOrders = listOf(
            listOf(PlanStepType.WORK), listOf(PlanStepType.CARE), listOf(PlanStepType.STUDY),
            listOf(PlanStepType.WORK, PlanStepType.CARE), listOf(PlanStepType.WORK, PlanStepType.STUDY),
            listOf(PlanStepType.CARE, PlanStepType.STUDY),
            listOf(PlanStepType.WORK, PlanStepType.CARE, PlanStepType.STUDY))
        val file = File(temporary.root, PlanV2Store.FILE_NAME)
        expectedOrders.forEach { order ->
            val steps = order.map { type -> when (type) {
                PlanStepType.WORK -> WorkStep("w", "work:竹影武馆/帮武馆跑腿")
                PlanStepType.CARE -> CareStep("c", 70, 80)
                PlanStepType.STUDY -> StudyStep("s", "study:田径运动课", 4)
            } }
            val expected = plan(steps)
            PlanV2Store(file).save(expected)
            val loaded = PlanV2Store(file).load()
            assertEquals(order, loaded!!.steps.map { it.type })
            assertEquals(expected, loaded)
        }
    }

    @Test fun unknownSchemaIsRejectedWithoutRewritingTheOriginalFile() {
        val bytes = PlanV2JsonCodec.encode(plan()).replace("\"schemaVersion\":2", "\"schemaVersion\":3")
            .toByteArray(Charsets.UTF_8)
        val file = File(temporary.root, PlanV2Store.FILE_NAME).apply { writeBytes(bytes) }
        assertNull(PlanV2Store(file).load())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun brokenJsonIsRejectedAndLeftUntouched() {
        val bytes = "{\"schemaVersion\":2,\"steps\":[".toByteArray()
        val file = File(temporary.root, PlanV2Store.FILE_NAME).apply { writeBytes(bytes) }
        assertNull(PlanV2Store(file).load())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun emptyPlansAreRejectedByTheModelAndTheFileLoader() {
        assertThrows(IllegalArgumentException::class.java) { plan(emptyList()) }
        val json = """{"schemaVersion":2,"planId":"p","name":"x","dailyBudgetMinutes":100,"executionProfile":"SELECTED_ACTIONS","steps":[]}"""
        val file = File(temporary.root, PlanV2Store.FILE_NAME).apply { writeText(json) }
        assertNull(PlanV2Store(file).load())
        assertEquals(json, file.readText())
    }

    @Test fun outOfOrderAndDuplicateActionsCannotBecomeRunnablePlans() {
        val work = """{"stepId":"w","type":"WORK","workOptionId":"work:竹影武馆/帮武馆跑腿","trigger":"ALWAYS","shifts":1}"""
        val study = """{"stepId":"s","type":"STUDY","courseId":"study:田径运动课","rounds":1}"""
        for (steps in listOf("$study,$work", "$study,$study")) {
            val json = """{"schemaVersion":2,"planId":"p","name":"x","dailyBudgetMinutes":100,"executionProfile":"SELECTED_ACTIONS","steps":[$steps]}"""
            val file = File(temporary.root, PlanV2Store.FILE_NAME).apply { writeText(json) }
            assertNull(PlanV2Store(file).load())
            assertEquals(json, file.readText())
        }
    }

    @Test fun unselectedActionParametersAreNotWrittenOrRequired() {
        val expected = plan()
        val json = PlanV2JsonCodec.encode(expected)
        assertFalse(json.contains("workOptionId"))
        assertFalse(json.contains("energyThreshold"))
        assertFalse(json.contains("cleanlinessThreshold"))
        assertFalse(json.contains("legacySource"))
        assertEquals(expected, PlanV2JsonCodec.decode(json))
    }

    @Test fun compatibilityProvenanceAndConditionalTriggerRoundTripExactly() {
        val expected = plan(listOf(
            WorkStep("w", "work:竹影武馆/帮武馆跑腿", WorkTrigger.IF_FUNDS_SHORT),
            CareStep("c", 60, 100), StudyStep("s", "study:田径运动课", 10)))
            .copy(executionProfile = ExecutionProfile.LEGACY_MANAGED,
                legacySource = """{"rounds":10,"workBeforeStudy":true}""")
        val file = File(temporary.root, PlanV2Store.FILE_NAME)
        PlanV2Store(file).save(expected)
        assertEquals(expected, PlanV2Store(file).load())
    }

    @Test fun duplicateKeysWrongTypesUnknownFieldsAndIllegalRangesAreRejected() {
        val baseline = PlanV2JsonCodec.encode(plan())
        val invalid = listOf(
            baseline.replaceFirst("{", "{\"schemaVersion\":2,"),
            baseline.replace("\"dailyBudgetMinutes\":100", "\"dailyBudgetMinutes\":\"100\""),
            baseline.replace("\"dailyBudgetMinutes\":100", "\"dailyBudgetMinutes\":2147483648"),
            baseline.replace("\"dailyBudgetMinutes\":100", "\"dailyBudgetMinutes\":0"),
            baseline.replace("\"schemaVersion\":2", "\"schemaVersion\":2.0"),
            baseline.replace("\"rounds\":2", "\"rounds\":0"),
            baseline.replace("\"type\":\"STUDY\"", "\"type\":\"UNKNOWN\""),
            baseline.dropLast(1) + ",\"unexpected\":true}",
            baseline + " trailing data")
        val file = File(temporary.root, PlanV2Store.FILE_NAME)
        invalid.forEach { text ->
            file.writeText(text)
            assertNull(text, PlanV2Store(file).load())
            assertEquals(text, file.readText())
        }
    }

    @Test fun invalidUtf8InsideAnOtherwiseValidNameIsNotSilentlyReplaced() {
        val json = PlanV2JsonCodec.encode(plan().copy(name = "A"))
        val bytes = json.toByteArray(Charsets.UTF_8)
        bytes[json.indexOf("\"name\":\"A\"") + "\"name\":\"".length] = 0xff.toByte()
        val file = File(temporary.root, PlanV2Store.FILE_NAME).apply { writeBytes(bytes) }
        assertNull(PlanV2Store(file).load())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun anInterruptedReplacementDoesNotDestroyThePreviouslySavedPlan() {
        val file = File(temporary.root, PlanV2Store.FILE_NAME)
        val store = PlanV2Store(file)
        val original = plan()
        store.save(original)
        val bytes = file.readBytes()
        assertTrue(File(temporary.root, file.name + ".pending").mkdir())
        assertThrows(java.io.IOException::class.java) { store.save(original.copy(name = "changed")) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(original, store.load())
    }

    @Test fun readingValidWhitespaceDoesNotRewriteTheFile() {
        val json = " \n" + PlanV2JsonCodec.encode(plan()) + "\r\n"
        val file = File(temporary.root, PlanV2Store.FILE_NAME).apply { writeText(json) }
        assertEquals(plan(), PlanV2Store(file).load())
        assertEquals(json, file.readText())
    }

    @Test fun startFactoryRejectsDefaultDisplayAndBlankRequestBeforeCreatingAnIntent() {
        assertThrows(IllegalArgumentException::class.java) {
            com.ynk.virtualdisplay.ui.data.SessionStartRequest.forPlan(plan(), 0, "request")
        }
        assertThrows(IllegalArgumentException::class.java) {
            com.ynk.virtualdisplay.ui.data.SessionStartRequest.forPlan(plan(), 4, " ")
        }
    }

    @Test fun twoArgumentFactoryCannotGuessAMissingRuntimeDisplay() {
        val original = PetRuntime.state.value
        try {
            PetRuntime.update { it.copy(displayId = null) }
            assertThrows(IllegalStateException::class.java) {
                com.ynk.virtualdisplay.ui.data.SessionStartRequest.forPlan(plan(), "request")
            }
        } finally { PetRuntime.update { original } }
    }

    @Test fun savesAndLoadsAnActualUtf8File() {
        val file = File(temporary.root, PlanV2Store.FILE_NAME)
        val store = PlanV2Store(file)
        assertNull(store.load())
        val expected = plan()
        store.save(expected)
        assertTrue(file.isFile)
        assertTrue(file.readText(Charsets.UTF_8).contains("田径运动课"))
        assertEquals(expected, PlanV2Store(file).load())
    }
}
