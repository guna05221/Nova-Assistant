package com.guna.nova

import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Local-only model lifecycle for Nova. The GGUF is stored in app-private files,
 * not bundled into the APK. Inference never sends prompts to a server.
 */
object LocalAiManager {
    const val MODEL_NAME = "Qwen3.5 4B Q4_K_M"
    private const val FILE_NAME = "Qwen3.5-4B-Q4_K_M.gguf"
    private const val MODEL_URL = "https://huggingface.co/ggml-org/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf"
    private const val EXPECTED_SHA256 = "217002fa8008ef4fc6d548353a3aebef055a96e6ff8e0e2c0953ef8254a89c14"
    private const val CONTEXT_SIZE = 2048

    data class UiState(
        val status: String = "Local AI model not installed",
        val progress: Float = 0f,
        val downloading: Boolean = false,
        val ready: Boolean = false
    )

    private val mutableState = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var loadedModel: LlamaModel? = null
    @Volatile private var loadedPath: String? = null

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), FILE_NAME)

    fun isDownloaded(context: Context): Boolean = modelFile(context).let {
        it.isFile && it.length() > 2_000_000_000L
    }

    fun downloadAndLoad(context: Context) {
        if (mutableState.value.downloading) return
        scope.launch {
            try {
                mutableState.value = mutableState.value.copy(
                    status = "Preparing model download…", downloading = true, progress = 0f
                )
                val file = downloadModel(context.applicationContext)
                loadModel(file)
                mutableState.value = UiState(
                    status = "$MODEL_NAME is ready — running offline",
                    progress = 1f, downloading = false, ready = true
                )
            } catch (e: Throwable) {
                mutableState.value = UiState(
                    status = "Local AI setup failed: ${e.message ?: e.javaClass.simpleName}",
                    progress = 0f, downloading = false, ready = false
                )
            }
        }
    }

    fun interpretAsync(context: Context, utterance: String, onResult: (String?) -> Unit) {
        scope.launch {
            val result = interpret(context.applicationContext, utterance)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    suspend fun interpret(context: Context, utterance: String): String? {
        if (!isDownloaded(context)) return null
        return try {
            val model = loadedModel ?: loadModel(modelFile(context))
            val systemPrompt = """
                You are Nova, a safe Android voice assistant. Understand English, Tamil and Tanglish.
                Convert the user's spoken request into exactly one JSON object. No markdown, no extra text.
                You are the reasoning and intent-planning layer for Nova, a hybrid Android agent.
                Understand unrestricted natural language in English, Tamil and Tanglish. Infer the user's goal,
                not just exact command phrases. For example, "I'm hungry", "சாப்பிடணும்", or "pasikkuthu"
                means the user wants food discovery and must map to FOOD_SEARCH.
                Return exactly one JSON object, no markdown:
                {"action":"ACTION","target":"","reply":""}
                Allowed actions:
                FOOD_SEARCH (find nearby restaurants/food; target may contain cuisine or food preference),
                SEARCH_SWIGGY (search delivery food in Swiggy; target is food/preference),
                SEARCH_ZOMATO (search delivery food in Zomato; target is food/preference),
                WEB_SEARCH (search the web for target),
                OPEN_APP (target is installed app name),
                SEARCH (target is general search query),
                HOME, BACK, SETTINGS, SCROLL_UP, SCROLL_DOWN, COPY_LAST_MESSAGE, PASTE,
                TYPE_TEXT (target is the exact text to enter in the currently focused field),
                SEND (only when the user explicitly says to send; click a clearly labelled Send button),
                CONVERSATION (reply is a short natural response),
                UNSUPPORTED (reply explains briefly that the action is not available).
                Choose FOOD_SEARCH for hunger, wanting to eat, finding restaurants, or asking what food is nearby.
                Choose SEARCH_SWIGGY or SEARCH_ZOMATO when the user names that service or asks to search there.
                Use WEB_SEARCH for live information, research, recommendations, and requests that need internet search.
                Preserve useful cuisine, dish, budget, and location details in target. Do not invent live prices,
                restaurant availability, location, contacts, message content or search results.
                Answer ordinary questions, explanations, advice, definitions, calculations, jokes and casual conversation with CONVERSATION. Do not turn normal questions into web searches. Use WEB_SEARCH only when the user explicitly requests an online search or live lookup.
                TYPE_TEXT only types into the currently focused editable field. Never send automatically after typing.
                SEND is permitted only after an explicit user instruction such as "send it"; click only a clearly labelled enabled Send/Submit button. If unavailable, explain that nothing was sent.
                Never claim an action or search has completed before the Android action is opened.
                The app can only open installed apps and Android Settings, navigate Home/Back, scroll,
                copy visible text and paste clipboard text when Accessibility is enabled, open Google
                searches, and hand off nearby food searches to Maps, Swiggy or Zomato.
                Search handoffs do not read or compare live results automatically. Do not claim arbitrary
                phone control, calling, notification/chat reading, bookings, ordering, purchases, payments,
                transfers, file deletion, or security changes are supported.
                Never make payments, transfers, purchases, subscriptions, or place food orders, even if
                the user confirms. For unsupported actions use UNSUPPORTED with a concise explanation.
                If critical information is missing, use CONVERSATION and ask one short question.
            """.trimIndent()
            val prompt = "User said: ${utterance.take(1200)}\nReturn one JSON action."
            Llama.complete(
                model = model,
                prompt = prompt,
                systemPrompt = systemPrompt,
                maxTokens = 160
            ).text
        } catch (e: Throwable) {
            mutableState.value = mutableState.value.copy(
                status = "Local AI inference error: ${e.message ?: e.javaClass.simpleName}",
                ready = false
            )
            null
        }
    }

    suspend fun loadIfPresent(context: Context): Boolean {
        if (!isDownloaded(context)) return false
        return try {
            loadModel(modelFile(context))
            true
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun loadModel(file: File): LlamaModel = mutex.withLock {
        loadedModel?.let { if (loadedPath == file.absolutePath) return it }
        mutableState.value = mutableState.value.copy(status = "Loading local AI model…", ready = false)
        val model = Llama.loadModel(
            modelPath = file.absolutePath,
            config = LlamaConfig(
                contextSize = CONTEXT_SIZE,
                threads = 4,
                gpuLayers = 0,
                temperature = 0.15f,
                topP = 0.85f,
                topK = 30
            )
        )
        loadedModel = model
        loadedPath = file.absolutePath
        mutableState.value = mutableState.value.copy(
            status = "$MODEL_NAME loaded on this phone",
            progress = 1f,
            ready = true
        )
        model
    }

    private suspend fun downloadModel(context: Context): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "models")
        if (!dir.exists() && !dir.mkdirs()) error("Cannot create model folder")
        val output = File(dir, FILE_NAME)
        if (output.exists() && output.length() > 2_000_000_000L) return@withContext output

        val part = File(dir, "$FILE_NAME.part")
        var existing = if (part.exists()) part.length() else 0L
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nova-Android/1.0")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0
            if (code != HttpURLConnection.HTTP_OK && !append) error("Model server returned HTTP $code")
            if (!append) existing = 0L
            val remaining = connection.contentLengthLong.coerceAtLeast(0L)
            val total = if (append && remaining > 0) existing + remaining else remaining
            connection.inputStream.use { input ->
                java.io.RandomAccessFile(part, "rw").use { raf ->
                    if (append) raf.seek(existing) else raf.setLength(0L)
                    val buffer = ByteArray(1024 * 1024)
                    var count = existing
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        raf.write(buffer, 0, n)
                        count += n
                        if (total > 0) {
                            mutableState.value = mutableState.value.copy(
                                status = "Downloading $MODEL_NAME — ${(count * 100 / total).coerceIn(0,100)}%",
                                progress = (count.toDouble() / total).toFloat().coerceIn(0f, 1f)
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (part.length() < 2_000_000_000L) error("Model download is incomplete")
        mutableState.value = mutableState.value.copy(status = "Verifying model file…")
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }.lowercase(Locale.ROOT)
        if (actual != EXPECTED_SHA256) {
            part.delete()
            error("Model checksum did not match; the incomplete file was discarded")
        }
        if (output.exists()) output.delete()
        if (!part.renameTo(output)) error("Could not save the downloaded model")
        output
    }
}
