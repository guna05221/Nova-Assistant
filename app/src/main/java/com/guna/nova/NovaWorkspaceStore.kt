package com.guna.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistent workspace for Nova's instructions, reminders, completed work and payment notices.
 *
 * This store records information only. It never performs payments, sends messages, places calls,
 * or claims that an external action succeeded. Callers must supply verified outcomes.
 */
object NovaWorkspaceStore {
    private const val PREFS = "nova_workspace_v1"
    private const val KEY_INSTRUCTIONS = "instructions"
    private const val KEY_REMINDERS = "reminders"
    private const val KEY_PAYMENTS = "payments"

    enum class InstructionStatus { ACTIVE, WAITING, BLOCKED, COMPLETED }
    enum class ReminderStatus { UPCOMING, COMPLETED, CANCELLED }
    enum class PaymentStatus { UPCOMING, DUE, PAID, CANCELLED, UNKNOWN }

    data class Instruction(
        val id: String,
        val title: String,
        val originalRequest: String,
        val plan: List<String>,
        val progress: List<String>,
        val status: InstructionStatus,
        val result: String,
        val createdAt: Long,
        val updatedAt: Long,
        val completedAt: Long?
    )

    data class Reminder(
        val id: String,
        val title: String,
        val details: String,
        val eventAt: Long,
        val location: String,
        val source: String,
        val status: ReminderStatus,
        val linkedInstructionId: String?,
        val createdAt: Long
    )

    data class PaymentNotice(
        val id: String,
        val provider: String,
        val title: String,
        val amountMinor: Long?,
        val currency: String,
        val dueAt: Long?,
        val officialUrl: String?,
        val source: String,
        val status: PaymentStatus,
        val createdAt: Long
    )

    fun createInstruction(
        context: Context,
        title: String,
        originalRequest: String,
        plan: List<String> = emptyList()
    ): Instruction {
        val now = System.currentTimeMillis()
        val item = Instruction(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { originalRequest.trim().take(80) },
            originalRequest = originalRequest.trim(),
            plan = plan.map { it.trim() }.filter { it.isNotBlank() },
            progress = emptyList(),
            status = InstructionStatus.ACTIVE,
            result = "",
            createdAt = now,
            updatedAt = now,
            completedAt = null
        )
        synchronized(this) {
            val items = readInstructions(context).toMutableList()
            items.add(0, item)
            writeInstructions(context, items)
        }
        return item
    }

    fun addProgress(context: Context, id: String, note: String): Instruction? =
        updateInstruction(context, id) { old ->
            old.copy(progress = old.progress + note.trim().take(500), updatedAt = System.currentTimeMillis())
        }

    fun setInstructionStatus(
        context: Context,
        id: String,
        status: InstructionStatus,
        result: String? = null,
        verified: Boolean = false
    ): Instruction? {
        // Completion is allowed only when the caller supplies verified=true.
        if (status == InstructionStatus.COMPLETED && !verified) return null
        return updateInstruction(context, id) { old ->
            val now = System.currentTimeMillis()
            old.copy(
                status = status,
                result = result ?: old.result,
                updatedAt = now,
                completedAt = if (status == InstructionStatus.COMPLETED) now else null
            )
        }
    }

    fun getInstructions(context: Context): List<Instruction> =
        synchronized(this) { readInstructions(context) }

    fun getActiveInstructions(context: Context): List<Instruction> =
        getInstructions(context).filter { it.status != InstructionStatus.COMPLETED }

    fun getCompletedInstructions(context: Context): List<Instruction> =
        getInstructions(context).filter { it.status == InstructionStatus.COMPLETED }

    fun createReminder(
        context: Context,
        title: String,
        details: String,
        eventAt: Long,
        location: String = "",
        source: String = "User instruction",
        linkedInstructionId: String? = null
    ): Reminder {
        require(eventAt > 0L) { "A valid event time is required." }
        val item = Reminder(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            details = details.trim(),
            eventAt = eventAt,
            location = location.trim(),
            source = source.trim(),
            status = ReminderStatus.UPCOMING,
            linkedInstructionId = linkedInstructionId,
            createdAt = System.currentTimeMillis()
        )
        synchronized(this) {
            val items = readReminders(context).toMutableList()
            items.add(0, item)
            writeReminders(context, items)
        }
        return item
    }

    fun updateReminderStatus(context: Context, id: String, status: ReminderStatus): Reminder? {
        synchronized(this) {
            val items = readReminders(context).toMutableList()
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return null
            val updated = items[index].copy(status = status)
            items[index] = updated
            writeReminders(context, items)
            return updated
        }
    }

    fun getReminders(context: Context): List<Reminder> =
        synchronized(this) { readReminders(context).sortedBy { it.eventAt } }

    fun savePaymentNotice(
        context: Context,
        provider: String,
        title: String,
        amountMinor: Long?,
        currency: String = "INR",
        dueAt: Long? = null,
        officialUrl: String? = null,
        source: String
    ): PaymentNotice {
        val safeUrl = officialUrl?.trim()?.takeIf {
            it.startsWith("https://", ignoreCase = true) &&
                !it.contains("@") && it.length <= 2048
        }
        val item = PaymentNotice(
            id = UUID.randomUUID().toString(),
            provider = provider.trim(),
            title = title.trim(),
            amountMinor = amountMinor?.takeIf { it >= 0L },
            currency = currency.trim().uppercase().take(3),
            dueAt = dueAt?.takeIf { it > 0L },
            officialUrl = safeUrl,
            source = source.trim(),
            status = if (dueAt != null && dueAt <= System.currentTimeMillis()) PaymentStatus.DUE else PaymentStatus.UPCOMING,
            createdAt = System.currentTimeMillis()
        )
        synchronized(this) {
            val items = readPayments(context).toMutableList()
            items.add(0, item)
            writePayments(context, items)
        }
        return item
    }

    fun updatePaymentStatus(context: Context, id: String, status: PaymentStatus): PaymentNotice? {
        // This only records a user- or evidence-confirmed status; it never initiates payment.
        synchronized(this) {
            val items = readPayments(context).toMutableList()
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return null
            val updated = items[index].copy(status = status)
            items[index] = updated
            writePayments(context, items)
            return updated
        }
    }

    fun getPaymentNotices(context: Context): List<PaymentNotice> =
        synchronized(this) { readPayments(context).sortedBy { it.dueAt ?: Long.MAX_VALUE } }

    private fun updateInstruction(
        context: Context,
        id: String,
        transform: (Instruction) -> Instruction
    ): Instruction? {
        synchronized(this) {
            val items = readInstructions(context).toMutableList()
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return null
            val updated = transform(items[index])
            items[index] = updated
            writeInstructions(context, items)
            return updated
        }
    }

    private fun readInstructions(context: Context): List<Instruction> =
        readArray(context, KEY_INSTRUCTIONS).mapNotNull { o ->
            runCatching {
                Instruction(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    originalRequest = o.optString("originalRequest"),
                    plan = o.getJSONArray("plan").toStringList(),
                    progress = o.getJSONArray("progress").toStringList(),
                    status = InstructionStatus.valueOf(o.optString("status", "ACTIVE")),
                    result = o.optString("result"),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                    completedAt = o.optLong("completedAt").takeIf { it > 0L }
                )
            }.getOrNull()
        }

    private fun readReminders(context: Context): List<Reminder> =
        readArray(context, KEY_REMINDERS).mapNotNull { o ->
            runCatching {
                Reminder(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    details = o.optString("details"),
                    eventAt = o.optLong("eventAt"),
                    location = o.optString("location"),
                    source = o.optString("source"),
                    status = ReminderStatus.valueOf(o.optString("status", "UPCOMING")),
                    linkedInstructionId = o.optString("linkedInstructionId").takeIf { it.isNotBlank() },
                    createdAt = o.optLong("createdAt")
                )
            }.getOrNull()
        }

    private fun readPayments(context: Context): List<PaymentNotice> =
        readArray(context, KEY_PAYMENTS).mapNotNull { o ->
            runCatching {
                PaymentNotice(
                    id = o.getString("id"),
                    provider = o.optString("provider"),
                    title = o.optString("title"),
                    amountMinor = if (o.isNull("amountMinor")) null else o.optLong("amountMinor"),
                    currency = o.optString("currency", "INR"),
                    dueAt = if (o.isNull("dueAt")) null else o.optLong("dueAt"),
                    officialUrl = o.optString("officialUrl").takeIf { it.startsWith("https://") },
                    source = o.optString("source"),
                    status = PaymentStatus.valueOf(o.optString("status", "UNKNOWN")),
                    createdAt = o.optLong("createdAt")
                )
            }.getOrNull()
        }

    private fun readArray(context: Context, key: String): List<JSONObject> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun writeInstructions(context: Context, items: List<Instruction>) =
        writeArray(context, KEY_INSTRUCTIONS, JSONArray().apply {
            items.forEach { item ->
                put(JSONObject()
                    .put("id", item.id).put("title", item.title)
                    .put("originalRequest", item.originalRequest)
                    .put("plan", item.plan.toJsonArray()).put("progress", item.progress.toJsonArray())
                    .put("status", item.status.name).put("result", item.result)
                    .put("createdAt", item.createdAt).put("updatedAt", item.updatedAt)
                    .put("completedAt", item.completedAt ?: JSONObject.NULL))
            }
        })

    private fun writeReminders(context: Context, items: List<Reminder>) =
        writeArray(context, KEY_REMINDERS, JSONArray().apply {
            items.forEach { item ->
                put(JSONObject()
                    .put("id", item.id).put("title", item.title).put("details", item.details)
                    .put("eventAt", item.eventAt).put("location", item.location).put("source", item.source)
                    .put("status", item.status.name)
                    .put("linkedInstructionId", item.linkedInstructionId ?: JSONObject.NULL)
                    .put("createdAt", item.createdAt))
            }
        })

    private fun writePayments(context: Context, items: List<PaymentNotice>) =
        writeArray(context, KEY_PAYMENTS, JSONArray().apply {
            items.forEach { item ->
                put(JSONObject()
                    .put("id", item.id).put("provider", item.provider).put("title", item.title)
                    .put("amountMinor", item.amountMinor ?: JSONObject.NULL).put("currency", item.currency)
                    .put("dueAt", item.dueAt ?: JSONObject.NULL)
                    .put("officialUrl", item.officialUrl ?: JSONObject.NULL)
                    .put("source", item.source).put("status", item.status.name)
                    .put("createdAt", item.createdAt))
            }
        })

    private fun writeArray(context: Context, key: String, value: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value.toString()).apply()
    }

    private fun List<String>.toJsonArray() = JSONArray().apply { this@toJsonArray.forEach { put(it) } }
    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}
