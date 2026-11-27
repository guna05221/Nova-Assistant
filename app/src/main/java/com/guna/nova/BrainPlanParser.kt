package com.guna.nova

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Strict parser for bounded, dependency-ordered model plans. */
object BrainPlanParser {
    private val rootFields = setOf("steps")
    private val stepFields = setOf("id", "action", "target", "reply", "depends_on")

    fun parse(raw: String, source: ProposalSource): BrainPlan? {
        val input = raw.trim()
        if (input.isEmpty() || input.length > 8192 || !input.startsWith("{") || !input.endsWith("}")) return null
        return try {
            val root = JSONObject(input)
            if (root.keys().asSequence().toSet() != rootFields) return null
            val stepsJson = root.opt("steps") as? JSONArray ?: return null
            if (stepsJson.length() !in 1..8) return null
            val steps = ArrayList<PlanStep>(stepsJson.length())
            for (index in 0 until stepsJson.length()) {
                val item = stepsJson.optJSONObject(index) ?: return null
                if (item.keys().asSequence().toSet() != stepFields) return null
                val id = item.opt("id") as? String ?: return null
                if (id.isBlank() || id.length > 64) return null
                val actionName = item.opt("action") as? String ?: return null
                val action = try {
                    NovaAction.valueOf(actionName.trim().uppercase(Locale.ROOT))
                } catch (_: IllegalArgumentException) { return null }
                val targetText = item.opt("target") as? String ?: return null
                val replyText = item.opt("reply") as? String ?: return null
                if (targetText.length > 500 || replyText.length > 1200) return null
                val dependencies = item.opt("depends_on") as? JSONArray ?: return null
                val dependencyIds = LinkedHashSet<String>()
                for (dependencyIndex in 0 until dependencies.length()) {
                    val dependency = dependencies.opt(dependencyIndex) as? String ?: return null
                    if (dependency.isBlank() || !dependencyIds.add(dependency)) return null
                }
                val target = targetText.trim().takeIf { it.isNotEmpty() }
                val reply = replyText.trim().takeIf { it.isNotEmpty() }
                if (action == NovaAction.CONVERSATION && target != null) return null
                if (action != NovaAction.CONVERSATION && reply != null) return null
                steps += PlanStep(id, IntentProposal(action, target, reply, source), dependencyIds)
            }
            BrainPlan(steps)
        } catch (_: Exception) { null }
    }
}
