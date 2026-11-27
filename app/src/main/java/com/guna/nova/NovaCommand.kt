package com.guna.nova

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import java.security.MessageDigest
import java.util.UUID

/**
 * Local-first intent router. Unknown speech is never silently treated as a web search.
 * A short conversation memory is stored locally to resolve simple follow-ups.
 */
object NovaCommand {
    private const val PREFS = "nova_brain"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestSequence = AtomicLong(0L)

    // Pending approvals are process-memory only: never persisted, replayed or restored.
    private data class PendingSend(
        val id: String,
        val packageName: String,
        val recipient: String,
        val expectedMessage: String,
        val digest: String,
        val expiresAtElapsed: Long
    )
    @Volatile private var pendingSend: PendingSend? = null
    private val pendingSendLock = Any()
    private const val SEND_CONFIRMATION_WINDOW_MS = 20_000L

    fun execute(context: Context, raw: String) {
        val appContext = context.applicationContext
        val original = raw.trim()
        val command = normalize(original)
        if (command.isBlank()) return

        val requestId = requestSequence.incrementAndGet()

        // A financial request always cancels any outstanding message approval first.
        if (FinancialActionPolicy.mustDeny(requestOrArguments = command)) {
            invalidatePendingSend(appContext, "Financial requests cannot be approved.")
            say(appContext, "I can explain financial topics, but Nova permanently blocks payments, transfers, purchases, paid orders, recharges and subscriptions.")
            return
        }

        val pending = synchronized(pendingSendLock) { pendingSend }
        if (pending != null) {
            when {
                isCancellation(command) -> {
                    resolvePendingSend(appContext, pending.id, false)
                    return
                }
                isConfirmation(command) -> {
                    // Spoken "yes" is not a substitute for the visible one-time Allow control.
                    say(appContext, "For safety, tap Allow in Nova's floating approval card. Nothing has been sent yet.")
                    return
                }
                else -> invalidatePendingSend(appContext, "A new command cancelled the pending approval.")
            }
        }

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val directIntent = understand(command, prefs.getString("last_app", null))

        // Typing never sends. A SEND request only creates a visible approval card.
        if (directIntent.kind == "TYPE_TEXT") {
            executeIntent(appContext, directIntent)
            return
        }
        if (directIntent.kind == "SEND") {
            requestMessageApproval(appContext)
            return
        }
        if (isCapabilityQuestion(command)) {
            say(appContext, capabilityReply(appContext))
            return
        }

        // Deterministic commands take precedence over AI interpretation.
        if (directIntent.kind != "UNKNOWN" && directIntent.kind != "CONVERSATION") {
            executeIntent(appContext, directIntent)
            return
        }
        if (directIntent.kind == "CONVERSATION") {
            say(appContext, directIntent.value.orEmpty())
            return
        }

        // Keep the microphone responsive while AI thinks, but discard stale model results
        // if a newer user utterance arrives before the previous request completes.
        statusOnly(appContext, "Thinking…")
        scope.launch {
            processUnknownUtterance(appContext, original, requestId)
        }
    }

    private suspend fun processUnknownUtterance(context: Context, utterance: String, requestId: Long) {
        val history = conversationHistory(context, utterance)
        val allTurns = conversationTurns(history)
        val conversation = ConversationContextEngine.analyze(allTurns.dropLast(1), utterance)
        if (conversation.requiresClarification) {
            val clarification = conversation.clarification ?: "Which earlier topic do you mean?"
            saveConversation(context, utterance, clarification)
            withContext(Dispatchers.Main) {
                if (requestSequence.get() == requestId) say(context, clarification)
            }
            return
        }
        val cloudHistory = cloudHistory(conversation.cloudTurns)
        val localAvailable = LocalAiManager.isDownloaded(context)
        val cloudAvailable = AiRoutingPolicy.cloudPermitted(
            consentEnabled = AIProviderManager.isCloudEnabled(context),
            providerConfigured = AIProviderManager.hasGemini(context) || AIProviderManager.hasGroq(context)
        )

        val plannerContext = conversation.localPrompt
        val cloudPlannerContext = conversation.cloudTurns.joinToString("\n") {
            it.role + ": " + it.content
        }.takeLast(1800)
        val plannerPrompt = """
            You are Nova, a safe Android assistant and structured planning engine.
            Understand English, Tamil and Tanglish. Resolve follow-ups from the supplied context,
            but plan only for the current request. Return exactly this JSON schema:
            {"steps":[{"id":"step-1","action":"ACTION","target":"","reply":"","depends_on":[]}]}
            Return one step for one action or up to three steps for a genuinely multi-step request.
            Dependencies must reference earlier step IDs only. Use only these actions:
            OPEN_APP, SEARCH, WEB_SEARCH, HOME, BACK, SETTINGS, SCROLL_UP, SCROLL_DOWN,
            FOOD_SEARCH, SEARCH_SWIGGY, SEARCH_ZOMATO, CONVERSATION, UNSUPPORTED.
            CONVERSATION is for ordinary questions, explanations, calculations, jokes and casual chat.
            Never propose sending/typing messages, payments, transfers, purchases, subscriptions,
            recharges, orders, deletion, publishing, calls, security changes or unsupported operations.
            Food discovery and Swiggy/Zomato search are search-only, never ordering.
            Require a target for app/search actions. Use empty strings for unused target/reply fields.
            Never claim an action has happened or invent live results. If essential information is
            missing, use one CONVERSATION step to ask a concise question. Return JSON only.
        """.trimIndent()

        // Always ask the on-device Qwen model first. A saved key never changes this order.
        var plan: BrainPlan? = null
        if (localAvailable) {
            val localOutput = LocalAiManager.interpret(context, plannerContext)
            if (!localOutput.isNullOrBlank()) {
                plan = BrainPlanParser.parse(localOutput, ProposalSource.LOCAL_MODEL)
                    ?: BrainContractParser.parseIntent(localOutput, ProposalSource.LOCAL_MODEL)?.let { BrainPlan.single(it) }
            }
        }

        // Cloud planning is a fallback only after local inference is unavailable or unusable,
        // and only when the separate explicit-consent gate and a configured provider both pass.
        if (plan == null && AiRoutingPolicy.shouldAttemptCloudFallback(
                localSucceeded = false,
                consentEnabled = AIProviderManager.isCloudEnabled(context),
                providerConfigured = AIProviderManager.hasGemini(context) || AIProviderManager.hasGroq(context)
            )
        ) {
            if (requestSequence.get() != requestId) return
            val planned = AIProviderManager.plan(context, plannerPrompt, cloudPlannerContext)
            if (planned.ok) {
                plan = BrainPlanParser.parse(planned.message, ProposalSource.CLOUD_MODEL)
                    ?: BrainContractParser.parseIntent(planned.message, ProposalSource.CLOUD_MODEL)?.let { BrainPlan.single(it) }
            }
        }

        if (requestSequence.get() != requestId) return

        val decisions = plan?.let { BrainPolicy.evaluatePlan(it, utterance) }.orEmpty()
        val denied = decisions.firstOrNull { it.outcome == PolicyOutcome.DENY }
        if (denied != null) {
            withContext(Dispatchers.Main) {
                if (requestSequence.get() == requestId) {
                    val message = if (denied.reasonCode == "PERMANENT_FINANCIAL_DENY")
                        "I can explain financial topics, but Nova permanently blocks financial transactions and paid orders."
                    else "That proposed action is not permitted."
                    say(context, message)
                }
            }
            return
        }

        val clarificationDecision = decisions.firstOrNull { it.outcome == PolicyOutcome.CLARIFY }
        if (clarificationDecision != null) {
            val clarification = if (clarificationDecision.reasonCode == "MISSING_ACTION_TARGET")
                "What should I use as the search or app target?"
            else "I couldn't safely interpret one part of that request. Could you clarify what you want?"
            saveConversation(context, utterance, clarification)
            withContext(Dispatchers.Main) {
                if (requestSequence.get() == requestId) say(context, clarification)
            }
            return
        }

        // All model-proposed Android actions use the trusted sequential executor.
        // Conversation-only plans remain responses and never reach Android APIs.
        val executablePlan = plan
        if (executablePlan != null && executablePlan.steps.any { it.proposal.action != NovaAction.CONVERSATION }) {
            if (requestSequence.get() != requestId) return
            val report = withContext(Dispatchers.IO) {
                executeModelPlan(context, executablePlan, utterance)
            }
            withContext(Dispatchers.Main) {
                if (requestSequence.get() == requestId) say(context, report.summary)
            }
            return
        }

        val step = plan?.steps?.singleOrNull()
        val decision = decisions.singleOrNull()
        val proposal = step?.proposal

        val hasPlannerReply = proposal?.action == NovaAction.CONVERSATION && !proposal.reply.isNullOrBlank()
        val localAnswer = if (!hasPlannerReply && localAvailable) {
            LocalAiManager.chat(context, conversation.localPrompt)
        } else null
        val answer = when {
            proposal?.action == NovaAction.CONVERSATION && !proposal.reply.isNullOrBlank() -> proposal.reply
            !localAnswer.isNullOrBlank() -> localAnswer
            AiRoutingPolicy.shouldAttemptCloudFallback(
                localSucceeded = false,
                consentEnabled = AIProviderManager.isCloudEnabled(context),
                providerConfigured = AIProviderManager.hasGemini(context) || AIProviderManager.hasGroq(context)
            ) -> {
                if (requestSequence.get() != requestId) return
                val result = AIProviderManager.chat(context, cloudHistory)
                result.message.takeIf { result.ok && it.isNotBlank() }
            }
            else -> null
        }

        if (requestSequence.get() != requestId) return

        val response = answer?.trim()?.takeIf { it.isNotBlank() } ?: when {
            cloudAvailable -> "I couldn't reach the AI service just now. Please try again."
            localAvailable -> "I couldn't generate a response with the local model. Please try again."
            else -> if (AIProviderManager.isCloudEnabled(context)) "Natural conversation isn't configured yet. Connect a cloud provider or install the local Qwen model."
                else "Cloud AI is disabled. Enable it in Nova settings or install the local Qwen model for natural conversation."
        }

        saveConversation(context, utterance, response)
        withContext(Dispatchers.Main) {
            if (requestSequence.get() == requestId) say(context, response)
        }
    }

    private fun conversationTurns(history: org.json.JSONArray): List<ConversationTurn> =
        (0 until history.length()).mapNotNull { index ->
            val item = history.optJSONObject(index) ?: return@mapNotNull null
            val role = item.optString("role")
            val content = item.optString("content").trim()
            if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                ConversationTurn(role, content.take(1600))
            } else null
        }

    private fun cloudHistory(turns: List<ConversationTurn>): org.json.JSONArray =
        org.json.JSONArray().also { result ->
            turns.takeLast(4).forEach { turn ->
                result.put(org.json.JSONObject().put("role", turn.role).put("content", turn.content.take(500)))
            }
        }

    /**
     * Trusted Android-side plan boundary. The engine preflights every step, then
     * re-checks financial policy immediately before each side effect.
     */
    private fun executeModelPlan(context: Context, plan: BrainPlan, originalRequest: String): PlanExecutionReport {
        val report = BrainExecutionEngine.execute(
            plan = plan,
            originalRequest = originalRequest,
            dispatcher = { step -> dispatchVerifiedModelStep(context, step, originalRequest) },
            onProgress = { progress ->
                val serialized = org.json.JSONArray().apply {
                    progress.steps.forEach { item ->
                        put(org.json.JSONObject().put("step_id", item.stepId)
                            .put("status", item.state.name).put("reason", item.reasonCode))
                    }
                }
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("last_plan_execution_id", progress.executionId)
                    .putString("last_plan_progress", serialized.toString())
                    .apply()
                val completed = progress.steps.count { it.state == PlanStepState.COMPLETED }
                val runningIndex = progress.steps.indexOfFirst { it.state == PlanStepState.RUNNING }
                val status = if (runningIndex >= 0) "Step ${runningIndex + 1}/${progress.steps.size}: running"
                    else "Plan progress: $completed/${progress.steps.size} completed"
                statusOnly(context, status)
                NovaOverlayService.status(context, status)
            }
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_plan_state", if (report.success) "COMPLETED" else "STOPPED")
            .putString("last_plan_summary", report.summary)
            .apply()
        return report
    }

    /**
     * Dispatches through trusted Android APIs, then waits for Accessibility evidence.
     * API acceptance alone is never reported as a completed plan step.
     */
    private fun dispatchVerifiedModelStep(context: Context, step: PlanStep, originalRequest: String): StepVerification {
        val proposal = step.proposal
        if (FinancialActionPolicy.mustDeny(
                capability = proposal.action.name,
                requestOrArguments = listOfNotNull(originalRequest, proposal.target, proposal.reply).joinToString(" ")
            )
        ) return StepVerification.failed("PERMANENT_FINANCIAL_DENY", "Financial actions are permanently blocked.")

        val accessibility = NovaAccessibilityService.instance
            ?: return StepVerification.failed("ACCESSIBILITY_UNAVAILABLE", "Enable Nova Accessibility; the action was not attempted.")
        val baseline = accessibility.captureObservation()
        val target = proposal.target.orEmpty()
        var expectedPackage: String? = null

        val accepted = try {
            when (proposal.action) {
                NovaAction.OPEN_APP -> {
                    expectedPackage = resolveAppPackage(context, target)
                    expectedPackage != null && openApp(context, target)
                }
                NovaAction.SEARCH, NovaAction.WEB_SEARCH -> target.isNotBlank() && search(context, target)
                NovaAction.HOME -> accessibility.home()
                NovaAction.BACK -> accessibility.back()
                NovaAction.SETTINGS -> {
                    expectedPackage = "com.android.settings"
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }
                NovaAction.SCROLL_UP -> accessibility.scroll(true)
                NovaAction.SCROLL_DOWN -> accessibility.scroll(false)
                NovaAction.FOOD_SEARCH -> FoodDiscovery.findNearby(context, target.ifBlank { "restaurants and food" })
                NovaAction.SEARCH_SWIGGY -> FoodDiscovery.searchDeliveryApp(context, "Swiggy", target)
                NovaAction.SEARCH_ZOMATO -> FoodDiscovery.searchDeliveryApp(context, "Zomato", target)
                else -> false
            }
        } catch (_: Exception) { false }

        if (!accepted) {
            return StepVerification.failed("ANDROID_DISPATCH_REJECTED", "Android did not accept the operation; dependent steps were stopped.")
        }

        val verification = accessibility.awaitActionVerification(
            action = proposal.action,
            target = expectedPackage ?: target,
            baseline = baseline,
            timeoutMs = PostActionVerifier.timeoutMs(proposal.action)
        )
        if (!verification.verified) return verification

        remember(context, proposal.action.name, proposal.target)
        return verification
    }

    private fun resolveAppPackage(context: Context, label: String): String? {
        if (label.isBlank()) return null
        val apps = context.packageManager.getInstalledApplications(0)
        val query = label.lowercase(Locale.ROOT).trim()
        val match = apps.firstOrNull {
            context.packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT) == query
        } ?: apps.firstOrNull {
            context.packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT).contains(query)
        }
        return match?.packageName?.takeIf { context.packageManager.getLaunchIntentForPackage(it) != null }
    }

    private fun conversationHistory(context: Context, latest: String): org.json.JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("conversation_history", null)
        val history = try { if (stored.isNullOrBlank()) org.json.JSONArray() else org.json.JSONArray(stored) }
            catch (_: Exception) { org.json.JSONArray() }
        history.put(org.json.JSONObject().put("role", "user").put("content", latest.take(1600)))
        while (history.length() > 12) history.remove(0)
        return history
    }

    private fun saveConversation(context: Context, user: String, answer: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("conversation_history", null)
        val history = try { if (stored.isNullOrBlank()) org.json.JSONArray() else org.json.JSONArray(stored) }
            catch (_: Exception) { org.json.JSONArray() }
        history.put(org.json.JSONObject().put("role", "user").put("content", user.take(1600)))
        history.put(org.json.JSONObject().put("role", "assistant").put("content", answer.take(2400)))
        while (history.length() > 12) history.remove(0)
        prefs.edit().putString("conversation_history", history.toString()).apply()
    }

    private fun isCapabilityQuestion(text: String): Boolean = containsAny(
        text,
        "what can you do", "what all can you do", "what are your features", "your capabilities",
        "what can nova do", "tell me your features", "how can you help me", "what do you support",
        "நீ என்ன செய்ய முடியும்", "உன்னால என்ன செய்ய முடியும்", "என்னென்ன செய்ய முடியும்",
        "நீ என்ன பண்ணுவ", "enna ellam panna mudiyum", "nee enna panna mudiyum",
        "un features enna", "nova enna pannum"
    )

    private fun capabilityReply(context: Context): String {
        val cloudReady = AIProviderManager.hasEnabledCloudProvider(context)
        val localReady = LocalAiManager.isDownloaded(context)
        val aiMode = when {
            cloudReady && localReady -> "AI reasoning: cloud AI is explicitly enabled, with your downloaded Qwen model available locally."
            cloudReady -> "AI reasoning: your explicitly enabled cloud AI connection."
            localReady -> "AI reasoning: your downloaded Qwen model, which can work offline."
            else -> "AI reasoning: cloud AI is disabled or not configured. Download local Qwen for offline reasoning, or enable cloud AI explicitly."
        }
        return """
            I'm Nova. Here's what I can do right now:
            • Understand requests in English, Tamil and Tanglish.
            • Open installed apps and Android Settings; go Home or Back; scroll the screen.
            • Search Google and open nearby food results in Maps, Swiggy or Zomato.
            • Copy visible text and paste clipboard text when Nova Accessibility is enabled.
            • Have AI-assisted conversations and plan supported actions. $aiMode

            Important limits: I can type into a focused text field and tap a clearly labelled Send button only when you explicitly tell me to send. I don't make phone calls, read private chats or notifications, place food orders, book services, make purchases or payments, transfer money, delete files, or change security settings. Search handoffs open another app; I don't yet read and compare its live results automatically.
            """.trimIndent()
    }

    private fun executeIntent(context: Context, intent: BrainIntent) {
        // Last line of defence: no caller (voice, model, UI or future planner)
        // may bypass the permanent financial deny policy.
        if (FinancialActionPolicy.mustDeny(
                capability = intent.kind,
                requestOrArguments = intent.value
            )
        ) {
            say(context, "I can explain financial topics, but Nova permanently blocks financial transactions and paid orders.")
            return
        }

        when (intent.kind) {
            "COPY_LAST_MESSAGE" -> {
                val service = NovaAccessibilityService.instance
                val copied = service?.copyLastMessage() == true
                val message = when {
                    service == null -> "Copy failed: enable Nova in Android Accessibility settings, then try again."
                    copied -> "Copied the latest visible text. Tap the destination text box and say: paste it."
                    else -> "Copy failed: no readable message text was found. Keep the chat open and try again."
                }
                say(context, message)
            }
            "PASTE" -> {
                val pasted = NovaAccessibilityService.instance?.pasteClipboard() == true
                say(context, if (pasted) "Pasted." else "I couldn't paste. Tap the text box first, then say paste it.")
            }
            "TYPE_TEXT" -> {
                val typed = NovaAccessibilityService.instance?.typeIntoFocusedField(intent.value.orEmpty()) == true
                say(context, if (typed) "Text entered. Say send it when you want me to send it." else "I couldn't find a focused text box. Open the app and tap its message field first.")
            }
            "SEND" -> {
                // Sending is intentionally impossible through this generic executor path.
                // Only resolvePendingSend() can invoke the snapshot-checked send operation.
                say(context, "To send, review the exact recipient and message in Nova's floating card and tap Allow.")
            }
            "HOME" -> {
                val accessibility = NovaAccessibilityService.instance
                if (accessibility != null) accessibility.home() else {
                    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(home)
                }
                remember(context, "HOME", null)
            }
            "BACK" -> {
                NovaAccessibilityService.instance?.back()
                remember(context, "BACK", null)
            }
            "SETTINGS" -> {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                remember(context, "SETTINGS", null)
            }
            "SCROLL_DOWN" -> NovaAccessibilityService.instance?.scroll(false)
            "SCROLL_UP" -> NovaAccessibilityService.instance?.scroll(true)
            "OPEN_APP" -> {
                val app = intent.value.orEmpty()
                if (app.isBlank() || !openApp(context, app)) say(context, "I couldn't find ${app.ifBlank { "that app" }} on this phone.")
                else remember(context, "OPEN_APP", app)
            }
            "SEARCH", "WEB_SEARCH" -> {
                val query = intent.value.orEmpty()
                if (query.isBlank()) say(context, "What should I search for?")
                else { search(context, query); remember(context, intent.kind, query) }
            }
            "FOOD_SEARCH" -> {
                val preference = intent.value?.takeIf { it.isNotBlank() } ?: "restaurants and food"
                val opened = FoodDiscovery.findNearby(context, preference)
                say(context, if (opened) "Opening nearby food and restaurant results. You can also ask me to search Swiggy or Zomato." else "I couldn't open nearby food search.")
                remember(context, "FOOD_SEARCH", preference)
            }
            "SEARCH_SWIGGY", "SEARCH_ZOMATO" -> {
                val app = if (intent.kind == "SEARCH_SWIGGY") "Swiggy" else "Zomato"
                val query = intent.value?.takeIf { it.isNotBlank() } ?: "food near me"
                val opened = FoodDiscovery.searchDeliveryApp(context, app, query)
                say(context, if (opened) "Opening $app food search." else "I couldn't open $app.")
                remember(context, intent.kind, query)
            }
            "CONVERSATION" -> say(context, intent.value ?: "I'm listening. Tell me what you'd like me to do.")
            else -> say(context, "I couldn't understand that request. Please say it another way.")
        }
    }

    private fun isConfirmation(text: String): Boolean = text in setOf(
        "yes", "yes send", "confirm", "confirm send", "send now",
        "ஆம்", "ஆமாம்", "சரி", "அனுப்பு"
    )

    private fun isCancellation(text: String): Boolean = text in setOf(
        "no", "cancel", "cancel it", "stop", "don't send", "do not send",
        "வேண்டாம்", "நிறுத்து", "அனுப்ப வேண்டாம்"
    )

    private fun requestMessageApproval(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            say(context, "Message approval needs Nova's floating window permission. Open Nova, enable Display over other apps, then try again. Nothing was sent.")
            return
        }
        val accessibility = NovaAccessibilityService.instance
        if (accessibility == null) {
            say(context, "I can't verify the current app. Enable Nova Accessibility and try again. Nothing was sent.")
            return
        }
        val snapshot = accessibility.currentSendContext()
        if (snapshot == null) {
            say(context, "I couldn't verify one recipient and the exact message in the current app. Nothing was sent.")
            return
        }

        val id = UUID.randomUUID().toString()
        val digest = sendSnapshotDigest(snapshot)
        val expiresAt = SystemClock.elapsedRealtime() + SEND_CONFIRMATION_WINDOW_MS
        val pending = PendingSend(
            id = id,
            packageName = snapshot.packageName,
            recipient = snapshot.recipient,
            expectedMessage = snapshot.message,
            digest = digest,
            expiresAtElapsed = expiresAt
        )
        synchronized(pendingSendLock) { pendingSend = pending }

        val started = NovaOverlayService.requestApproval(
            context = context,
            approvalId = id,
            packageName = snapshot.packageName,
            recipient = snapshot.recipient,
            message = snapshot.message,
            expiresAtElapsed = expiresAt
        )
        if (!started) {
            synchronized(pendingSendLock) { if (pendingSend?.id == id) pendingSend = null }
            say(context, "I couldn't open Nova's floating approval card. Nothing was sent.")
            return
        }
        say(context, "Review the recipient and exact message in Nova's floating card. Tap Allow to send once, or Deny to cancel.")
    }

    /** SHA-256 binds approval to the exact foreground package, recipient label and message. */
    fun sendSnapshotDigest(snapshot: VerifiedSendContext): String {
        val canonical = snapshot.packageName + "\u0000" + snapshot.recipient + "\u0000" + snapshot.message
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun resolvePendingSend(context: Context, approvalId: String, allow: Boolean) {
        val approved = synchronized(pendingSendLock) {
            val current = pendingSend
            if (current?.id != approvalId) return
            pendingSend = null // consume atomically before any side effect
            current
        }
        NovaOverlayService.clearApproval(context, approvalId)
        if (!allow) {
            say(context, "Denied. Nothing was sent.")
            return
        }
        if (SystemClock.elapsedRealtime() >= approved.expiresAtElapsed) {
            say(context, "That approval expired. Nothing was sent.")
            return
        }
        val sent = NovaAccessibilityService.instance?.clickSendButton(
            expectedPackage = approved.packageName,
            expectedRecipient = approved.recipient,
            expectedMessage = approved.expectedMessage,
            expectedDigest = approved.digest
        ) == true
        say(
            context,
            if (sent) "I tapped Send for the approved message. Please check the app to verify delivery."
            else "I did not send it because the app, recipient or message could not be re-verified. Please review it and ask again."
        )
    }

    fun expirePendingSend(context: Context, approvalId: String) {
        val expired = synchronized(pendingSendLock) {
            if (pendingSend?.id != approvalId) return
            pendingSend = null
            true
        }
        if (expired) {
            NovaOverlayService.clearApproval(context, approvalId)
            say(context, "The message approval expired. Nothing was sent.")
        }
    }

    fun invalidatePendingSend(context: Context, reason: String = "Approval cancelled.") {
        val cancelled = synchronized(pendingSendLock) {
            val current = pendingSend ?: return
            pendingSend = null
            current
        }
        NovaOverlayService.clearApproval(context, cancelled.id)
        say(context, "$reason Nothing was sent.")
    }

    fun invalidatePendingSendForPackage(context: Context, changedPackage: String) {
        val current = synchronized(pendingSendLock) { pendingSend } ?: return
        if (current.packageName == changedPackage) {
            invalidatePendingSend(context, "The message changed, so I cancelled its approval.")
        }
    }

    fun invalidatePendingSendIfChanged(context: Context, current: VerifiedSendContext?) {
        val pending = synchronized(pendingSendLock) { pendingSend } ?: return
        val matches = current != null &&
            current.packageName == pending.packageName &&
            current.recipient == pending.recipient &&
            current.message == pending.expectedMessage &&
            sendSnapshotDigest(current) == pending.digest
        if (!matches) invalidatePendingSend(context, "The app, recipient or message changed, so I cancelled its approval.")
    }

    fun onApprovalSurfaceUnavailable(context: Context, approvalId: String) {
        val removed = synchronized(pendingSendLock) {
            if (pendingSend?.id != approvalId) return
            pendingSend = null
            true
        }
        if (removed) say(context, "Nova couldn't display the floating approval card. Nothing was sent.")
    }

    private data class BrainIntent(val kind: String, val value: String? = null)

    private fun understand(text: String, lastApp: String?): BrainIntent {
        val t = text.lowercase(Locale.ROOT)
        // Natural-language fallback for common goals when the local model returns invalid output.
        if (containsAny(t, "i am hungry", "i'm hungry", "im hungry", "hungry", "find food", "food nearby",
                "nearby restaurants", "restaurants near me", "want to eat", "something to eat",
                "pasikkuthu", "pasikuthu", "saapidanum", "சாப்பிடணும்", "பசிக்குது", "உணவு தேடு"))
            return BrainIntent("FOOD_SEARCH")
        if (t.contains("swiggy")) return BrainIntent("SEARCH_SWIGGY", text)
        if (t.contains("zomato")) return BrainIntent("SEARCH_ZOMATO", text)

        // Explicit voice typing and sending; typing never implies sending.
        val typePrefixes = listOf("type this ", "type ", "enter text ", "write in the text box ")
        val typePrefix = typePrefixes.firstOrNull { t.startsWith(it) }
        if (typePrefix != null) {
            val dictated = text.substring(typePrefix.length).trim()
            if (dictated.isNotBlank()) return BrainIntent("TYPE_TEXT", dictated)
        }
        if (containsAny(t, "send it", "send this", "send message", "press send", "அனுப்பு", "send pannu", "anuppu"))
            return BrainIntent("SEND")

        // Clipboard commands act on the currently visible app screen.
        if (containsAny(t, "copy the last message", "copy last message", "copy that message", "last message copy", "கடைசி மெசேஜ் காப்பி", "kadaisi message copy", "last message ah copy")) return BrainIntent("COPY_LAST_MESSAGE")
        if (containsAny(t, "paste it", "paste that", "paste the message", "paste", "இத ஒட்டு", "ஒட்டு", "paste pannu", "adha paste pannu", "paste pannunga")) return BrainIntent("PASTE")

        // English, common Tamil-script and Tanglish expressions.
        if (containsAny(t, "go home", "go to home", "home screen", "home page", "take me home",
                "return home", "open home", "show home", "முகப்பு", "வீட்டுக்கு போ", "ஹோம் ஸ்கிரீன்",
                "home ku po", "veetuku po", "home screen ku po", "main screen ku po")) return BrainIntent("HOME")
        if (containsAny(t, "go back", "come back", "back please", "பின்னால் செல்", "திரும்பி போ", "back po", "pinnaadi po"))
            return BrainIntent("BACK")
        if (containsAny(t, "open settings", "phone settings", "அமைப்புகள்", "settings open pannu", "settings ah open pannu"))
            return BrainIntent("SETTINGS")
        if (containsAny(t, "scroll down", "கீழே போ", "keela scroll", "scroll keela"))
            return BrainIntent("SCROLL_DOWN")
        if (containsAny(t, "scroll up", "மேலே போ", "mela scroll", "scroll mela"))
            return BrainIntent("SCROLL_UP")

        val searchPrefixes = listOf("search online for ", "search the web for ", "look up ", "find online ",
            "search for ", "search ", "google ")
        val searchPrefix = searchPrefixes.firstOrNull { t.startsWith(it) }
        if (searchPrefix != null) return BrainIntent("SEARCH", text.substring(searchPrefix.length).trim())

        val openPrefixes = listOf("please open ", "can you open ", "open ", "launch ", "start ",
            "திற ", "open pannu ", "open pannunga ")
        val openPrefix = openPrefixes.firstOrNull { t.startsWith(it) }
        if (openPrefix != null) {
            val target = text.substring(openPrefix.length).trim()
            if (target.isNotBlank()) return BrainIntent("OPEN_APP", target)
        }

        if (containsAny(t, "what can you do", "who are you", "how are you", "hello", "hi nova",
                "வணக்கம்", "எப்படி இருக்க", "enna panra", "epdi iruka", "vanakkam"))
            return BrainIntent("CONVERSATION", "Hello! I'm Nova. You can speak to me naturally in Tamil, English or Tanglish. What would you like?")
        // Repeat an app action only when the user explicitly names an app-opening action.
        // Generic "again" belongs to conversation reasoning (e.g. another joke / explain again).
        val reopen = Regex("^(?:please )?(?:open|launch|start) (.+?) again$")
            .matchEntire(t)?.groupValues?.get(1)?.trim()
        if (!reopen.isNullOrBlank()) return BrainIntent("OPEN_APP", reopen)
        if (containsAny(t, "repeat the last action", "do the last action again", "repeat that action")) {
            return if (lastApp.isNullOrBlank())
                BrainIntent("CONVERSATION", "I don't have an app action to repeat yet.")
            else BrainIntent("OPEN_APP", lastApp)
        }
        if (containsAny(t, "not that", "wrong one", "வேண்டாம்", "illa adhu illa", "no not this"))
            return BrainIntent("CONVERSATION", "Okay, I won't do that. Tell me what you meant instead.")
        return BrainIntent("UNKNOWN")
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).trim()
        .replace(Regex("[.!?,;:]+$"), "").replace(Regex("\\s+"), " ")

    private fun containsAny(text: String, vararg phrases: String) = phrases.any { text == it || text.contains(it) }

    private fun remember(context: Context, action: String, value: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_action", action).putString("last_app", value).apply()
    }

    private fun statusOnly(context: Context, message: String) {
        context.getSharedPreferences("nova_diagnostics", Context.MODE_PRIVATE).edit()
            .putString("status", message).putBoolean("running", true).apply()
    }

    private fun say(context: Context, message: String) {
        context.getSharedPreferences("nova_diagnostics", Context.MODE_PRIVATE).edit()
            .putString("status", message).putBoolean("running", true).apply()
        NovaSpeech.speak(context, message)
        // The main screen also displays status; speech output is handled by NovaSpeech.
    }

    private fun openApp(context: Context, label: String): Boolean {
        val apps = context.packageManager.getInstalledApplications(0)
        val query = label.lowercase(Locale.ROOT).trim()
        val match = apps.firstOrNull {
            context.packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT) == query
        } ?: apps.firstOrNull {
            context.packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT).contains(query)
        }
        val launch = match?.let { context.packageManager.getLaunchIntentForPackage(it.packageName) } ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return true
    }

    private fun search(context: Context, query: String): Boolean {
        if (query.isBlank()) return false
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }
}
