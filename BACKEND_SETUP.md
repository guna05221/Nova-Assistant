# Nova Backend — Development Foundation

This repository now includes a Vercel Functions API scaffold. It is intentionally safe-by-default and is not deployed.

## Routes

- GET /api/health — reports whether required server-side configuration is present. It never returns secrets.
- POST /api/v1/session — exchanges a manually entered pairing code for a short-lived signed bearer token.
- POST /api/v1/chat — authenticated conversation endpoint using the OpenAI Responses API.

## Local configuration

Copy .env.example to .env.local for local development. Set:
- NOVA_CLOUD_AI_ENABLED=false until you explicitly decide to enable paid provider requests.
- NOVA_PAIRING_CODE to a random value of at least 12 characters.
- NOVA_SESSION_SECRET to a cryptographically random value of at least 32 characters.
- OPENAI_API_KEY only in the server environment, never in Android, Git, or client config.
- NOVA_OPENAI_MODEL to a model ID available to your OpenAI API project.
- NOVA_MAX_OUTPUT_TOKENS to cap output size (100–1000).

No API request to OpenAI is made while NOVA_CLOUD_AI_ENABLED is not exactly true. A health check does not contact OpenAI.

## Security and current limitations

- The session token is signed with HMAC-SHA256 and expires after seven days.
- Pairing-code and chat throttles are in-memory best-effort protections. Serverless instances do not share memory; these are not production-grade global rate limits.
- The current pairing-code flow is a development bootstrap, not full account/device identity management. Rotate the pairing code if it is exposed.
- The chat endpoint sends only the current message, not a stored transcript. Raw voice audio is not uploaded.
- The OpenAI request uses store=false. Provider usage tokens are returned to the caller for display.
- The tool policy in lib/server/policy.ts is an allow-list foundation only. No tools are executed by the backend yet.
- A reliable monthly USD hard cap, durable usage ledger, revocation, and global per-user quotas require a persistent database or an equivalent durable metering service. They are not falsely simulated with per-instance memory.
- Do not enable paid requests until an OpenAI API key, model, budget policy, and billing controls have been configured and reviewed.

## Offline behavior

The backend never pretends to be available offline. When it is disabled or unreachable, the client should use only its existing supported local commands and show that cloud reasoning is unavailable. Do not queue sensitive actions for later automatic execution.

## Deploy status

Not deployed. No Vercel project was created or linked by this change. No Vercel resources, paid services, API keys, or provider requests were created.
