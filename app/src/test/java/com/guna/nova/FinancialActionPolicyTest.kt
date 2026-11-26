package com.guna.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialActionPolicyTest {
    @Test
    fun deniesEveryKnownFinancialCapability() {
        listOf(
            "PAY", "PAYMENT", "BILL_PAYMENT", "TRANSFER", "MONEY_TRANSFER",
            "BANK_TRANSFER", "UPI_TRANSFER", "PURCHASE", "BUY", "CHECKOUT",
            "ORDER", "PLACE_ORDER", "PAID_ORDER", "FOOD_ORDER", "RECHARGE",
            "TOP_UP", "SUBSCRIBE", "SUBSCRIPTION", "RENEW_SUBSCRIPTION",
            "FINANCIAL_TRANSACTION"
        ).forEach { capability ->
            assertTrue("Must deny $capability", FinancialActionPolicy.mustDeny(capability))
        }
    }

    @Test
    fun deniesEnglishTransactionRequests() {
        listOf(
            "Make a payment now",
            "Pay my bill",
            "Send money to my friend",
            "Transfer funds",
            "Buy this",
            "Purchase it",
            "Place an order",
            "Order food",
            "Recharge my phone",
            "Subscribe now"
        ).forEach { request ->
            assertTrue("Must deny: $request", FinancialActionPolicy.mustDeny(requestOrArguments = request))
        }
    }

    @Test
    fun deniesTamilAndTanglishTransactionRequests() {
        listOf(
            "பணம் செலுத்து",
            "பணம் அனுப்பு",
            "பில் கட்டு",
            "ரீசார்ஜ் பண்ணு",
            "ஆர்டர் பண்ணு",
            "panam anuppu",
            "panam seluthu",
            "bill kattu",
            "gpay pannu",
            "recharge pannu",
            "order pannu",
            "vaangi kudu"
        ).forEach { request ->
            assertTrue("Must deny: $request", FinancialActionPolicy.mustDeny(requestOrArguments = request))
        }
    }

    @Test
    fun deniesFinancialIntentInModelArgumentsEvenWhenActionLooksHarmless() {
        assertTrue(
            FinancialActionPolicy.mustDeny(
                capability = "SEARCH_SWIGGY",
                requestOrArguments = "order biryani and pay now"
            )
        )
        assertTrue(
            FinancialActionPolicy.mustDeny(
                capability = "FOOD_SEARCH",
                requestOrArguments = "place an order for groceries"
            )
        )
    }

    @Test
    fun allowsInformationalQuestionsAndNonTransactionalFoodDiscovery() {
        listOf(
            "What is UPI?",
            "Explain how subscriptions work",
            "Find nearby restaurants",
            "I'm hungry",
            "Search Swiggy for vegetarian restaurants"
        ).forEach { request ->
            assertFalse("Should remain informational/search-only: $request",
                FinancialActionPolicy.mustDeny(requestOrArguments = request))
        }
    }

    @Test
    fun unknownCapabilityIsNotAccidentallyRegisteredAsFinancial() {
        assertFalse(FinancialActionPolicy.isProhibitedCapability("OPEN_APP"))
        assertFalse(FinancialActionPolicy.isProhibitedCapability("CONVERSATION"))
    }
}
