package com.guna.nova

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API credentials are encrypted with an Android Keystore AES-GCM key.
 * Keys are never written to source code, logs, or app diagnostics.
 */
object AIProviderManager {
    private const val PREFS = "nova_ai_secure"
    private const val GEMINI = "gemini"
    private const val GROQ = "groq"
    private const val KEY_ALIAS = "nova_ai_credentials_v1"
    private const val GEMINI_MODEL = "gemini-3.5-flash-lite"
    private const val GROQ_MODEL = "openai/gpt-oss-20b"

    data class Result(val ok: Boolean, val message: String, val provider: String? = null)

    fun hasGemini(context: Context) = get(context, GEMINI).isNotBlank()
    fun hasGroq(context: Context) = get(context, GROQ).isNotBlank()

    fun saveGemini(context: Context, value: String) = save(context, GEMINI, value.trim())
    fun saveGroq(context: Context, value: String) = save(context, GROQ, value.trim())

    fun clearGemini(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(GEMINI).apply()
    fun clearGroq(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(GROQ).apply()

    private fun save(context: Context, name: String, value: String) {
        require(value.isNotBlank()) { "API key cannot be empty" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(name, android.util.Base64.encodeToString(packed, android.util.Base64.NO_WRAP)).apply()
    }

    private fun get(context: Context, name: String): String {
        val packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return ""
        return try {
            val bytes = android.util.Base64.decode(packed, android.util.Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val data = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    fun testGemini(context: Context): Result {
        val key = get(context, GEMINI)
        if (key.isBlank()) return Result(false, "Add your Gemini API key first.")
        return try {
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", "Reply with exactly: Gemini connection successful")))))
            val response = request(
                "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
                "x-goog-api-key", key, body.toString()
            )
            if (response.first in 200..299) Result(true, "Gemini connected successfully.", "Gemini")
            else Result(false, "Gemini returned HTTP ${response.first}. Check the key, model access or quota.")
        } catch (e: Exception) { Result(false, "Gemini test failed: ${e.message ?: "network error"}") }
    }

    fun testGroq(context: Context): Result {
        val key = get(context, GROQ)
        if (key.isBlank()) return Result(false, "Add your Groq API key first.")
        return try {
            val body = chatBody("Reply with exactly: Groq connection successful")
            val response = request("https://api.groq.com/openai/v1/chat/completions", "Authorization", "Bearer $key", body.toString())
            if (response.first in 200..299) Result(true, "Groq connected successfully.", "Groq")
            else Result(false, "Groq returned HTTP ${response.first}: ${providerError(response.second)}")
        } catch (e: Exception) { Result(false, "Groq test failed: ${e.message ?: "network error"}") }
    }

    /** Gemini primary, Groq fallback. Returns null if neither provider is configured/available. */
    fun plan(context: Context, systemPrompt: String, userText: String): Result {
        val geminiKey = get(context, GEMINI)
        if (geminiKey.isNotBlank()) {
            try {
                val parts = JSONArray().put(JSONObject().put("text", systemPrompt + "\\nUser said: " + userText.take(1200) + "\\nReturn one JSON action."))
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                    .put("generationConfig", JSONObject().put("temperature", 0.1).put("maxOutputTokens", 220))
                val response = request("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
                    "x-goog-api-key", geminiKey, body.toString())
                if (response.first in 200..299) {
                    val root = JSONObject(response.second)
                    val text = root.getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    return Result(true, text, "Gemini")
                }
                if (response.first !in listOf(408, 429, 500, 502, 503, 504)) return Result(false, "Gemini request failed (HTTP ${response.first}).")
            } catch (_: Exception) { /* try configured fallback */ }
        }
        val groqKey = get(context, GROQ)
        if (groqKey.isNotBlank()) {
            return try {
                val body = JSONObject().put("model", GROQ_MODEL).put("temperature", 0.1).put("max_tokens", 220)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userText.take(1200) + "\\nReturn one JSON action.")))
                val response = request("https://api.groq.com/openai/v1/chat/completions",
                    "Authorization", "Bearer $groqKey", body.toString())
                if (response.first in 200..299) {
                    val text = JSONObject(response.second).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content")
                    Result(true, text, "Groq")
                } else Result(false, "Groq request failed (HTTP ${response.first}): ${providerError(response.second)}")
            } catch (e: Exception) { Result(false, "Groq request failed: ${e.message ?: "network error"}") }
        }
        return Result(false, "No cloud AI key is configured.")
    }

    private fun providerError(body: String): String {
        return try {
            val root = JSONObject(body)
            root.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }?.take(180) ?: "Check model access, API key or quota."
        } catch (_: Exception) { "Check model access, API key or quota." }
    }

    private fun chatBody(text: String) = JSONObject().put("model", GROQ_MODEL)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))

    private fun request(url: String, header: String, value: String, body: String): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 25000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty(header, value)
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to response
        } finally { connection.disconnect() }
    }
}
