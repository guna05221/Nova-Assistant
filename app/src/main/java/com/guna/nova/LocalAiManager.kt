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

    suspend fun interpret(context: Context, utterance: String): String? {
        if (!isDownloaded(context)) return null
        return try {
            val model = loadedModel ?: loadModel(modelFile(context))
            val systemPrompt = """
                You are Nova, a safe Android voice assistant. Understand English, Tamil and Tanglish.
                Convert the user's spoken request into exactly one JSON object. No markdown, no extra text.
                Allowed actions only:
                OPEN_APP (target is app name),
                SEARCH (target is search query),
                HOME, BACK, SETTINGS, SCROLL_UP, SCROLL_DOWN,
                COPY_LAST_MESSAGE, PASTE,
                CONVERSATION (reply is a short natural response),
                UNSUPPORTED (reply explains briefly that this action is not available).
                JSON shape: {"action":"ACTION","target":"","reply":""}
                Never claim an action was completed. Do not invent contact names, message content, app names or results.
                Sending messages, payments, deleting content, changing account/security settings and other sensitive actions are UNSUPPORTED.
                If the request is ambiguous, use CONVERSATION and ask one short clarification.
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
