# Nova AI — Brain Architecture and Safety Foundation

**Status:** Phase 1 design baseline  
**Branch:** `phase1/brain-foundation`  
**Parent baseline:** `2336fffedf5d86ec098bafa12c926e3f57427c13` on `feature/contextual-speech-understanding`  
**Scope of this change:** Architecture and acceptance criteria only. No runtime behavior is changed by this document.

## 1. Design goals

Nova is a local-first Android personal assistant. It should understand the user's goal, preserve relevant conversation context, plan supported tasks, execute only permitted actions, verify the result, and explain what happened.

The model is never the authority for permissions. All model output is untrusted input. A deterministic policy layer decides whether an action may proceed.

Non-negotiable invariants:

1. **Financial actions are permanently prohibited.** Nova must not initiate, authorize, automate, or hand off a payment, money transfer, purchase, paid order, recharge, subscription, or other financial transaction. No user confirmation, model response, API setting, or future plugin may override this prohibition.
2. **Message sending requires exact, one-time approval.** Typing is not sending. Before sending, Nova must display the exact destination and exact message. Approval is tied to an immutable snapshot of both, expires, is consumed once, and is invalidated by any change. If the app, destination, message, or visible send context cannot be verified, do not send.
3. **Cloud AI is disabled by default.** An API key being present is not consent. Local processing is the default. Cloud use requires a separate, explicit user opt-in, must be visible, and must never silently fall back to a paid provider.
4. **Fail closed.** Unknown, malformed, ambiguous, unsupported, stale, or unverifiable actions are not executed.
5. **No false success.** Nova reports success only after the Android executor confirms the operation; otherwise it reports the failure or uncertainty.

## 2. Target architecture

```text
Voice / Text / Explicitly permitted screen input
                    |
                    v
          Input & Session Adapter
                    |
                    v
       Speech / Language Understanding
       - ASR transcript and confidence
       - Tamil / English / Tanglish
       - contextual normalization
                    |
                    v
        Conversation & Context Engine
       - recent turns
       - user-approved long-term memory
       - task state and references
                    |
                    v
             Nova Brain / Planner
       - intent and goal extraction
       - clarification when uncertain
       - structured multi-step plan
       - model output is untrusted
                    |
                    v
         Deterministic Safety Gateway
       - permanent financial deny policy
       - capability allow-list
       - exact-message approval gate
       - cloud consent gate
       - Android permission checks
                    |
          allowed | denied | clarify
                    |
                    v
             Tool Executor
       - one action at a time
       - scoped Android capabilities
       - no arbitrary model-generated code
                    |
                    v
          Result / State Verification
       - inspect action result
       - confirm current app and target
       - record success, failure or unknown
                    |
                    v
         Response Composer / Voice Engine
       - natural concise answer
       - user-language response
       - no invented completion claims
```

### Layer responsibilities

| Layer | Responsibility | Must not do |
|---|---|---|
| Input adapter | Receive voice/text and session metadata | Decide permissions |
| Speech understanding | Transcribe, detect language, normalize likely ASR errors | Invent missing intent or silently change meaning |
| Context engine | Resolve references and retrieve relevant approved memory | Treat old context as current authorization |
| Brain/planner | Produce typed intent and proposed steps | Execute tools or grant permissions |
| Safety gateway | Apply immutable rules, consent and capability policy | Ask the model whether an action is safe |
| Executor | Execute one approved, typed capability | Accept arbitrary code or bypass policy |
| Verifier | Confirm the resulting Android state | Assume a tap means success |
| Response/voice | Explain result and ask clarifying questions | Claim unverified completion |

## 3. Typed action boundary

The brain should return a structured proposal, not Android instructions:

- `IntentProposal`: intent, target, extracted parameters, confidence, source turn.
- `PlanStep`: typed capability, validated arguments, dependencies.
- `PolicyDecision`: `ALLOW`, `DENY`, or `REQUIRE_APPROVAL`, with a stable reason code.
- `ExecutionResult`: `SUCCEEDED`, `FAILED`, or `UNKNOWN`, with evidence and a safe user-facing summary.

Every tool call must pass through the same safety gateway immediately before execution. Model text, JSON fields, UI callbacks, notifications, and deep links must not call the executor directly.

Plans execute sequentially. Re-evaluate policy and required permissions for every step; a previous approval must not authorize a later step.

## 4. Permanent financial-action deny policy

### Prohibited capability classes

- Payments and bill payments
- UPI, bank, wallet or other money transfers
- Purchases, checkout and paid orders
- Food/grocery/product ordering that creates a paid order
- Mobile/data/DTH recharges
- Subscription creation, renewal or upgrade
- Any equivalent financial transaction through a browser, app, accessibility action, API, or future plugin

### Enforcement design

1. Maintain a central, non-user-configurable policy that marks these capability classes as permanently denied.
2. Apply the policy to the interpreted intent before planning.
3. Apply it again to every proposed plan step after planning.
4. Apply it a final time in the executor immediately before any side effect.
5. Never provide an approval UI or override token for a prohibited action.
6. Treat ambiguous transaction intent as denied or ask a clarification that cannot authorize a transaction.
7. Keep informational help (e.g. explaining a bill or comparing free plans) separate from transaction execution.
8. Add regression tests for English, Tamil, Tanglish, paraphrases, indirect requests, model-proposed actions, and multi-step plans.

A phrase list may help recognize utterances, but it is not the security boundary. The actual security boundary is the absence of any permitted financial capability plus the repeated central deny check.

## 5. Exact-message approval protocol

The message workflow must be a dedicated capability, not a generic click action.

1. User asks Nova to send a message.
2. Nova reads the currently visible editable message and destination from the foreground app, where Android exposes them.
3. Nova creates a pending approval containing:
   - package name / app identity;
   - recipient or destination identity, if available;
   - exact message text;
   - cryptographic digest of the canonical snapshot;
   - creation time and short expiry;
   - unique one-time approval identifier.
4. Nova displays the exact destination and exact message in a clear approval surface with Allow and Deny.
5. Allow is accepted only for the currently pending approval ID and before expiry.
6. Immediately before sending, the executor re-reads the foreground package, destination, and message and recomputes the digest.
7. Any mismatch, unavailable field, app switch, edit, expired approval, duplicate tap, process restart, or missing accessibility node invalidates the approval and sends nothing.
8. Consume the approval atomically before the send side effect so it cannot be replayed.
9. After the tap, verify the best available app state. If delivery cannot be confirmed, say that Nova attempted the action but cannot verify delivery.

Voice recognition of "yes", "send", or similar words alone is not approval. Approval must be an explicit action on the approval surface. A changed message requires a new preview and a new approval.

## 6. Local-first and cloud-consent boundary

### Default state

- `cloudAiEnabled = false` when the app is first installed and whenever the setting is absent.
- Saved API keys do not change this setting.
- Gemini/Groq provider presence does not imply consent.
- The planner, chat responder, speech path, retry handler, and fallback path all use local processing unless cloud use has been explicitly enabled.
- If local AI is unavailable and cloud is disabled, Nova explains that it cannot answer with the local model; it must not silently use cloud.

### Explicit opt-in

Cloud use requires a separate user-controlled setting, independent from key storage. The UI must explain that the selected request content leaves the phone and identify the provider. The setting must be off by default and easy to turn off. No background, proactive, screen, clipboard, contact, or message content is sent to cloud by default.

The network boundary must be centralized: provider clients cannot open outbound AI requests without a successful consent check. A paid-provider response, quota error, or network error must never trigger an alternate paid service or billing change.

For the ₹0 development requirement, provider use remains off unless the user separately authorizes a specific free-tier integration and accepts its limits. Do not embed secrets in the APK or repository.

## 7. Memory and privacy boundary

- Short-term conversation context is bounded and local.
- Long-term memory stores only useful, user-approved facts or task state, with clear review and deletion controls.
- Sensitive screen content, message content, credentials, and payment data are not copied into long-term memory by default.
- Context retrieval informs interpretation; it never acts as authorization.
- Cloud consent is not implied by memory, app permission, or prior cloud use.

## 8. Android capability registry

Start with a small allow-list of individually scoped capabilities, such as opening an installed app, Home/Back, scrolling, and typing into a user-focused editable field. Each capability declares required Android permissions, whether it is reversible, whether it needs approval, and how its result is verified.

Do not expose a generic `clickSendButton()` capability to the planner. Sending must only be reachable through the exact-message approval workflow. Do not expose financial capabilities at all.

## 9. Task execution and recovery

- Execute one step at a time.
- Persist task progress separately from permission grants.
- Re-check app state and policy between steps.
- On timeout or uncertain result, stop and report uncertainty rather than retrying a potentially non-idempotent action.
- A retry must receive a new policy decision.
- Ask one concise clarification when target, recipient, or user intent is genuinely ambiguous.
- Keep an auditable local record of decision reason codes without storing unnecessary private content.

## 10. Phase 1 implementation sequence

| Task | Deliverable | Acceptance gate |
|---|---|---|
| 1. Architecture baseline | This document and approved module boundaries | No runtime behavior changed |
| 2. Central safety policy | Immutable financial deny + typed capability checks | Unit tests prove prohibited capabilities cannot pass |
| 3. Exact-message approval | Pending snapshot, explicit Allow/Deny, expiry, digest revalidation, one-time consumption | Tests prove changed/stale/replayed approval never sends |
| 4. Cloud consent gate | Default-off setting independent of provider keys; all provider calls gated | Tests prove key presence alone never makes a request |
| 5. Brain contracts | Typed proposal, context and plan models | Malformed model output cannot reach executor |
| 6. Local-first routing | Local Qwen first; cloud only through explicit gate | Offline mode makes no network AI request |
| 7. Conversation and context | Natural dialogue, follow-up resolution and clarification | Multi-turn regression suite passes |

Do not start the next task until the current task has a recorded result.

## 11. Required automated tests

### Financial policy
- All prohibited capability identifiers are denied regardless of confirmation.
- English, Tamil and Tanglish transaction requests do not result in execution.
- Model-proposed financial actions are denied after parsing.
- A financial step hidden inside a multi-step plan blocks that step and prevents unsafe continuation.
- No payment/order/recharge/subscription executor is registered.

### Message approval
- No approval means no send.
- Deny means no send.
- Exact approval sends only the matching package, destination and message.
- Changed text, recipient or foreground app means no send.
- Expired and replayed approvals mean no send.
- Process restart clears pending approval.
- Failed/unavailable snapshot means no approval request and no send.

### Cloud consent
- Missing setting defaults to disabled.
- A saved API key does not enable cloud.
- All AI provider entry points reject cloud calls while disabled.
- No automatic cloud fallback occurs when local AI fails.
- Explicit opt-in is required before any cloud request.

### Brain / executor separation
- Invalid or unknown model output cannot call Android capabilities.
- Every action is revalidated at execution time.
- Execution results are verified before Nova reports success.

## 12. Compatibility and branch reuse decisions

- Keep `feature/contextual-speech-understanding` as the source baseline and preserve its local Qwen integration.
- `feature/agent-action-workflow` contains a promising one-time exact-message approval implementation. Reuse it only after checking its full diff and integrating it through the new policy boundary; do not merge the branch wholesale.
- `ui/chatgpt-redesign` is a UI-only candidate. Review it separately after the brain contracts and safety behavior are stable.
- Do not merge `feature/voice-reactive-popup` or `feature/autonomous-agent-workflow` wholesale: they replace/remove existing local AI components and introduce provider/overlay changes that need separate compatibility and safety review.

## 13. Current baseline risks to remove

- Cloud provider calls are currently triggered by saved provider keys without a separate global consent gate.
- The baseline has a direct SEND path and lacks an exact-message preview/approval protocol.
- Financial protection relies partly on phrase matching and is not a complete execution-boundary policy.
- The speech recognizer can use an online provider; local-first AI inference does not mean speech recognition is offline.
- Speaker profile enrollment is not currently used as a per-command authorization gate.
- Existing tests and on-device performance evidence are insufficient; do not describe features as verified until tests and device validation exist.
