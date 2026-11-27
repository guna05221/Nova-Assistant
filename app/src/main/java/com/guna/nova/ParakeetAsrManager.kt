package com.guna.nova

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Local English ASR model lifecycle. The 0.6B INT8 model is downloaded only
 * after the user explicitly taps Download; it is never bundled into the APK.
 *
 * The upstream Sherpa export is offline/non-streaming. Nova provides simulated
 * streaming by periodically decoding the current utterance buffer.
 */
object ParakeetAsrManager {
    const val MODEL_NAME = "Parakeet Unified EN 0.6B INT8"
    private const val ARCHIVE_NAME = "parakeet-unified-en-0.6b-int8.tar.bz2"
    private const val MODEL_DIR_NAME = "parakeet-unified-en-0.6b-int8-non-streaming"
    private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming.tar.bz2"

    data class State(
        val status: String = "Parakeet model not installed",
        val progress: Float = 0f,
        val downloading: Boolean = false,
        val ready: Boolean = false
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var recognizerPath: String? = null

    fun modelDirectory(context: Context) = File(File(context.filesDir, "models"), MODEL_DIR_NAME)

    fun isInstalled(context: Context): Boolean {
        val dir = modelDirectory(context)
        return File(dir, "encoder.int8.onnx").isFile &&
            File(dir, "decoder.int8.onnx").isFile &&
            File(dir, "joiner.int8.onnx").isFile &&
            File(dir, "tokens.txt").isFile
    }

    fun download(context: Context) {
        if (mutableState.value.downloading) return
        scope.launch {
            try {
                mutableState.value = State("Downloading $MODEL_NAME…", 0f, true, false)
                val dir = withContext(Dispatchers.IO) { downloadAndExtract(context.applicationContext) }
                load(dir)
                mutableState.value = State("$MODEL_NAME ready — offline English recognition", 1f, false, true)
            } catch (t: Throwable) {
                mutableState.value = State("Parakeet setup failed: ${t.message ?: t.javaClass.simpleName}", 0f, false, false)
            }
        }
    }

    suspend fun loadIfInstalled(context: Context): Boolean {
        if (!isInstalled(context)) return false
        return try {
            load(modelDirectory(context))
            mutableState.value = State("$MODEL_NAME loaded — offline", 1f, false, true)
            true
        } catch (t: Throwable) {
            mutableState.value = State("Parakeet load failed: ${t.message ?: t.javaClass.simpleName}", 0f, false, false)
            false
        }
    }

    private suspend fun load(dir: File): OfflineRecognizer = mutex.withLock {
        recognizer?.let { if (recognizerPath == dir.absolutePath) return it }
        mutableState.value = mutableState.value.copy(status = "Loading $MODEL_NAME…", ready = false)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                    joiner = File(dir, "joiner.int8.onnx").absolutePath
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                modelType = "nemo_transducer",
                numThreads = 3,
                provider = "cpu"
            ),
            decodingMethod = "greedy_search"
        )
        val created = withContext(Dispatchers.Default) { OfflineRecognizer(config = config) }
        recognizer?.release()
        recognizer = created
        recognizerPath = dir.absolutePath
        created
    }

    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.Default) {
        require(samples.size >= 4000) { "Speak a little longer before transcription." }
        val active = recognizer ?: error("Download and load Parakeet first.")
        val stream = active.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            active.decode(stream)
            active.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private suspend fun downloadAndExtract(context: Context): File = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "models")
        if (!root.exists() && !root.mkdirs()) error("Cannot create private model directory")
        val target = File(root, MODEL_DIR_NAME)
        if (isInstalled(context)) return@withContext target

        val archive = File(root, "$ARCHIVE_NAME.part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 120000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nova-Android-ASR/1.0")
        }
        try {
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("Model server returned HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var count = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        count += n
                        if (total > 0) {
                            mutableState.value = mutableState.value.copy(
                                status = "Downloading Parakeet — ${(count * 100 / total).toInt()}%",
                                progress = (count.toDouble() / total).toFloat().coerceIn(0f, 1f)
                            )
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (!archive.isFile || archive.length() < 100_000_000L) error("Model archive is incomplete")

        val staging = File(root, "$MODEL_DIR_NAME.extracting")
        staging.deleteRecursively()
        if (!staging.mkdirs()) error("Cannot create model extraction directory")
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val name = entry.name.replace('\\\\', '/')
                    if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                        error("Unsafe model archive entry")
                    }
                    val output = File(staging, name.substringAfter('/', name))
                    if (!output.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        error("Unsafe model archive path")
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { tar.copyTo(it) }
                    }
                }
            }
            val modelRoot = if (File(staging, "encoder.int8.onnx").exists()) staging
                else staging.walkTopDown().firstOrNull { it.isDirectory && File(it, "encoder.int8.onnx").exists() }
                    ?: error("Parakeet encoder missing from model archive")
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
                .forEach { if (!File(modelRoot, it).isFile) error("Missing model file: $it") }

            val prepared = File(root, "$MODEL_DIR_NAME.ready")
            prepared.deleteRecursively()
            if (!modelRoot.copyRecursively(prepared, overwrite = true)) error("Could not prepare model files")
            target.deleteRecursively()
            if (!prepared.renameTo(target)) error("Could not activate downloaded model")
        } finally {
            staging.deleteRecursively()
            archive.delete()
        }
        target
    }

    fun release() {
        recognizer?.release()
        recognizer = null
        recognizerPath = null
    }
}
