package com.guna.nova

enum class AccessibilitySignal { WINDOW_STATE, WINDOW_CONTENT, VIEW_SCROLLED, OTHER }

/** Bounded in-memory snapshot of visible non-editable UI text; never persisted. */
data class AccessibilityObservation(
    val sequence: Long = 0L,
    val packageName: String = "",
    val className: String = "",
    val signal: AccessibilitySignal = AccessibilitySignal.OTHER,
    val visibleText: String = "",
    val observedAtElapsed: Long = 0L
)

object PostActionVerifier {
    private const val NOVA_PACKAGE = "com.guna.nova"

    fun timeoutMs(action: NovaAction): Long = when (action) {
        NovaAction.SEARCH, NovaAction.WEB_SEARCH,
        NovaAction.FOOD_SEARCH, NovaAction.SEARCH_SWIGGY, NovaAction.SEARCH_ZOMATO -> 9_000L
        else -> 5_000L
    }

    fun isVerified(
        action: NovaAction,
        target: String,
        baseline: AccessibilityObservation,
        observed: AccessibilityObservation
    ): Boolean {
        if (observed.sequence <= baseline.sequence || observed.packageName.isBlank()) return false
        val stateChanged = observed.packageName != baseline.packageName ||
            observed.className != baseline.className ||
            observed.visibleText != baseline.visibleText
        val windowTransition = observed.signal == AccessibilitySignal.WINDOW_STATE
        val contentTransition = observed.signal == AccessibilitySignal.WINDOW_CONTENT

        return when (action) {
            NovaAction.OPEN_APP ->
                target.isNotBlank() && observed.packageName == target && windowTransition
            NovaAction.SETTINGS ->
                observed.packageName == "com.android.settings" && (windowTransition || contentTransition)
            NovaAction.HOME ->
                windowTransition && observed.packageName != baseline.packageName &&
                    observed.packageName != NOVA_PACKAGE
            NovaAction.BACK ->
                stateChanged && (windowTransition || contentTransition)
            NovaAction.SCROLL_UP, NovaAction.SCROLL_DOWN ->
                observed.packageName == baseline.packageName &&
                    observed.signal == AccessibilitySignal.VIEW_SCROLLED
            NovaAction.SEARCH, NovaAction.WEB_SEARCH,
            NovaAction.FOOD_SEARCH, NovaAction.SEARCH_SWIGGY, NovaAction.SEARCH_ZOMATO ->
                observed.packageName != NOVA_PACKAGE &&
                    (windowTransition || contentTransition) &&
                    containsSearchEvidence(target, observed.visibleText)
            else -> false
        }
    }

    private fun containsSearchEvidence(target: String, visibleText: String): Boolean {
        if (target.isBlank() || visibleText.length < 18) return false
        val normalizedText = visibleText.lowercase()
        val terms = target.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .distinct()
        return terms.isNotEmpty() && terms.any { normalizedText.contains(it) }
    }
}
