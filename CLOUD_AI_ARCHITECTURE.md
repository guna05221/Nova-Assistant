# Nova Cloud AI Architecture

Status: architecture specification (not yet connected to a live AI provider or deployed backend).

## Product decision

Nova uses a cloud-first reasoning architecture with a lightweight on-device fallback. The Android app remains responsible for voice capture, permissions, UI, and actions that must happen on the phone. A secure backend brokers AI-provider calls and server-side tools. The provider is intentionally abstracted so Nova can change models without shipping a new APK.

User-selected operating preferences:
- Architecture: cloud AI with lightweight offline fallback.
- Budget: flexible monthly budget, with explicit user-configurable caps and alerts.
- Privacy: convenience-oriented; relevant context may be sent to the cloud when needed, but credentials, payment secrets, and unrelated personal data must not be sent.
- Autonomy: ordinary low-risk tasks may run without repeated confirmation; sensitive or irreversible actions require confirmation.

## High-level components

1. **Nova Android app**
   - Captures voice/text and renders conversation and plans.
   - Requests Android runtime permissions only when a feature needs them.
   - Holds a short-lived session token, never an AI-provider API key.
   - Executes phone-side actions only through a typed, allow-listed action registry.
   - Reports action outcomes to the backend so the assistant can verify completion.

2. **Nova API backend**
   - Authenticates the user/device and validates every request.
   - Applies quotas, rate limits, budget controls, and request-size limits before provider calls.
   - Selects a model through a provider adapter.
   - Maintains conversation/task state and issues typed tool calls.
   - Executes only explicitly registered server-side tools.
   - Returns a structured plan, user-facing response, and typed device-action requests.

3. **AI provider adapter**
   - Provider-neutral interface: generate, stream, estimate usage, and health check.
   - Provider credentials live only in server-side secret storage.
   - Model routing uses task complexity, latency, availability, and configured spend limits.
   - Provider failures trigger bounded retry/fallback; never retry non-idempotent actions automatically.

4. **Tool registry**
   - Server tools: approved search, retrieval, and integrations that have their own authenticated server-side connectors.
   - Device tools: open an app, launch Maps/search intents, request location, or use other Android capabilities exposed by the app.
   - Every tool has a name, version, JSON input schema, permission requirement, risk level, timeout, and result schema.

## Request lifecycle

1. Android sends a user request over HTTPS with a short-lived access token, request ID, locale, and only the context needed for the task.
2. Backend authenticates the caller, checks request size, rate limits, budget state, and idempotency key.
3. Context builder selects relevant conversation history and permitted preferences. It excludes secrets and unrelated data.
4. Planner asks the selected model for a structured plan using the registered tool schemas. Model output is untrusted data, never executable code.
5. Policy engine validates every proposed tool name, arguments, permission, risk, and execution preconditions.
6. Low-risk actions may proceed under the user's configured autonomy policy. Sensitive actions return a confirmation request bound to the exact action and arguments.
7. Backend tools execute server-side. Device actions are returned to Android as typed requests; Android checks permissions and executes only supported actions.
8. Android reports the actual outcome. The backend marks the step complete only from that outcome, not from the model's claim.
9. The model may receive the tool result and continue planning within a bounded step count and time budget.
10. Nova returns a concise result and any pending confirmation or limitation.

## Security requirements

- Never embed provider API keys, database service-role keys, or backend signing secrets in the APK, source repository, build logs, or client configuration.
- Store provider keys as deployment secrets; rotate them and use separate development/production credentials.
- Use TLS for all app/backend traffic. Validate token issuer, audience, expiry, and signature.
- Issue short-lived, scoped session tokens. Revoke sessions on logout or device removal.
- Apply per-user and per-device rate limits, request-size limits, concurrency limits, and abuse detection.
- Treat model output, retrieved web pages, app content, and tool results as untrusted. Ignore instructions embedded in retrieved content that attempt to override Nova policy or reveal secrets.
- Do not allow arbitrary shell, JavaScript, SQL, deep-link, or accessibility command execution from model text.
- Validate tool arguments against strict schemas and server-side allow-lists. Normalize URLs and block private-network/metadata endpoints for server fetch tools.
- Use idempotency keys for state-changing server actions. Use short-lived, single-use confirmation tokens bound to the action name, exact arguments, user, and expiry.
- Require explicit confirmation for sending messages/emails, placing orders, payments, purchases, deleting data, changing security settings, or sharing sensitive information.
- Never collect or store payment credentials. Prefer provider-owned checkout/confirmation screens.
- Keep audit events for security and troubleshooting, but redact tokens, API keys, message bodies, precise location, and other unnecessary personal data.
- Add a user control to clear conversation history and disable cloud context sharing.

## Privacy profile: convenience with minimization

The convenience setting permits Nova to send relevant task context to the cloud to improve task completion. It does not mean unrestricted collection.

Allowed when relevant and permissioned:
- Current request and a bounded amount of conversation context.
- Locale and language preference.
- Approximate or precise location only for location-dependent tasks and only after Android permission.
- User-approved preferences needed for the current task.

Never send:
- API keys, passwords, OTPs, authentication cookies, private signing keys, or payment credentials.
- Full contact lists, full inboxes, or unrelated files by default.
- Background microphone recordings or continuous location history.
- Sensitive information that is not needed for the requested task.

Default retention: do not persist raw voice audio. Store conversation text only if the user enables history; provide deletion controls. Operational logs should contain request IDs, timing, model/usage metadata, and redacted error codes rather than raw prompts.

## Tool execution and autonomy policy

Risk levels:
- **LOW**: open an installed app, open a web search, show Maps directions, read a permitted public result. May execute automatically after permission checks.
- **MEDIUM**: access selected files, read a selected message, or use a connected account. Require explicit feature authorization and scope limits; ask when the target or data scope is ambiguous.
- **HIGH**: send a message/email, submit an order, pay, purchase, delete, publish, or change security/privacy settings. Require a clear confirmation immediately before execution, showing the exact recipient/item/amount/content/action.

The AI proposes; the policy engine authorizes; the tool executor executes; the Android app or integration reports the outcome. The model cannot promote its own permission level.

## Cost controls

Budget is configurable, not unlimited by default.
- User sets monthly budget, optional daily cap, and alert thresholds (for example 50%, 80%, and 100%).
- Track provider-reported input/output tokens and estimated cost per request, user, model, and billing period.
- Enforce hard limits server-side before a request; stop or downgrade model routing when the remaining budget is insufficient.
- Use a small/low-cost model for classification, simple intents, and short summaries; reserve stronger models for multi-step reasoning.
- Trim and summarize context, cap output tokens, cache safe repeatable public lookups, and avoid duplicate calls.
- Expose a usage screen with request count, model, estimated cost, and remaining budget.
- At the hard cap, switch to offline fallback and clearly explain that cloud AI is paused. Never silently exceed the configured cap.
- Provider billing can differ from estimates; display that costs are estimates until reconciled with provider usage/billing data.

## Offline and degraded operation

When there is no network, the app must not pretend cloud reasoning is available.
- Keep deterministic local commands for supported basics: open apps/settings, Back/Home/scroll where permitted, and launch saved/search intents.
- Use Android speech recognition only when the installed speech service supports it; offline speech recognition is not guaranteed by the current implementation.
- Show an offline indicator and explain which features are unavailable.
- Do not queue messages, orders, payments, purchases, deletion, or other state-changing actions for later automatic execution.
- Safe read-only requests may be saved as drafts for the user to retry.
- On reconnection, perform a health check and resume only user-approved, idempotent work.
- Keep cloud reasoning optional; no large local language model is required for the first cloud-fallback release.

## Reliability and observability

- Use bounded timeouts, exponential backoff with jitter for safe requests, circuit breakers, and provider health checks.
- Retry only idempotent inference requests; never blindly retry a payment/order/message action.
- Add request correlation IDs across app, backend, provider, and tool execution.
- Record latency, provider errors, token usage, cost estimates, tool success/failure, and fallback frequency.
- Redact sensitive values in logs and restrict log access.
- Add automated tests for authentication, budget caps, tool schema validation, prompt injection, confirmation replay, duplicate requests, offline behavior, and provider outage.

## Suggested implementation phases

1. **Foundation**: backend skeleton, provider adapter interface, auth/session flow, secret management, request validation, and cost ledger.
2. **Cloud conversation**: structured model responses, streaming responses, context limits, and user-visible usage controls.
3. **Safe tools**: tool registry, risk policy, confirmation tokens, Android device-action protocol, and verified outcomes.
4. **Offline fallback**: deterministic command subset, offline UI state, and safe retry behavior.
5. **Hardening**: tests, abuse/rate limits, prompt-injection defenses, redacted telemetry, and provider failover.
6. **Provider selection/deployment**: choose a provider and hosting environment, configure secrets outside Git, deploy staging, then production.

## Current repository status

The Android project currently contains local llama.cpp integration and an initial allow-listed food/search handoff. This document specifies the target cloud architecture; it does not mean a backend, provider account, API key, cloud deployment, billing controls, or cloud conversation flow has already been implemented. Those require a provider and hosting deployment decision. The local model integration should remain in place until the cloud path is built, tested, and verified; it can then be removed or retained as an optional future fallback.


## Provider decision update — November 2026

The selected initial cloud providers are Google Gemini as primary and Groq as fallback. Both are configured through server-side provider adapters in `lib/server/providers.ts`; provider keys are never shipped in the Android app. Provider IDs are configured independently through `NOVA_GEMINI_MODEL` and `NOVA_GROQ_MODEL`.

The backend chat route now attempts Gemini first and falls back to Groq only for temporary failures and quota/rate-limit responses. If neither provider is configured or available, Nova should use the existing on-device experience. Free-tier availability and quotas are provider-controlled and are not unlimited or guaranteed.

Implementation status: provider adapters and backend routing are committed, but cloud AI remains disabled by default. Android-to-cloud integration, durable quota accounting, device pairing hardening, automated backend tests, and deployment remain outstanding. No provider API calls, API keys, Vercel deployment, or paid resources have been created.
