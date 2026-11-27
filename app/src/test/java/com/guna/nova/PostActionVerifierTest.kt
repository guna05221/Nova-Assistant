package com.guna.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostActionVerifierTest {
    private val baseline = AccessibilityObservation(
        sequence = 10, packageName = "com.android.launcher", className = "Launcher",
        signal = AccessibilitySignal.WINDOW_STATE, visibleText = "Home"
    )

    @Test fun appLaunchRequiresExpectedPackageAndFreshWindowTransition() {
        val good = baseline.copy(sequence = 11, packageName = "com.whatsapp", className = "Main", visibleText = "Chats")
        assertTrue(PostActionVerifier.isVerified(NovaAction.OPEN_APP, "com.whatsapp", baseline, good))
        assertFalse(PostActionVerifier.isVerified(NovaAction.OPEN_APP, "com.whatsapp", baseline, good.copy(packageName = "com.chrome")))
        assertFalse(PostActionVerifier.isVerified(NovaAction.OPEN_APP, "com.whatsapp", baseline, good.copy(sequence = 10)))
    }

    @Test fun searchRequiresFreshExternalPageAndVisibleQueryEvidence() {
        val good = AccessibilityObservation(11, "com.android.chrome", "SearchActivity",
            AccessibilitySignal.WINDOW_CONTENT, "Results for Tamil movies with several visible results")
        assertTrue(PostActionVerifier.isVerified(NovaAction.SEARCH, "Tamil movies", baseline, good))
        assertFalse(PostActionVerifier.isVerified(NovaAction.SEARCH, "Tamil movies", baseline,
            good.copy(visibleText = "Trending topics and other visible results")))
        assertFalse(PostActionVerifier.isVerified(NovaAction.SEARCH, "Tamil movies", baseline,
            good.copy(packageName = "com.guna.nova")))
    }

    @Test fun settingsRequiresSettingsPackage() {
        val good = baseline.copy(sequence = 12, packageName = "com.android.settings",
            signal = AccessibilitySignal.WINDOW_STATE, visibleText = "Network and Internet")
        assertTrue(PostActionVerifier.isVerified(NovaAction.SETTINGS, "", baseline, good))
        assertFalse(PostActionVerifier.isVerified(NovaAction.SETTINGS, "", baseline, good.copy(packageName = "com.android.systemui")))
    }

    @Test fun homeRequiresForegroundWindowTransition() {
        val good = baseline.copy(sequence = 13, packageName = "com.android.launcher3", signal = AccessibilitySignal.WINDOW_STATE)
        assertTrue(PostActionVerifier.isVerified(NovaAction.HOME, "", baseline, good))
        assertFalse(PostActionVerifier.isVerified(NovaAction.HOME, "", baseline, good.copy(packageName = baseline.packageName)))
    }

    @Test fun backRequiresObservableScreenChange() {
        val good = baseline.copy(sequence = 14, className = "PreviousActivity", signal = AccessibilitySignal.WINDOW_STATE)
        assertTrue(PostActionVerifier.isVerified(NovaAction.BACK, "", baseline, good))
        assertFalse(PostActionVerifier.isVerified(NovaAction.BACK, "", baseline, baseline.copy(sequence = 14)))
    }

    @Test fun scrollRequiresViewScrolledEvent() {
        val good = baseline.copy(sequence = 15, signal = AccessibilitySignal.VIEW_SCROLLED)
        assertTrue(PostActionVerifier.isVerified(NovaAction.SCROLL_DOWN, "", baseline, good))
        assertFalse(PostActionVerifier.isVerified(NovaAction.SCROLL_DOWN, "", baseline, good.copy(signal = AccessibilitySignal.WINDOW_CONTENT)))
    }

    @Test fun conversationAndFinancialActionsNeverVerifyAsDeviceOperations() {
        val event = baseline.copy(sequence = 20, packageName = "com.whatsapp",
            signal = AccessibilitySignal.WINDOW_STATE, visibleText = "Chats and messages")
        assertFalse(PostActionVerifier.isVerified(NovaAction.CONVERSATION, "", baseline, event))
        assertFalse(PostActionVerifier.isVerified(NovaAction.PAYMENT, "", baseline, event))
    }

    @Test fun searchHasLongerButBoundedTimeoutBudget() {
        assertTrue(PostActionVerifier.timeoutMs(NovaAction.SEARCH) > PostActionVerifier.timeoutMs(NovaAction.OPEN_APP))
        assertTrue(PostActionVerifier.timeoutMs(NovaAction.SEARCH) <= 12_000L)
    }
}
