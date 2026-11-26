package com.guna.nova

import java.util.Locale

/**
 * Central, deterministic deny policy for financial transaction capabilities.
 *
 * This is a safety boundary, not an intent model. All tool proposals and executor
 * entry points must call it. Financial capabilities are never registered as tools.
 */
internal object FinancialActionPolicy {
    private val prohibitedCapabilities = setOf(
        "PAY", "PAYMENT", "BILL_PAYMENT",
        "TRANSFER", "MONEY_TRANSFER", "BANK_TRANSFER", "UPI_TRANSFER",
        "PURCHASE", "BUY", "CHECKOUT",
        "ORDER", "PLACE_ORDER", "PAID_ORDER", "FOOD_ORDER",
        "RECHARGE", "TOP_UP",
        "SUBSCRIBE", "SUBSCRIPTION", "RENEW_SUBSCRIPTION",
        "FINANCIAL_TRANSACTION"
    )

    // Explicit transaction language. Keep informational questions such as
    // "what is UPI?" and "explain subscriptions" available as conversation.
    private val prohibitedPhrases = listOf(
        // English
        "make a payment", "make payment", "pay now", "pay the bill", "pay my bill",
        "pay this bill", "bill payment", "pay bill", "pay using upi", "pay through upi",
        "upi payment", "upi transfer", "send money", "transfer money", "money transfer",
        "bank transfer", "transfer funds", "buy this", "buy it", "purchase this",
        "purchase it", "checkout now", "place an order", "place order", "order this",
        "order it", "order food", "order biryani", "order groceries", "recharge my",
        "recharge phone", "mobile recharge", "data recharge", "top up my", "subscribe now",
        "start subscription", "renew subscription", "pay for this", "pay for it",
        // Tamil script
        "பணம் செலுத்து", "பணம் அனுப்பு", "பணம் மாற்று", "பில் கட்டு", "பில் செலுத்து",
        "கட்டணம் செலுத்து", "ரீசார்ஜ் செய்", "ரீசார்ஜ் பண்ணு", "ஆர்டர் பண்ணு",
        "ஆர்டர் செய்", "வாங்கி கொடு", "வாங்கிக் கொடு", "சந்தா கட்டு",
        // Common Tanglish
        "panam anuppu", "panam seluthu", "panam kattu", "panam maathu",
        "bill kattu", "bill seluthu", "payment pannu", "pay pannu",
        "upi la anuppu", "upi la pay pannu", "gpay pannu", "phonepe pannu",
        "paytm pannu", "recharge pannu", "mobile recharge pannu",
        "order pannu", "order pannunga", "vaangi kudu", "vaangikudu",
        "subscribe pannu", "subscription renew pannu", "checkout pannu"
    )

    private val imperativePatterns = listOf(
        Regex("""\b(?:pay|purchase|buy|recharge|subscribe|checkout)\s+(?:for\s+)?(?:this|it|now)\b"""),
        Regex("""\b(?:send|transfer)\s+(?:the\s+)?(?:money|funds)\b"""),
        Regex("""\b(?:place|make)\s+(?:an?\s+)?order\b"""),
        Regex("""\b(?:order|buy|purchase)\s+(?:the\s+)?(?:food|biryani|groceries|product|item)\b"""),
        Regex("""\b(?:renew|start)\s+(?:my\s+)?subscription\b""")
    )

    fun isProhibitedCapability(capability: String?): Boolean {
        val normalized = capability
            ?.uppercase(Locale.ROOT)
            ?.replace(Regex("[^A-Z0-9]+"), "_")
            ?.trim('_')
            ?: return false
        return normalized in prohibitedCapabilities
    }

    fun isFinancialRequest(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val normalized = normalize(text)
        if (prohibitedPhrases.any { normalized.contains(normalize(it)) }) return true
        return imperativePatterns.any { it.containsMatchIn(normalized) }
    }

    /**
     * Call for raw user requests, model-proposed action types/arguments,
     * and again immediately before any side effect in the executor.
     */
    fun mustDeny(capability: String? = null, requestOrArguments: String? = null): Boolean =
        isProhibitedCapability(capability) || isFinancialRequest(requestOrArguments)

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\p{Punct}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
