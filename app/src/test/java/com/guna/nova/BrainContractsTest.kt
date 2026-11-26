package com.guna.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainContractsTest {
    @Test
    fun acceptsOnlyExactThreeFieldJsonSchema() {
        val proposal = BrainContractParser.parseIntent(
            """{"action":"OPEN_APP","target":"Maps","reply":""}""",
            ProposalSource.LOCAL_MODEL
        )
        assertNotNull(proposal)
        assertEquals(NovaAction.OPEN_APP, proposal?.action)
        assertEquals("Maps", proposal?.target)
        assertEquals(ProposalSource.LOCAL_MODEL, proposal?.source)
    }

    @Test
    fun rejectsMalformedWrappedOrOversizedModelOutput() {
        val invalid = listOf(
            """{"action":"OPEN_APP","target":"Maps","reply":"" """,
            """Here is the JSON: {"action":"OPEN_APP","target":"Maps","reply":""}""",
            """{"action":"OPEN_APP","target":"Maps","reply":"","extra":"execute"}""",
            """{"action":"OPEN_APP","target":42,"reply":""}""",
            """{"action":"OPEN_APP","target":"Maps"}""",
            " " + "x".repeat(4097)
        )
        invalid.forEach { raw ->
            assertNull("Malformed model output must be rejected: $raw",
                BrainContractParser.parseIntent(raw, ProposalSource.LOCAL_MODEL))
        }
    }

    @Test
    fun rejectsUnknownAndPrivilegedActions() {
        listOf("UNKNOWN_ACTION", "SEND", "TYPE_TEXT", "DELETE", "PAYMENT").forEach { action ->
            val raw = """{"action":"$action","target":"anything","reply":""}"""
            val proposal = BrainContractParser.parseIntent(raw, ProposalSource.LOCAL_MODEL)
            if (action == "PAYMENT") {
                assertNotNull("Financial action is represented as a typed proposal for policy denial.", proposal)
            } else {
                assertNull("Unknown or unregistered privileged action must not parse: $action", proposal)
            }
        }
    }

    @Test
    fun rejectsConversationTargetAndActionReplyConfusion() {
        assertNull(BrainContractParser.parseIntent(
            """{"action":"CONVERSATION","target":"Settings","reply":""}""",
            ProposalSource.LOCAL_MODEL
        ))
        assertNull(BrainContractParser.parseIntent(
            """{"action":"OPEN_APP","target":"Maps","reply":"done"}""",
            ProposalSource.LOCAL_MODEL
        ))
    }

    @Test
    fun planRejectsDuplicateIdsForwardDependenciesAndOversizedPlans() {
        val proposal = IntentProposal(NovaAction.SEARCH, target = "weather", source = ProposalSource.LOCAL_MODEL)
        val first = PlanStep("one", proposal)
        assertTrue(runCatching { BrainPlan(listOf(first, first)) }.isFailure)
        assertTrue(runCatching { BrainPlan(listOf(PlanStep("one", proposal, setOf("later")))) }.isFailure)
        val tooMany = (1..9).map { PlanStep("step-$it", proposal) }
        assertTrue(runCatching { BrainPlan(tooMany) }.isFailure)
    }

    @Test
    fun policyAllowsOnlyReadOnlyAllowListedModelActions() {
        val safe = PlanStep("safe", IntentProposal(NovaAction.SEARCH, "weather", source = ProposalSource.LOCAL_MODEL))
        val safeDecision = BrainPolicy.evaluate(safe, "search weather")
        assertEquals(PolicyOutcome.ALLOW, safeDecision.outcome)

        val privileged = PlanStep("privileged", IntentProposal(NovaAction.PAYMENT, "UPI", source = ProposalSource.LOCAL_MODEL))
        val denied = BrainPolicy.evaluate(privileged, "pay this bill")
        assertEquals(PolicyOutcome.DENY, denied.outcome)
        assertEquals("PERMANENT_FINANCIAL_DENY", denied.reasonCode)

        val unsupported = PlanStep("unsupported", IntentProposal(NovaAction.UNSUPPORTED, source = ProposalSource.LOCAL_MODEL))
        assertEquals(PolicyOutcome.CLARIFY, BrainPolicy.evaluate(unsupported, "do something unsupported").outcome)
    }

    @Test
    fun sourceMetadataDoesNotGrantExecutionPermission() {
        val local = PlanStep("local", IntentProposal(NovaAction.SEND, source = ProposalSource.LOCAL_MODEL))
        val cloud = PlanStep("cloud", IntentProposal(NovaAction.SEND, source = ProposalSource.CLOUD_MODEL))
        assertFalse(BrainPolicy.evaluate(local, "send this").outcome == PolicyOutcome.ALLOW)
        assertFalse(BrainPolicy.evaluate(cloud, "send this").outcome == PolicyOutcome.ALLOW)
    }
}
