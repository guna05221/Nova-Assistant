package com.guna.nova

import org.json.JSONObject
import java.util.Locale

/** Origin is metadata only; it never grants an action permission. */
enum class ProposalSource { CLOUD_MODEL, LOCAL_MODEL, DETERMINISTIC }

enum class NovaAction {
    OPEN_APP, SEARCH, WEB_SEARCH, HOME, BACK, SETTINGS, SCROLL_UP, SCROLL_DOWN,
    FOOD_SEARCH, SEARCH_SWIGGY, SEARCH_ZOMATO, CONVERSATION, UNSUPPORTED,
    PAY, PAYMENT, BILL_PAYMENT, TRANSFER, MONEY_TRANSFER, BANK_TRANSFER,
    UPI_TRANSFER, PURCHASE, BUY, CHECKOUT, ORDER, PLACE_ORDER, PAID_ORDER,
    FOOD_ORDER, RECHARGE, TOP_UP, SUBSCRIBE, SUBSCRIPTION,
    RENEW_SUBSCRIPTION, FINANCIAL_TRANSACTION
}

data class IntentProposal(
    val action: NovaAction,
    val target: String? = null,
    val reply: String? = null,
    val source: ProposalSource
)

data class PlanStep(
    val id: String,
    val proposal: IntentProposal,
    val dependsOn: Set<String> = emptySet()
) {
    init {
        require(id.isNotBlank() && id.length <= 64) { "Plan step IDs must be non-empty and bounded." }
        require(dependsOn.none { it == id }) { "A plan step cannot depend on itself." }
    }
}

data class BrainPlan(val steps: List<PlanStep>) {
    init {
        require(steps.isNotEmpty()) { "A brain plan must contain at least one step." }
        require(steps.size <= 8) { "A brain plan cannot contain more than eight steps." }
        val seen = mutableSetOf<String>()
        steps.forEach { step ->
            require(step.id !in seen) { "Plan step IDs must be unique." }
            require(step.dependsOn.all { it in seen }) { "Dependencies must refer to earlier plan steps." }
            seen.add(step.id)
        }
    }

    companion object {
        fun single(proposal: IntentProposal): BrainPlan =
            BrainPlan(listOf(PlanStep(id = "step-1", proposal = proposal)))
    }
}

enum class PolicyOutcome { ALLOW, DENY, REQUIRE_APPROVAL, CLARIFY }

data class PolicyDecision(val outcome: PolicyOutcome, val reasonCode: String, val stepId: String)
enum class ExecutionStatus { DISPATCHED, REJECTED, NOT_EXECUTED }
data class ExecutionResult(val stepId: String, val status: ExecutionStatus, val reasonCode: String, val summary: String)

object BrainContractParser {
    private val allowedFields = setOf("action", "target", "reply")

    fun parseIntent(raw: String, source: ProposalSource): IntentProposal? {
        val input = raw.trim()
        if (input.isEmpty() || input.length > 4096 || !input.startsWith("{") || !input.endsWith("}")) return null
        return try {
            val json = JSONObject(input)
            val keys = mutableSetOf<String>()
            val iterator = json.keys()
            while (iterator.hasNext()) keys.add(iterator.next())
            if (keys != allowedFields) return null
            val actionValue = json.opt("action") as? String ?: return null
            val action = try {
                NovaAction.valueOf(actionValue.trim().uppercase(Locale.ROOT))
            } catch (_: IllegalArgumentException) { return null }
            val targetValue = json.opt("target") as? String ?: return null
            val replyValue = json.opt("reply") as? String ?: return null
            if (targetValue.length > 500 || replyValue.length > 1200) return null
            val target = targetValue.trim().takeIf { it.isNotEmpty() }
            val reply = replyValue.trim().takeIf { it.isNotEmpty() }
            if (action == NovaAction.CONVERSATION && target != null) return null
            if (action != NovaAction.CONVERSATION && reply != null) return null
            IntentProposal(action, target, reply, source)
        } catch (_: Exception) { null }
    }
}

/** Deterministic policy for model-created plan steps; the model cannot approve itself. */
object BrainPolicy {
    private val executableModelActions = setOf(
        NovaAction.OPEN_APP, NovaAction.SEARCH, NovaAction.WEB_SEARCH,
        NovaAction.HOME, NovaAction.BACK, NovaAction.SETTINGS,
        NovaAction.SCROLL_UP, NovaAction.SCROLL_DOWN, NovaAction.FOOD_SEARCH,
        NovaAction.SEARCH_SWIGGY, NovaAction.SEARCH_ZOMATO, NovaAction.CONVERSATION
    )

    fun evaluate(step: PlanStep, originalRequest: String): PolicyDecision {
        val proposal = step.proposal
        if (FinancialActionPolicy.mustDeny(
                capability = proposal.action.name,
                requestOrArguments = listOfNotNull(originalRequest, proposal.target, proposal.reply).joinToString(" ")
            )
        ) return PolicyDecision(PolicyOutcome.DENY, "PERMANENT_FINANCIAL_DENY", step.id)
        if (proposal.action == NovaAction.UNSUPPORTED) return PolicyDecision(PolicyOutcome.CLARIFY, "UNSUPPORTED_ACTION", step.id)
        if (proposal.action !in executableModelActions) return PolicyDecision(PolicyOutcome.DENY, "ACTION_NOT_MODEL_EXECUTABLE", step.id)
        if (proposal.action in setOf(NovaAction.OPEN_APP, NovaAction.SEARCH, NovaAction.WEB_SEARCH,
                NovaAction.SEARCH_SWIGGY, NovaAction.SEARCH_ZOMATO) && proposal.target.isNullOrBlank()) {
            return PolicyDecision(PolicyOutcome.CLARIFY, "MISSING_ACTION_TARGET", step.id)
        }
        return PolicyDecision(PolicyOutcome.ALLOW, "MODEL_ACTION_ALLOWLIST", step.id)
    }

    /** Every proposed step is checked; no plan-level approval can override a denied step. */
    fun evaluatePlan(plan: BrainPlan, originalRequest: String): List<PolicyDecision> =
        plan.steps.map { evaluate(it, originalRequest) }
}
