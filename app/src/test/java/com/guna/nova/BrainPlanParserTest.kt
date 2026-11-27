package com.guna.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainPlanParserTest {
    private val validTwoStepPlan = """
        {"steps":[
          {"id":"open","action":"OPEN_APP","target":"Maps","reply":"","depends_on":[]},
          {"id":"search","action":"SEARCH","target":"coffee shops","reply":"","depends_on":["open"]}
        ]}
    """.trimIndent()

    @Test fun parsesBoundedPlanAndPreservesDependencies() {
        val plan = BrainPlanParser.parse(validTwoStepPlan, ProposalSource.LOCAL_MODEL)
        assertEquals(2, plan?.steps?.size)
        assertEquals(setOf("open"), plan?.steps?.get(1)?.dependsOn)
        assertEquals(ProposalSource.LOCAL_MODEL, plan?.steps?.get(0)?.proposal?.source)
    }

    @Test fun rejectsMalformedUnknownOrWronglyTypedFields() {
        val invalid = listOf(
            "not json",
            """{"steps":[]}""",
            """{"steps":[{"id":"x","action":"SEARCH","target":"x","reply":"","depends_on":[],"extra":true}]}""",
            """{"steps":[{"id":"x","action":"SEARCH","target":42,"reply":"","depends_on":[]}]}""",
            """{"steps":[{"id":"x","action":"SEARCH","target":"x","reply":"","depends_on":"none"}]}""",
            """{"steps":[{"id":"x","action":"SEND","target":"x","reply":"","depends_on":[]}]}""",
            """{"steps":[{"id":"x","action":"SEARCH","target":"x","reply":"","depends_on":["later"]}]}""",
            """{"steps":[{"id":"x","action":"SEARCH","target":"x","reply":"","depends_on":["x"]}]}""",
            """{"steps":[{"id":"x","action":"SEARCH","target":"x","reply":"","depends_on":[]},{"id":"x","action":"SEARCH","target":"y","reply":"","depends_on":[]}]}"""
        )
        invalid.forEach { assertNull("Should reject: $it", BrainPlanParser.parse(it, ProposalSource.LOCAL_MODEL)) }
    }

    @Test fun rejectsPlansExceedingStepAndPayloadLimits() {
        val tooMany = (1..9).joinToString(",") { index ->
            """{"id":"s$index","action":"SEARCH","target":"query","reply":"","depends_on":[]}"""
        }
        assertNull(BrainPlanParser.parse("""{"steps":[$tooMany]}""", ProposalSource.LOCAL_MODEL))
        assertNull(BrainPlanParser.parse(" " + "x".repeat(8192), ProposalSource.LOCAL_MODEL))
    }

    @Test fun evaluatesEveryStepAndDeniesWholePlanWhenAnyStepIsFinancial() {
        val plan = BrainPlanParser.parse(
            """{"steps":[
                {"id":"search","action":"SEARCH","target":"restaurants","reply":"","depends_on":[]},
                {"id":"pay","action":"PAYMENT","target":"UPI","reply":"","depends_on":["search"]}
            ]}""",
            ProposalSource.CLOUD_MODEL
        )
        assertTrue(plan != null)
        val decisions = BrainPolicy.evaluatePlan(plan!!, "find restaurants and pay")
        assertEquals(2, decisions.size)
        assertEquals(PolicyOutcome.ALLOW, decisions[0].outcome)
        assertEquals(PolicyOutcome.DENY, decisions[1].outcome)
        assertEquals("PERMANENT_FINANCIAL_DENY", decisions[1].reasonCode)
    }

    @Test fun sourceDoesNotChangePolicyAndUnsupportedStepsRequireClarification() {
        val local = BrainPlanParser.parse(validTwoStepPlan, ProposalSource.LOCAL_MODEL)!!
        val cloud = BrainPlanParser.parse(validTwoStepPlan, ProposalSource.CLOUD_MODEL)!!
        assertEquals(BrainPolicy.evaluatePlan(local, "open Maps and search coffee"),
            BrainPolicy.evaluatePlan(cloud, "open Maps and search coffee"))
        val unsupported = BrainPlan.single(IntentProposal(NovaAction.UNSUPPORTED, source = ProposalSource.LOCAL_MODEL))
        assertEquals(PolicyOutcome.CLARIFY, BrainPolicy.evaluatePlan(unsupported, "do something").single().outcome)
        assertFalse(BrainPolicy.evaluatePlan(local, "open Maps and search coffee").any { it.outcome == PolicyOutcome.DENY })
    }
}
