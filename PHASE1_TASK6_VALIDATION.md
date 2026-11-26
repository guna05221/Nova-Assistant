# Phase 1 Task 6 — Local-first routing

**Status:** Implemented and automated validation passed  
**Branch:** `phase1/brain-foundation`

## Behavior

1. Nova attempts local Qwen intent interpretation before any cloud planner request.
2. Nova attempts local Qwen conversation generation before any cloud conversation request.
3. Cloud AI is eligible only after local inference is unavailable or returns unusable output, and only when the separate cloud-consent preference is enabled and at least one provider key is configured.
4. Provider methods retain their own consent checks, so the routing decision is not the only enforcement boundary.
5. If cloud consent is disabled, an unavailable or failed local model does not trigger a network AI request.
6. A stale utterance is checked before cloud fallback, so an older request cannot start a cloud call after a newer utterance supersedes it.
7. Existing typed Brain Contracts, financial deny policy, local Qwen model, and floating exact-message approval flow are preserved.

## Automated validation

- Workflow: [Nova validation (tests only)](https://github.com/guna05221/Nova-Assistant/actions/runs/45885112086)
- Result: **Success**
- Kotlin compilation: passed
- Unit tests: passed
- Gradle task: `testDebugUnitTest`
- APK build/signing: not run
- Uploaded artifacts: 0

## New routing tests

`AiRoutingPolicyTest` verifies:
- Local success prevents cloud fallback even when cloud consent is enabled.
- Local failure permits cloud only with explicit consent and a configured provider.
- Missing consent or provider configuration denies cloud routing.
- Offline mode does not attempt cloud fallback.

Device-level Qwen performance and Android UI behavior still require later on-device validation; the CI run validates compilation and JVM unit tests only.
