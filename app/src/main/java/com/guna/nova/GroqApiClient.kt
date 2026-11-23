package com.guna.nova

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small, single-provider Groq client. The API key is encrypted with an Android Keystore key
 * and is never written to logs or source control. This is suitable for a personal build,
 * not a guarantee against extraction on a compromised/rooted device.
 */
object GroqKeyStore {
    private const val PREFS = "nova_groq_key"
    private const val KEY_ALIAS = "nova_groq_api_key_v1"

    fun save(context: Context, value: String) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ivText = prefs.getString("iv", null) ?: return ""
        val dataText = prefs.getString("ciphertext", null) ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}

object GroqApiClient {
    private const val PREFS = "nova_groq_usage"
    private const val MODEL = "openai/gpt-oss-20b"
    private const val MAX_LOCAL_REQUESTS_PER_DAY = 900
    private const val MAX_LOCAL_TOKENS_PER_DAY = 180_000
    private const val MAX_OUTPUT_TOKENS = 350
    private val requestInFlight = AtomicBoolean(false)

    data class Result(val answer: String? = null, val error: String? = null)

    fun ask(context: Context, userText: String, history: JSONArray): Result {
        if (!requestInFlight.compareAndSet(false, true)) {
            return Result(error = "Nova is already thinking. Please wait for the current answer.")
        }
        try {
            val key = GroqKeyStore.get(context)
            if (key.isBlank()) return Result(error = "Add your Groq API key in Nova Settings to enable AI chat.")
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val today = LocalDate.now().toString()
            if (prefs.getString("day", "") != today) {
                prefs.edit().putString("day", today).putInt("requests", 0).putInt("tokens", 0).apply()
            }
            if (prefs.getInt("requests", 0) >= MAX_LOCAL_REQUESTS_PER_DAY ||
                prefs.getInt("tokens", 0) >= MAX_LOCAL_TOKENS_PER_DAY) {
                return Result(error = "Nova's local free-quota safety limit has been reached for today. Phone commands still work; AI chat will be available again tomorrow.")
            }

            val messages = JSONArray()
                .put(JSONObject()
                    .put("role", "system")
                    .put("content", "You are Nova, a helpful, natural voice assistant. Be conversational and concise by default. Answer the user's actual question directly. Do not claim to perform phone actions; Nova's Android command system handles those separately. If the user asks for detail, explain clearly."))
            for (i in 0 until history.length()) {
                val item = history.optJSONObject(i) ?: continue
                val role = item.optString("role")
                val content = item.optString("content")
                if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                    messages.put(JSONObject().put("role", role).put("content", content.take(1800)))
                }
            }
            messages.put(JSONObject().put("role", "user").put("content", userText.take(1800)))

            val payload = JSONObject()
                .put("model", MODEL)
                .put("messages", messages)
                .put("max_completion_tokens", MAX_OUTPUT_TOKENS)
                .put("stream", false)

            val connection = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    return when (status) {
                        401 -> Result(error = "Groq rejected the API key. Check the key in Nova Settings.")
                        429 -> Result(error = "Groq's free rate limit has been reached. Nova will not switch to a paid service. Please wait and try again later.")
                        402 -> Result(error = "Groq requires a paid billing action for this request. Nova will not enable billing or spend money.")
                        else -> Result(error = "Groq couldn't answer right now (HTTP $status). Please try again later.")
                    }
                }
                val root = JSONObject(body)
                val answer = root.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty().trim()
                if (answer.isBlank()) return Result(error = "Groq returned an empty answer. Please try again.")
                val used = root.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0
                prefs.edit()
                    .putInt("requests", prefs.getInt("requests", 0) + 1)
                    .putInt("tokens", (prefs.getInt("tokens", 0) + used).coerceAtMost(MAX_LOCAL_TOKENS_PER_DAY))
                    .apply()
                return Result(answer = answer)
            } finally {
                connection.disconnect()
            }
        } catch (_: java.net.SocketTimeoutException) {
            return Result(error = "Groq took too long to respond. Check your connection and try again.")
        } catch (_: Exception) {
            return Result(error = "Nova couldn't connect to Groq. Check your internet connection and try again.")
        } finally {
            requestInFlight.set(false)
        }
    }

    fun usage(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("day", "") != LocalDate.now().toString()) return 0 to 0
        return prefs.getInt("requests", 0) to prefs.getInt("tokens", 0)
    }
}
