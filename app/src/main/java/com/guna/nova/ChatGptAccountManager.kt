package com.guna.nova

import android.content.Context
import android.widget.Toast

/**
 * Temporary account-state boundary for the Brain UI.
 *
 * The OAuth callback, PKCE exchange, ID-token/JWKS validation, secure token
 * persistence and Responses API verification are intentionally not simulated.
 * The connect action reports that limitation instead of opening an unsafe or
 * incomplete sign-in flow.
 */
object ChatGptAccountStore {
    private const val PREFS = "nova_chatgpt"

    fun isConnected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("verified_connection", false)

    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("status", "ChatGPT sign-in is not configured yet.") ?: "ChatGPT sign-in is not configured yet."
}

object ChatGptAccountManager {
    fun startSignIn(context: Context, onStatus: (String) -> Unit) {
        val message = "ChatGPT sign-in is not ready yet. Nova still needs the official OAuth callback, PKCE token exchange, account verification and a successful Responses API test."
        context.getSharedPreferences("nova_chatgpt", Context.MODE_PRIVATE).edit()
            .putBoolean("verified_connection", false)
            .putString("status", message)
            .apply()
        onStatus(message)
        Toast.makeText(context, "ChatGPT connection is not ready in this build.", Toast.LENGTH_LONG).show()
    }

    fun disconnect(context: Context) {
        context.getSharedPreferences("nova_chatgpt", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("verified_connection", false)
            .putString("status", "ChatGPT is disconnected.")
            .apply()
    }
}
