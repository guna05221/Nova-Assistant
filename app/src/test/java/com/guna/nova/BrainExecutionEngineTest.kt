package com.guna.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainExecutionEngineTest {
    private fun step(id: String, action: NovaAction = NovaAction.SEARCH, dependsOn: Set<String> = emptySet()) =
        PlanStep(id, IntentProposal(action, target = if (action == NovaAction.SEARCH) id else null,
            source = ProposalSource.LOCAL_MODEL), dependsOn)

    @Test fun executesSequentiallyAndReportsProgress() {
        val plan = BrainPlan(listOf(step("one"), step("two", dependsOn = setOf("one")), step("three", dependsOn = setOf("two"))))
        val dispatched = mutableListOf<String>()
        val progress = mutableListOf<PlanExecutionProgress>()
        val report = BrainExecutionEngine.execute(plan, "search in sequence", dispatcher = {
            dispatched += it.id
            StepVerification.verified("TEST_ACCEPTED", "accepted")
        }, onProgress = { progress += it })

        assertTrue(report.success)
        assertEquals(listOf("one", "two", "three"), dispatched)
        assertTrue(report.steps.all { it.state == PlanStepState.COMPLETED })
        assertTrue(progress.any { snapshot -> snapshot.steps.any { it.state == PlanStepState.RUNNING } })
    }

    @Test fun stopsAfterFirstFailedVerificationAndSkipsRemainingSteps() {
        val plan = BrainPlan(listOf(step("one"), step("two", dependsOn = setOf("one")), step("three", dependsOn = setOf("two"))))
        val dispatched = mutableListOf<String>()
        val report = BrainExecutionEngine.execute(plan, "search in sequence", dispatcher = {
            dispatched += it.id
            if (it.id == "two") StepVerification.failed("NOT_ACCEPTED", "failed")
            else StepVerification.verified("TEST_ACCEPTED", "accepted")
        })

        assertFalse(report.success)
        assertEquals(listOf("one", "two"), dispatched)
        assertEquals(PlanStepState.COMPLETED, report.steps[0].state)
        assertEquals(PlanStepState.FAILED, report.steps[1].state)
        assertEquals(PlanStepState.SKIPPED, report.steps[2].state)
        assertTrue(report.summary.contains("NOT_ACCEPTED"))
    }

    @Test fun preflightsWholePlanAndNeverDispatchesWhenFinancialStepExists() {
        val plan = BrainPlan(listOf(
            step("search"),
            PlanStep("pay", IntentProposal(NovaAction.PAYMENT, target = "UPI", source = ProposalSource.CLOUD_MODEL), setOf("search"))
        ))
        var dispatchCount = 0
        val report = BrainExecutionEngine.execute(plan, "find restaurants and pay", dispatcher = {
            dispatchCount++
            StepVerification.verified("TEST_ACCEPTED", "accepted")
        })

        assertFalse(report.success)
        assertEquals(0, dispatchCount)
        assertTrue(report.steps.none { it.state == PlanStepState.COMPLETED })
        assertEquals(PlanStepState.FAILED, report.steps[1].state)
    }

    @Test fun dependencyMustBeCompletedBeforeDependentStepStarts() {
        val plan = BrainPlan(listOf(step("root"), step("child", dependsOn = setOf("root"))))
        val observed = mutableListOf<String>()
        val report = BrainExecutionEngine.execute(plan, "search", dispatcher = {
            observed += it.id
            if (it.id == "root") StepVerification.failed("ROOT_FAILED", "no")
            else StepVerification.verified("OK", "yes")
        })
        assertEquals(listOf("root"), observed)
        assertEquals(PlanStepState.SKIPPED, report.steps[1].state)
    }

    @Test fun catchesExecutorExceptionsAndStopsSafely() {
        val plan = BrainPlan(listOf(step("one"), step("two", dependsOn = setOf("one"))))
        val report = BrainExecutionEngine.execute(plan, "search", dispatcher = { throw IllegalStateException("test") })
        assertFalse(report.success)
        assertEquals("EXECUTOR_EXCEPTION", report.steps[0].reasonCode)
        assertEquals(PlanStepState.SKIPPED, report.steps[1].state)
    }

    @Test fun conversationStepBlocksWholePlanBeforeAnyAndroidAction() {
        val plan = BrainPlan(listOf(
            step("search"),
            PlanStep("chat", IntentProposal(NovaAction.CONVERSATION, reply = "hello", source = ProposalSource.LOCAL_MODEL), setOf("search"))
        ))
        var dispatchCount = 0
        val report = BrainExecutionEngine.execute(plan, "search then answer", dispatcher = {
            dispatchCount++
            StepVerification.verified("TEST_ACCEPTED", "accepted")
        })
        assertFalse(report.success)
        assertEquals(0, dispatchCount)
        assertEquals(PlanStepState.FAILED, report.steps[1].state)
    }
}
