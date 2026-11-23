package com.guna.nova

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

private const val CHATGPT_PREFS = "nova_chatgpt"
private const val CHATGPT_RESOURCE = "https://api.openai.com/v1"
private const val CHATGPT_ISSUER = "https://auth.openai.com"
private const val CHATGPT_REDIRECT = "http://127.0.0.1:1455/auth/callback"
private const val CHATGPT_MODEL = "gpt-6.2-sol"

object ChatGptAccountStore {
    fun isConnected(context: Context): Boolean =
        context.getSharedPreferences(CHATGPT_PREFS, Context.MODE_PRIVATE)
            .getBoolean("verified_connection", false)

    fun status(context: Context): String =
        context.getSharedPreferences(CHATGPT_PREFS, Context.MODE_PRIVATE)
            .getString("status", "Connect your ChatGPT account.") ?: "Connect your ChatGPT account."

    fun email(context: Context): String =
        context.getSharedPreferences(CHATGPT_PREFS, Context.MODE_PRIVATE)
            .getString("email", "").orEmpty()

    internal fun save(context: Context, record: JSONObject) {
        val encrypted = ChatGptTokenVault.encrypt(record.toString())
        context.getSharedPreferences(CHATGPT_PREFS, Context.MODE_PRIVATE).edit()
            .putString("credentials", encrypted)
            .putString("email", record.optString("email"))
            .putBoolean("verified_connection", true)
            .putString("status", "Connected · ChatGPT plan permission verified")
            .apply()
    }

    internal fun credentials(context: Context): JSONObject? = try {
        val encrypted = context.getSharedPreferences(CHATGPT_PREFS, Context.MODE_PRIVATE)
            .getString("credentials", null) ?: return null
        JSONObject(ChatGptTokenVault.decrypt(encrypted))
    } catch (_: Exception) { null }
}

object ChatGptTokenVault {
    private const val KEY_ALIAS = "nova_chatgpt_oauth_aes"

    private fun key(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun decrypt(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        val iv = bytes.copyOfRange(0, 12)
        val encrypted = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }
}

object ChatGptAccountManager {
    fun startSignIn(context: Context, onStatus: (String) -> Unit) {
        setStatus(context, "Preparing secure ChatGPT sign-in…")
        Toast.makeText(context, "Opening secure ChatGPT sign-in", Toast.LENGTH_SHORT).show()
        thread(name = "nova-chatgpt-oauth") {
            try {
                authorize(context.applicationContext)
                onStatus(ChatGptAccountStore.status(context))
            } catch (e: Exception) {
                val message = e.message?.take(180) ?: "ChatGPT sign-in failed. Please try again."
                setStatus(context, message)
                onStatus(message)
            }
        }
    }

    /** Primary Nova conversation provider. Returns null when no verified ChatGPT plan is connected. */
    fun chat(context: Context, history: org.json.JSONArray): AIProviderManager.Result? {
        var record = ChatGptAccountStore.credentials(context) ?: return null
        return try {
            if (record.optLong("expires_at") <= System.currentTimeMillis() + 60_000L) {
                val refreshed = postForm("$CHATGPT_ISSUER/api/accounts/oauth/token", mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to record.getString("client_id"),
                    "refresh_token" to record.getString("refresh_token"),
                    "resource" to CHATGPT_RESOURCE
                ))
                val scopes = refreshed.optString("scope", record.optString("scope")).split(" ").filter { it.isNotBlank() }.toSet()
                require("chatgpt.tokens.use.direct" in scopes && "resource.invoke" in scopes) {
                    "ChatGPT plan permission is no longer available. Reconnect your account."
                }
                record.put("access_token", refreshed.getString("access_token"))
                record.put("refresh_token", refreshed.optString("refresh_token", record.getString("refresh_token")))
                record.put("expires_at", System.currentTimeMillis() + refreshed.optLong("expires_in") * 1000)
                record.put("scope", scopes.joinToString(" "))
                refreshed.optString("id_token").takeIf { it.isNotBlank() }?.let { record.put("id_token", it) }
                ChatGptAccountStore.save(context, record)
            }

            val input = org.json.JSONArray()
            input.put(org.json.JSONObject().put("role", "system").put("content",
                "You are Nova, a natural, helpful Android assistant. Use the supplied conversation history for follow-ups. Be clear and conversational. Do not claim phone actions happened unless Nova verifies them. Never perform or authorize payments, transfers, purchases, subscriptions, recharges or paid orders. Nova's separate safety layer controls all device actions."))
            for (i in 0 until history.length()) {
                val item = history.optJSONObject(i) ?: continue
                val role = item.optString("role")
                if (role == "user" || role == "assistant") {
                    input.put(org.json.JSONObject().put("role", role)
                        .put("content", item.optString("content").take(1600)))
                }
            }
            val body = org.json.JSONObject().put("model", CHATGPT_MODEL)
                .put("input", input).put("store", false).put("stream", true)
            val connection = URL("$CHATGPT_RESOURCE/responses").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${record.getString("access_token")}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                error("ChatGPT request failed (HTTP $code): ${detail.take(160)}")
            }
            val answer = StringBuilder()
            var completed = false
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data: ")) return@forEach
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") return@forEach
                    try {
                        val event = org.json.JSONObject(data)
                        when (event.optString("type")) {
                            "response.output_text.delta" -> answer.append(event.optString("delta"))
                            "response.completed" -> completed = true
                            "response.failed" -> error("ChatGPT response failed.")
                        }
                    } catch (_: org.json.JSONException) { }
                }
            }
            connection.disconnect()
            require(completed && answer.isNotBlank()) { "ChatGPT stream ended before a completed response." }
            AIProviderManager.Result(true, answer.toString().trim(), "ChatGPT")
        } catch (e: Exception) {
            AIProviderManager.Result(false, e.message ?: "ChatGPT request failed.", "ChatGPT")
        }
    }

    fun disconnect(context: Context) {
        context.getSharedPreferences(CHATGPT_PREFS, Context.MODE_PRIVATE).edit()
            .remove("credentials").remove("email").putBoolean("verified_connection", false)
            .putString("status", "ChatGPT is disconnected.").apply()
    }

    private fun setStatus(context: Context, value: String) {
        context.getSharedPreferences(CHATGPT_PREFS, Context.MODE_PRIVATE).edit()
            .putString("status", value).putBoolean("verified_connection", false).apply()
    }

    private fun authorize(context: Context) {
        val hostId = context.getSharedPreferences(CHATGPT_PREFS, Context.MODE_PRIVATE)
            .getString("ext_agent_host_id", null) ?: "urn:uuid:${UUID.randomUUID()}".also {
                context.getSharedPreferences(CHATGPT_PREFS, Context.MODE_PRIVATE).edit()
                    .putString("ext_agent_host_id", it).apply()
            }

        val state = random(32)
        val nonce = random(32)
        val verifier = random(48)
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val callbackServer = ServerSocket(1455, 1, InetAddress.getByName("127.0.0.1"))
        callbackServer.soTimeout = 240_000

        val params = linkedMapOf(
            "client_id" to "dynamic_agent_client",
            "agent_name_hint" to "Nova",
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to CHATGPT_REDIRECT,
            "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
            "resource" to CHATGPT_RESOURCE,
            "state" to state,
            "nonce" to nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge
        )
        val authUrl = "$CHATGPT_ISSUER/api/accounts/authorize?" +
            params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            setStatus(context, "Waiting for ChatGPT authorization in your browser…")

            callbackServer.accept().use { socket ->
                socket.soTimeout = 20_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: error("No sign-in callback received.")
                while (reader.readLine()?.isNotEmpty() == true) { }
                val target = requestLine.split(" ").getOrNull(1) ?: error("Invalid sign-in callback.")
                val callback = Uri.parse("http://127.0.0.1$target")
                val returnedState = callback.getQueryParameter("state")
                require(returnedState == state) { "ChatGPT sign-in state did not match. Please retry." }
                val oauthError = callback.getQueryParameter("error")
                require(oauthError == null) {
                    if (oauthError == "access_denied") "ChatGPT plan permission was not granted."
                    else "ChatGPT sign-in was cancelled or rejected."
                }
                val code = callback.getQueryParameter("code") ?: error("ChatGPT did not return an authorization code.")
                val clientId = callback.getQueryParameter("client_id")
                    ?: error("OpenAI did not return a registered client ID.")
                val page = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n<html><body style='font-family:sans-serif;padding:32px'><h2>Nova sign-in received</h2><p>You can return to Nova.</p></body></html>"
                socket.getOutputStream().write(page.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()

                setStatus(context, "Verifying your ChatGPT account and plan permission…")
                val token = postForm("$CHATGPT_ISSUER/api/accounts/oauth/token", mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "code_verifier" to verifier,
                    "redirect_uri" to CHATGPT_REDIRECT,
                    "resource" to CHATGPT_RESOURCE
                ))
                require(token.optString("token_type").equals("Bearer", true)) { "Unsupported ChatGPT token type." }
                val scopes = token.optString("scope").split(" ").filter { it.isNotBlank() }.toSet()
                require("chatgpt.tokens.use.direct" in scopes && "resource.invoke" in scopes) {
                    "Your ChatGPT account signed in, but OpenAI did not grant ChatGPT plan usage to Nova. Check plan eligibility and permissions."
                }
                val idToken = token.optString("id_token")
                require(idToken.isNotBlank()) { "OpenAI did not return a verifiable account identity." }
                val identity = verifyIdToken(idToken, clientId, nonce)
                val expiresIn = token.optLong("expires_in", 0)
                require(expiresIn in 1..86400) { "OpenAI returned an invalid token expiry." }

                val record = JSONObject().apply {
                    put("client_id", clientId)
                    put("subject", identity.first)
                    put("email", identity.second)
                    put("id_token", idToken)
                    put("access_token", token.getString("access_token"))
                    put("refresh_token", token.getString("refresh_token"))
                    put("expires_at", System.currentTimeMillis() + expiresIn * 1000)
                    put("scope", scopes.joinToString(" "))
                    put("host_id", hostId)
                }
                // Do not mark connected until a real Responses API request completes.
                val test = postResponse(record.getString("access_token"))
                require(test) { "ChatGPT account authorized, but the first Responses API test did not complete." }
                ChatGptAccountStore.save(context, record)
            }
        } finally {
            callbackServer.close()
        }
    }

    private fun postResponse(accessToken: String): Boolean {
        val body = JSONObject().apply {
            put("model", CHATGPT_MODEL)
            put("input", "Reply with exactly: Nova connection verified.")
            put("store", false)
            put("stream", true)
        }
        val connection = URL("$CHATGPT_RESOURCE/responses").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code !in 200..299) {
            val detail = (if (code >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("ChatGPT Responses test failed (HTTP $code): ${detail.take(140)}")
        }
        var completed = false
        connection.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") return@forEach
                try {
                    val event = JSONObject(data)
                    if (event.optString("type") == "response.completed") completed = true
                    if (event.optString("type") == "response.failed") {
                        throw IllegalStateException("ChatGPT reported a failed response.")
                    }
                } catch (e: org.json.JSONException) { }
            } }
        }
        connection.disconnect()
        return completed
    }

    private fun verifyIdToken(jwt: String, clientId: String, nonce: String): Pair<String, String> {
        val parts = jwt.split(".")
        require(parts.size == 3) { "Invalid OpenAI identity token." }
        val decoder = Base64.getUrlDecoder()
        val header = JSONObject(decoder.decode(parts[0]).toString(Charsets.UTF_8))
        require(header.optString("alg") == "RS256") { "Unsupported OpenAI identity signature." }
        val discoveryConnection = URL("$CHATGPT_ISSUER/.well-known/openid-configuration").openConnection() as HttpURLConnection
        discoveryConnection.connectTimeout = 15_000
        discoveryConnection.readTimeout = 15_000
        val discovery = JSONObject(discoveryConnection.inputStream.bufferedReader().use { it.readText() })
        require(discovery.optString("issuer") == CHATGPT_ISSUER) { "Unexpected OpenAI identity issuer metadata." }
        val jwksUri = discovery.optString("jwks_uri")
        require(Uri.parse(jwksUri).scheme == "https" && Uri.parse(jwksUri).host == "auth.openai.com") {
            "Unexpected OpenAI signing-key endpoint."
        }
        val jwksConnection = URL(jwksUri).openConnection() as HttpURLConnection
        jwksConnection.connectTimeout = 15_000
        jwksConnection.readTimeout = 15_000
        val jwks = JSONObject(jwksConnection.inputStream.bufferedReader().use { it.readText() })
        val keys = jwks.getJSONArray("keys")
        var selected: JSONObject? = null
        for (i in 0 until keys.length()) {
            val candidate = keys.getJSONObject(i)
            if (candidate.optString("kid") == header.optString("kid") &&
                candidate.optString("kty") == "RSA" && candidate.optString("use", "sig") == "sig") {
                selected = candidate
                break
            }
        }
        val jwk = selected ?: error("OpenAI identity signing key was not found.")
        val n = java.math.BigInteger(1, decoder.decode(jwk.getString("n")))
        val e = java.math.BigInteger(1, decoder.decode(jwk.getString("e")))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(verifier.verify(decoder.decode(parts[2]))) { "OpenAI identity signature verification failed." }
        val claims = JSONObject(decoder.decode(parts[1]).toString(Charsets.UTF_8))
        require(claims.optString("iss") == CHATGPT_ISSUER) { "Unexpected identity issuer." }
        val audience = claims.opt("aud")
        val audienceMatches = if (audience is org.json.JSONArray) {
            (0 until audience.length()).any { audience.optString(it) == clientId }
        } else audience == clientId
        require(audienceMatches) { "Identity token was issued for a different Nova client." }
        require(claims.optLong("exp") > System.currentTimeMillis() / 1000) { "OpenAI identity token has expired." }
        require(claims.optString("nonce") == nonce) { "OpenAI identity nonce did not match." }
        val subject = claims.optString("sub")
        require(subject.isNotBlank()) { "OpenAI identity token has no subject." }
        return subject to claims.optString("email")
    }

    private fun postForm(endpoint: String, fields: Map<String, String>): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val form = fields.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        connection.outputStream.use { OutputStreamWriter(it, Charsets.UTF_8).use { writer -> writer.write(form) } }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("ChatGPT token exchange failed (HTTP $code): ${text.take(160)}")
        return JSONObject(text)
    }

    private fun random(size: Int): String {
        val bytes = ByteArray(size).also { SecureRandom().nextBytes(it) }
        return b64(bytes)
    }
    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
