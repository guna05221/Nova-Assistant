package com.guna.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRoutingPolicyTest {
    @Test
    fun localSuccessNeverRoutesToCloudEvenWhenCloudIsEnabled() {
        assertFalse(AiRoutingPolicy.shouldAttemptCloudFallback(
            localSucceeded = true,
            consentEnabled = true,
            providerConfigured = true
        ))
    }

    @Test
    fun localFailureUsesCloudOnlyWithExplicitConsentAndConfiguredProvider() {
        assertTrue(AiRoutingPolicy.shouldAttemptCloudFallback(
            localSucceeded = false,
            consentEnabled = true,
            providerConfigured = true
        ))
        assertFalse(AiRoutingPolicy.shouldAttemptCloudFallback(
            localSucceeded = false,
            consentEnabled = false,
            providerConfigured = true
        ))
        assertFalse(AiRoutingPolicy.shouldAttemptCloudFallback(
            localSucceeded = false,
            consentEnabled = true,
            providerConfigured = false
        ))
    }

    @Test
    fun missingConsentOrProviderNeverPermitsCloud() {
        assertFalse(AiRoutingPolicy.cloudPermitted(false, false))
        assertFalse(AiRoutingPolicy.cloudPermitted(false, true))
        assertFalse(AiRoutingPolicy.cloudPermitted(true, false))
        assertTrue(AiRoutingPolicy.cloudPermitted(true, true))
    }

    @Test
    fun offlineModeDoesNotAttemptAnyCloudFallback() {
        val consentEnabled = false
        val providerConfigured = true
        assertFalse(AiRoutingPolicy.shouldAttemptCloudFallback(
            localSucceeded = false,
            consentEnabled = consentEnabled,
            providerConfigured = providerConfigured
        ))
    }
}
