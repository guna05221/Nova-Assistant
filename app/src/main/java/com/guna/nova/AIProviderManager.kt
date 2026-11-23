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
            else {
                val detail = try {
                    val error = JSONObject(response.second).optJSONObject("error")
                    error?.optString("message").orEmpty()
                } catch (_: Exception) { "" }
                val safeDetail = detail.replace(Regex("AIza[0-9A-Za-z_-]{20,}"), "[redacted]").take(240)
                Result(false, "Gemini HTTP ${response.first}: ${safeDetail.ifBlank { "Google did not provide details. Check model access and API key restrictions." }}")
            }
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
        // ChatGPT website sign-in is manual-only; it is not a supported background API.
        val geminiKey = get(context, GEMINI)
        if (geminiKey.isNotBlank()) {
            try {
                val parts = JSONArray().put(JSONObject().put("text", systemPrompt + "\nUser said: " + userText.take(1200) + "\nReturn one JSON action."))
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
                        .put(JSONObject().put("role", "user").put("content", userText.take(1200) + "\nReturn one JSON action.")))
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


    data class ResearchSource(val title: String, val url: String)
    data class ResearchResult(val ok: Boolean, val report: String, val sources: List<ResearchSource>, val error: String = "")

    /**
     * Grounded online research using Gemini Google Search. Source URLs are taken only
     * from grounding metadata returned by the provider; Nova does not invent links.
     */
    fun research(context: Context, query: String): ResearchResult {
        val key = get(context, GEMINI)
        if (key.isBlank()) return ResearchResult(false, "", emptyList(), "Connect Gemini to enable grounded online research.")
        return try {
            val prompt = "Act as an independent research analyst. Understand the user's actual goal and choose relevant sources and evaluation criteria yourself. Request: " + query.take(900) +
                "\\nResearch broadly using Google Search. Compare several relevant options, not just the first result. " +
                "Evaluate official descriptions/specifications, independent reviews, user comments where indexed, " +
                "creator/provider credibility, quality, recency, suitability and value. For videos include exact title, " +
                "creator, duration, language, syllabus/content and learner feedback when evidence is available. " +
                "For restaurants include cuisine, specific dishes and menu prices; for nearby requests use only a " +
                "location explicitly supplied by the user, otherwise ask for their area. For products, travel and " +
                "services choose criteria appropriate to that category. Explain the best overall option and alternatives " +
                "with pros and cons. Separate verified facts, review evidence and your own inference. State when " +
                "comments, ratings, prices or other evidence could not be verified. Never fabricate ratings, review " +
                "counts, prices, distances, availability, comments or URLs. Prefer direct/official and current " +
                "independent sources. Format as a readable report with an executive recommendation, comparison, " +
                "why the top option wins, trade-offs and source titles. Do not claim review evidence unless found.";
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 1800))
            val response = request(
                "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
                "x-goog-api-key", key, body.toString()
            )
            if (response.first !in 200..299) return ResearchResult(false, "", emptyList(), "Online research failed (HTTP ${response.first}).")
            val root = JSONObject(response.second)
            val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
                ?: return ResearchResult(false, "", emptyList(), "The research provider returned no result.")
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            val report = buildString {
                if (parts != null) for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    append(part.optString("text"))
                }
            }.trim()
            val sources = mutableListOf<ResearchSource>()
            val chunks = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
            if (chunks != null) for (i in 0 until chunks.length()) {
                val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                val url = web.optString("uri")
                val title = web.optString("title").ifBlank { url }
                if (url.startsWith("https://") && sources.none { it.url == url }) {
                    sources.add(ResearchSource(title.take(160), url))
                }
            }
            if (report.isBlank()) ResearchResult(false, "", sources, "The research provider returned an empty report.")
            else ResearchResult(true, report, sources.take(12))
        } catch (e: Exception) {
            ResearchResult(false, "", emptyList(), "Online research error: ${e.message ?: "network unavailable"}")
        }
    }

    /**
     * Natural conversation endpoint. Unlike plan(), this returns plain language and
     * sends the bounded conversation history so follow-up questions retain context.
     * Gemini is primary; Groq is the automatic fallback.
     */
    fun chat(context: Context, history: JSONArray): Result {
        val system = "You are Nova, a helpful Android voice assistant. Speak naturally and directly. " +
            "Understand English, Tamil and Tanglish. Answer questions, explain concepts, tell jokes, " +
            "and handle follow-ups using the conversation history. When the user asks for another joke, " +
            "example, explanation, or recommendation, create a genuinely new one rather than repeating " +
            "the previous answer. When they say explain again, rephrase with a different explanation. " +
            "Only repeat verbatim when explicitly asked to repeat exactly. Keep spoken answers concise " +
            "but useful. Do not claim to have performed phone actions unless confirmed by the app. " +
            "Never make payments, transfers, purchases, subscriptions, or place paid orders, even if confirmed. " +
            "If asked to do these, politely refuse and offer safe information instead."
        // ChatGPT website sign-in is manual-only; it is not a supported background API.
        val geminiKey = get(context, GEMINI)
        if (geminiKey.isNotBlank()) {
            try {
                val contents = JSONArray()
                for (i in 0 until history.length()) {
                    val item = history.optJSONObject(i) ?: continue
                    val role = if (item.optString("role") == "assistant") "model" else "user"
                    val text = item.optString("content").take(1600)
                    if (text.isNotBlank()) contents.put(JSONObject().put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", text))))
                }
                val body = JSONObject().put("systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", contents)
                    .put("generationConfig", JSONObject().put("temperature", 0.85).put("maxOutputTokens", 500))
                val response = request(
                    "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
                    "x-goog-api-key", geminiKey, body.toString())
                if (response.first in 200..299) {
                    val answer = JSONObject(response.second).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
                    if (answer.isNotBlank()) return Result(true, answer, "Gemini")
                } else if (response.first !in listOf(408, 429, 500, 502, 503, 504)) {
                    return Result(false, "Gemini request failed (HTTP ${response.first}).")
                }
            } catch (_: Exception) { /* try Groq fallback */ }
        }
        val groqKey = get(context, GROQ)
        if (groqKey.isNotBlank()) {
            return try {
                val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                for (i in 0 until history.length()) {
                    val item = history.optJSONObject(i) ?: continue
                    val role = item.optString("role")
                    if (role == "user" || role == "assistant") messages.put(
                        JSONObject().put("role", role).put("content", item.optString("content").take(1600)))
                }
                val body = JSONObject().put("model", GROQ_MODEL).put("temperature", 0.85)
                    .put("max_tokens", 500).put("messages", messages)
                val response = request("https://api.groq.com/openai/v1/chat/completions",
                    "Authorization", "Bearer $groqKey", body.toString())
                if (response.first in 200..299) {
                    val answer = JSONObject(response.second).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                    Result(answer.isNotBlank(), answer.ifBlank { "I couldn't form an answer just now." }, "Groq")
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
