package com.guna.nova

import java.util.UUID

enum class PlanStepState { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

data class StepVerification(val verified: Boolean, val reasonCode: String, val summary: String) {
    companion object {
        fun verified(reasonCode: String, summary: String) = StepVerification(true, reasonCode, summary)
        fun failed(reasonCode: String, summary: String) = StepVerification(false, reasonCode, summary)
    }
}

data class PlanStepProgress(val stepId: String, val state: PlanStepState, val reasonCode: String = "")
data class PlanExecutionProgress(val executionId: String, val steps: List<PlanStepProgress>)
data class PlanExecutionReport(
    val executionId: String,
    val steps: List<PlanStepProgress>,
    val success: Boolean,
    val summary: String
)

/**
 * Single-flight plan coordinator. It preflights the entire plan before dispatch,
 * then requires a verified result before a dependent step can start.
 */
object BrainExecutionEngine {
    fun execute(
        plan: BrainPlan,
        originalRequest: String,
        dispatcher: (PlanStep) -> StepVerification,
        onProgress: (PlanExecutionProgress) -> Unit = {}
    ): PlanExecutionReport {
        val executionId = UUID.randomUUID().toString()
        val states = plan.steps.associate { it.id to PlanStepProgress(it.id, PlanStepState.PENDING) }.toMutableMap()
        fun snapshot() = PlanExecutionProgress(executionId, plan.steps.map { states.getValue(it.id) })
        fun publish() { runCatching { onProgress(snapshot()) } }
        fun report(success: Boolean, summary: String) =
            PlanExecutionReport(executionId, plan.steps.map { states.getValue(it.id) }, success, summary)

        publish()

        // A denied, unclear or non-executable step blocks the whole plan before any side effect.
        val policyBlocked = BrainPolicy.evaluatePlan(plan, originalRequest)
            .firstOrNull { it.outcome != PolicyOutcome.ALLOW }
        val conversationStep = plan.steps.firstOrNull { it.proposal.action == NovaAction.CONVERSATION }
        val blocked = policyBlocked ?: conversationStep?.let {
            PolicyDecision(PolicyOutcome.CLARIFY, "NON_EXECUTABLE_PLAN_STEP", it.id)
        }
        if (blocked != null) {
            states[blocked.stepId] = PlanStepProgress(blocked.stepId, PlanStepState.FAILED, blocked.reasonCode)
            plan.steps.filter { it.id != blocked.stepId }.forEach {
                states[it.id] = PlanStepProgress(it.id, PlanStepState.SKIPPED, "PLAN_PREFLIGHT_BLOCKED")
            }
            publish()
            val message = when (blocked.reasonCode) {
                "PERMANENT_FINANCIAL_DENY" ->
                    "Nova permanently blocks financial actions. No plan steps were executed."
                "NON_EXECUTABLE_PLAN_STEP" ->
                    "The plan mixed a conversation response with device actions. Nothing was executed; please separate the request."
                else -> "Nova could not safely authorize the complete plan. No plan steps were executed."
            }
            return report(false, message)
        }

        var failedStepId: String? = null
        var failureReason = ""
        for (step in plan.steps) {
            if (step.dependsOn.any { states[it]?.state != PlanStepState.COMPLETED }) {
                states[step.id] = PlanStepProgress(step.id, PlanStepState.FAILED, "DEPENDENCY_NOT_VERIFIED")
                failedStepId = step.id
                failureReason = "DEPENDENCY_NOT_VERIFIED"
                publish()
                break
            }

            states[step.id] = PlanStepProgress(step.id, PlanStepState.RUNNING, "DISPATCH_IN_PROGRESS")
            publish()

            // Defence in depth: re-check the permanent deny policy immediately before
            // crossing the side-effect boundary, even after successful preflight.
            val proposal = step.proposal
            if (FinancialActionPolicy.mustDeny(
                    capability = proposal.action.name,
                    requestOrArguments = listOfNotNull(originalRequest, proposal.target, proposal.reply).joinToString(" ")
                )
            ) {
                states[step.id] = PlanStepProgress(step.id, PlanStepState.FAILED, "PERMANENT_FINANCIAL_DENY")
                failedStepId = step.id
                failureReason = "PERMANENT_FINANCIAL_DENY"
                publish()
                break
            }

            val result = try { dispatcher(step) } catch (_: Exception) {
                StepVerification.failed("EXECUTOR_EXCEPTION", "The operation failed unexpectedly.")
            }
            if (result.verified) {
                states[step.id] = PlanStepProgress(step.id, PlanStepState.COMPLETED, result.reasonCode)
                publish()
            } else {
                states[step.id] = PlanStepProgress(step.id, PlanStepState.FAILED, result.reasonCode)
                failedStepId = step.id
                failureReason = result.reasonCode
                publish()
                break
            }
        }

        if (failedStepId != null) {
            var afterFailure = false
            plan.steps.forEach { step ->
                if (step.id == failedStepId) afterFailure = true
                else if (afterFailure && states.getValue(step.id).state == PlanStepState.PENDING) {
                    states[step.id] = PlanStepProgress(step.id, PlanStepState.SKIPPED, "PREVIOUS_STEP_FAILED")
                }
            }
            publish()
            val completed = states.values.count { it.state == PlanStepState.COMPLETED }
            return report(false, "Stopped at step $failedStepId ($failureReason). $completed/${plan.steps.size} steps completed; remaining steps were skipped.")
        }

        return report(true, "Completed all ${plan.steps.size} steps in order. Android accepted each operation at its API boundary; remote app or network completion is not implied.")
    }
}
