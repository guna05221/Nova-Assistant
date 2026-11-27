package com.guna.nova

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import org.json.JSONArray
import kotlin.math.sqrt

class VoiceIdentity(context: Context) {
    companion object {
        const val MODEL_ASSET = "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx"

        internal fun hasModelAsset(assetNames: Array<String>): Boolean =
            assetNames.any { it == MODEL_ASSET }

        internal fun requiresEnrollment(modelAvailable: Boolean, enrolled: Boolean): Boolean =
            modelAvailable && !enrolled
    }

    private val prefs = context.getSharedPreferences("nova_voice_identity", Context.MODE_PRIVATE)

    // Sherpa's native asset loader terminates the process when this model is absent.
    // Check the APK assets before entering JNI so speech recognition can still start safely.
    private val extractor: SpeakerEmbeddingExtractor? =
        if (hasModelAsset(context.assets.list("") ?: emptyArray())) {
            SpeakerEmbeddingExtractor(
                assetManager = context.assets,
                config = SpeakerEmbeddingExtractorConfig(
                    model = MODEL_ASSET,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )
        } else {
            Log.w("NovaVoice", "Speaker verification model is not packaged: $MODEL_ASSET")
            null
        }

    val isAvailable: Boolean
        get() = extractor != null

    fun embedding(samples: FloatArray): FloatArray {
        val activeExtractor = extractor
            ?: throw IllegalStateException("Speaker verification model is unavailable: $MODEL_ASSET")
        val stream: OnlineStream = activeExtractor.createStream()
        return try {
            stream.acceptWaveform(samples, 16000)
            stream.inputFinished()
            activeExtractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    fun saveProfile(samples: List<FloatArray>) {
        val vectors = samples.map { embedding(it) }.filter { it.isNotEmpty() }
        require(vectors.isNotEmpty()) { "No voice samples captured" }
        val average = FloatArray(vectors[0].size)
        vectors.forEach { vector ->
            for (i in average.indices) average[i] += vector[i] / vectors.size
        }
        val json = JSONArray()
        average.forEach { json.put(it.toDouble()) }
        prefs.edit().putString("embedding", json.toString()).putBoolean("enrolled", true).apply()
    }

    fun isEnrolled(): Boolean = isAvailable && prefs.getBoolean("enrolled", false)

    fun matches(samples: FloatArray, threshold: Float = 0.55f): Boolean {
        val activeExtractor = extractor ?: return false
        val raw = prefs.getString("embedding", null) ?: return false
        val savedJson = JSONArray(raw)
        val saved = FloatArray(savedJson.length()) { savedJson.getDouble(it).toFloat() }
        val current = embedding(samples)
        if (saved.size != current.size || current.isEmpty()) return false
        var dot = 0f
        var a = 0f
        var b = 0f
        for (i in saved.indices) {
            dot += saved[i] * current[i]
            a += saved[i] * saved[i]
            b += current[i] * current[i]
        }
        val score = if (a > 0f && b > 0f) dot / (sqrt(a) * sqrt(b)) else 0f
        android.util.Log.i("NovaVoice", "Speaker similarity: $score")
        return score >= threshold
    }

    fun close() {
        extractor?.release()
    }
}
