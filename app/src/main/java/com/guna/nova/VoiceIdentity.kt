package com.guna.nova

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import org.json.JSONArray
import kotlin.math.sqrt

class VoiceIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("nova_voice_identity", Context.MODE_PRIVATE)
    private val extractor = SpeakerEmbeddingExtractor(
        assetManager = context.assets,
        config = SpeakerEmbeddingExtractorConfig(
            model = "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx",
            numThreads = 2,
            debug = false,
            provider = "cpu"
        )
    )

    fun embedding(samples: FloatArray): FloatArray {
        val stream: OnlineStream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, 16000)
            stream.inputFinished()
            extractor.compute(stream)
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

    fun isEnrolled(): Boolean = prefs.getBoolean("enrolled", false)

    fun matches(samples: FloatArray, threshold: Float = 0.55f): Boolean {
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

    fun close() = extractor.release()
}
