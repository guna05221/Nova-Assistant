import { verifySession } from "../../lib/server/auth";
import { json, limit, methodNotAllowed, readBearer, readJson } from "../../lib/server/http";
import { generateWithFreeProviders, ProviderError } from "../../lib/server/providers";

export default {
  async fetch(request: Request): Promise<Response> {
    if (request.method !== "POST") return methodNotAllowed(["POST"]);
    const token = readBearer(request);
    const claims = token ? verifySession(token) : null;
    if (!claims) return json({ error: "unauthorized" }, 401);
    if (!limit(request, "chat:" + claims.sub, 20, 60_000)) {
      return json({ error: "rate_limited", message: "Please wait before sending another request." }, 429);
    }

    const body = await readJson(request, 12_288);
    const message = body?.message;
    if (typeof message !== "string" || message.trim().length === 0 || message.length > 4_000) {
      return json({ error: "invalid_request", message: "message must be 1–4000 characters." }, 400);
    }

    if (process.env.NOVA_CLOUD_AI_ENABLED !== "true") {
      return json({
        error: "cloud_ai_disabled",
        message: "Cloud AI is not enabled. No provider request was made.",
        offlineFallback: "Use Nova's supported on-device commands.",
      }, 503);
    }

    const maxOutputTokens = Math.min(
      Math.max(Number(process.env.NOVA_MAX_OUTPUT_TOKENS ?? "500") || 500, 100),
      1_000,
    );
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 30_000);

    try {
      const result = await generateWithFreeProviders(message.trim(), maxOutputTokens, controller.signal);
      return json({
        reply: result.text,
        provider: result.provider,
        model: result.model,
        usage: {
          inputTokens: result.inputTokens,
          outputTokens: result.outputTokens,
        },
        requestId: result.requestId,
        execution: "cloud",
      });
    } catch (error) {
      if (error instanceof ProviderError) {
        return json({
          error: "provider_error",
          provider: error.provider,
          message: "The configured AI provider rejected the request.",
        }, error.status === 429 ? 429 : 503);
      }
      const timedOut = error instanceof Error && error.name === "AbortError";
      return json({
        error: timedOut ? "provider_timeout" : "providers_unavailable",
        message: timedOut ? "Cloud AI timed out. Try again." :
          error instanceof Error ? error.message : "No free AI provider is currently available.",
        offlineFallback: "Use Nova's supported on-device commands.",
      }, 503);
    } finally {
      clearTimeout(timeout);
    }
  },
};
