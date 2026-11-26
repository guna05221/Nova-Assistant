package com.guna.nova

import android.content.Context
import android.content.pm.PackageManager
import java.util.Locale

/**
 * Free, on-device speech post-processing.
 *
 * This does not pretend to retrain the recognizer for an accent. It repairs a small,
 * conservative set of common ASR formatting/app-name errors and resolves app names
 * against labels actually installed on this phone.
 */
object SpeechUnderstanding {
    data class Result(
        val transcript: String,
        val confidence: Float?,
        val corrections: List<String>
    )

    fun process(context: Context, raw: String, confidence: Float? = null): Result {
        var value = raw.trim().replace(Regex("\\s+"), " ")
        val corrections = mutableListOf<String>()
        val before = value

        // Common ASR segmentation and filler errors around well-known app names.
        value = value.replace(Regex("(?i)\\byou\\s+tube\\s+(?:up|app)\\b"), "YouTube")
            .replace(Regex("(?i)\\byou\\s+tube\\b"), "YouTube")
            .replace(Regex("(?i)\\bwhats\\s+app\\b"), "WhatsApp")
            .replace(Regex("(?i)\\bwhat\\s+sapp\\b"), "WhatsApp")
            .replace(Regex("(?i)\\bface\\s+book\\b"), "Facebook")
            .replace(Regex("(?i)\\bplay\\s+store\\b"), "Play Store")
        if (value != before) corrections.add("common app-name transcription")

        value = canonicalizeRequestedApp(context, value, corrections)
        return Result(value, confidence, corrections)
    }

    private fun canonicalizeRequestedApp(
        context: Context,
        utterance: String,
        corrections: MutableList<String>
    ): String {
        val command = Regex("(?i)^\\s*(please\\s+)?(can you\\s+)?(open|launch|start)\\s+(.+?)\\s*$")
            .matchEntire(utterance) ?: return utterance
        val target = command.groupValues[4].trim().trimEnd('.', '!', '?', ',')
        if (target.isBlank()) return utterance

        val aliases = mapOf(
            "youtube" to setOf("youtube", "you tube", "you tub", "u tube"),
            "whatsapp" to setOf("whatsapp", "what's app", "whats app", "what sap"),
            "instagram" to setOf("instagram", "insta gram", "insta"),
            "google maps" to setOf("google maps", "maps", "google map"),
            "chrome" to setOf("chrome", "crome"),
            "play store" to setOf("play store", "google play store")
        )
        val normalizedTarget = normalize(target)
        val alias = aliases.entries.firstOrNull { (_, values) ->
            values.any { normalize(it) == normalizedTarget }
        }?.key
        if (alias != null) {
            val installed = findInstalledLabel(context, alias)
            if (installed != null && !installed.equals(target, ignoreCase = true)) {
                corrections.add("app alias resolved")
                return utterance.replaceRange(command.groups[4]!!.range, installed)
            }
        }

        val installed = findFuzzyInstalledLabel(context, target)
        if (installed != null && !installed.equals(target, ignoreCase = true)) {
            corrections.add("installed app name matched")
            return utterance.replaceRange(command.groups[4]!!.range, installed)
        }
        return utterance
    }

    private fun findInstalledLabel(context: Context, query: String): String? {
        val pm = context.packageManager
        return try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .map { pm.getApplicationLabel(it).toString() }
                .firstOrNull { normalize(it) == normalize(query) }
        } catch (_: Exception) { null }
    }

    private fun findFuzzyInstalledLabel(context: Context, target: String): String? {
        val pm = context.packageManager
        val q = normalize(target)
        if (q.length < 4) return null
        return try {
            val labels = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .map { pm.getApplicationLabel(it).toString() }
                .distinct()
                .map { it to similarity(q, normalize(it)) }
                .filter { (_, score) -> score >= 0.78 }
                .sortedByDescending { it.second }
                .toList()
            // Only auto-correct a clear winner. Ambiguous app names are left untouched.
            if (labels.isEmpty()) null
            else if (labels.size > 1 && labels[0].second - labels[1].second < 0.08) null
            else labels.first().first
        } catch (_: Exception) { null }
    }

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        val longer = maxOf(a.length, b.length)
        if (longer == 0) return 1f
        return (longer - levenshtein(a, b)).toFloat() / longer
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
}
