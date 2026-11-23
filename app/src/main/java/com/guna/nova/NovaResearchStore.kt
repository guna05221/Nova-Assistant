package com.guna.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stores the latest grounded research report and its provider-supplied source URLs. */
object NovaResearchStore {
    private const val PREFS = "nova_research"
    private const val KEY = "latest"

    data class Source(val title: String, val url: String)
    data class Report(val query: String, val text: String, val sources: List<Source>, val savedAt: Long)

    fun save(context: Context, query: String, text: String, sources: List<Source>) {
        val json = JSONObject()
            .put("query", query)
            .put("text", text)
            .put("savedAt", System.currentTimeMillis())
            .put("sources", JSONArray().apply {
                sources.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) }
            })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    fun latest(context: Context): Report? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val array = json.optJSONArray("sources") ?: JSONArray()
            val sources = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url")
                if (!url.startsWith("https://")) null else Source(item.optString("title"), url)
            }
            Report(json.optString("query"), json.optString("text"), sources, json.optLong("savedAt"))
        }.getOrNull()
    }
}
