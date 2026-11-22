# Nova Backend — Development Foundation

This repository includes a Vercel Functions API scaffold. It is intentionally safe-by-default and is not deployed.

## Routes

- GET /api/health — reports configuration presence only; never returns secrets.
- POST /api/v1/session — exchanges a manually entered pairing code for a short-lived signed bearer token.
- POST /api/v1/chat — authenticated conversation endpoint using Gemini first and Groq as fallback.

## Free-tier provider configuration

Set these server-side environment variables in Vercel or a local .env.local file:

- NOVA_CLOUD_AI_ENABLED=false (keep disabled until you explicitly opt in).
- GEMINI_API_KEY and NOVA_GEMINI_MODEL for Google Gemini.
- GROQ_API_KEY and NOVA_GROQ_MODEL for Groq fallback.
- NOVA_PAIRING_CODE to a random value of at least 12 characters.
- NOVA_SESSION_SECRET to a cryptographically random value of at least 32 characters.
- NOVA_MAX_OUTPUT_TOKENS to cap output size (100–1000).

Get provider keys from Google AI Studio and Groq Console. Select model IDs currently available to your accounts. Free tiers have changing quotas, model access and terms; they are not unlimited or guaranteed to remain free.

Nova tries Gemini first. It falls back to Groq only when Gemini is temporarily unavailable, rate-limited, or over quota. It does not retry a permanent request/configuration error. If neither provider is configured or available, the API returns a clear unavailable response so the Android app can use its supported offline features.

No provider request is made unless NOVA_CLOUD_AI_ENABLED is exactly true. Keep both provider accounts on free tiers and do not enable paid billing if the goal is ₹0 provider spend.

## Security and current limitations

- Provider keys stay on the server and must never be placed in Android, Git, or client configuration.
- The session token is signed with HMAC-SHA256 and expires after seven days.
- Pairing-code and chat throttles are in-memory best-effort protections. Serverless instances do not share memory; these are not production-grade global rate limits.
- The current pairing-code flow is a development bootstrap, not full account/device identity management. Rotate the pairing code if it is exposed.
- The chat endpoint sends only the current message, not a stored transcript. Raw voice audio is not uploaded.
- Provider usage counts are returned when available. They are not a durable billing ledger.
- The tool policy in lib/server/policy.ts is an allow-list foundation only. No device tools are executed by the backend yet.
- A reliable monthly hard cap, durable usage ledger, revocation, and global per-user quotas require persistent storage. They are not simulated with per-instance memory.
- Free-tier quotas can be exhausted; automatic fallback cannot guarantee uninterrupted free service.

## Offline behavior

When cloud AI is disabled, offline, or out of quota, Nova should use only its supported on-device commands and local model. Do not queue sensitive actions for later automatic execution.

## Deployment status

Not deployed. No Vercel project, paid service, API key, or provider request was created by this change.
