package com.guna.nova

/**
 * Pure routing policy for AI inference. Local Qwen is always preferred;
 * cloud is only eligible as a fallback after local inference fails and
 * the user has explicitly enabled cloud AI with a configured provider.
 */
object AiRoutingPolicy {
    fun cloudPermitted(consentEnabled: Boolean, providerConfigured: Boolean): Boolean =
        consentEnabled && providerConfigured

    fun shouldAttemptCloudFallback(
        localSucceeded: Boolean,
        consentEnabled: Boolean,
        providerConfigured: Boolean
    ): Boolean = !localSucceeded && cloudPermitted(consentEnabled, providerConfigured)
}
