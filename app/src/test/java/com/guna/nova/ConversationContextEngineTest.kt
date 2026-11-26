package com.guna.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextEngineTest {
    private val prior = listOf(
        ConversationTurn("user", "Explain Java inheritance."),
        ConversationTurn("assistant", "Inheritance lets a class reuse fields and methods from a parent class.")
    )

    @Test fun classifiesWhyAsFollowUpAndKeepsTopic() {
        val result = ConversationContextEngine.analyze(prior, "Why?")
        assertEquals(ConversationMode.FOLLOW_UP, result.mode)
        assertFalse(result.requiresClarification)
        assertTrue(result.localPrompt.contains("Java inheritance"))
        assertTrue(result.localPrompt.contains("Current user request: Why?"))
    }

    @Test fun recognizesEnglishFollowUps() {
        listOf("Tell me more", "What about that?", "Do the same", "Explain the last one", "What did you say earlier?")
            .forEach { assertEquals(it, ConversationMode.FOLLOW_UP, ConversationContextEngine.analyze(prior, it).mode) }
    }

    @Test fun recognizesTamilAndTanglishFollowUps() {
        listOf("ஏன்?", "இன்னும் சொல்லு", "அதே மாதிரி", "முன்னாடி என்ன சொன்ன", "yen?", "innum sollu", "adha pathi sollu")
            .forEach { assertEquals(it, ConversationMode.FOLLOW_UP, ConversationContextEngine.analyze(prior, it).mode) }
    }

    @Test fun distinguishesNewQuestionAndSendsOnlyCurrentTurnToCloud() {
        val result = ConversationContextEngine.analyze(prior, "What is the capital of Japan?")
        assertEquals(ConversationMode.NEW_TOPIC, result.mode)
        assertEquals(1, result.cloudTurns.size)
        assertEquals("What is the capital of Japan?", result.cloudTurns.single().content)
    }

    @Test fun asksForClarificationWhenFollowUpHasNoHistory() {
        val result = ConversationContextEngine.analyze(emptyList(), "What about that?")
        assertTrue(result.requiresClarification)
        assertTrue(result.clarification.orEmpty().contains("earlier context"))
    }

    @Test fun asksWhichOptionWhenPreviousAnswerContainsMultipleChoices() {
        val context = listOf(ConversationTurn("assistant", "Choose Java or Kotlin:\n- Java\n- Kotlin"))
        val result = ConversationContextEngine.analyze(context, "That one")
        assertTrue(result.requiresClarification)
        assertTrue(result.clarification.orEmpty().contains("Which option"))
    }

    @Test fun cloudHistoryIsMinimalAndRedactsPrivateIdentifiers() {
        val context = listOf(
            ConversationTurn("user", "My email is guna@example.com and phone 9876543210"),
            ConversationTurn("assistant", "I can help with that.")
        )
        val result = ConversationContextEngine.analyze(context, "Tell me more")
        val cloudText = result.cloudTurns.joinToString(" ") { it.content }
        assertTrue(result.cloudTurns.size <= 4)
        assertFalse(cloudText.contains("guna@example.com"))
        assertFalse(cloudText.contains("9876543210"))
        assertTrue(cloudText.contains("[email]"))
        assertTrue(cloudText.contains("[phone]"))
    }

    @Test fun localContextIsBoundedAndIgnoresUnknownRoles() {
        val noisy = (1..20).map { ConversationTurn("system", "hidden $it") } +
            (1..20).map { ConversationTurn("user", "turn $it") }
        val result = ConversationContextEngine.analyze(noisy, "Why?")
        assertFalse(result.localPrompt.contains("hidden"))
        assertTrue(result.localPrompt.length <= 8500)
        assertTrue(result.cloudTurns.size <= 4)
    }
}
