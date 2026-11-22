import { verifySession } from "../../lib/server/auth";
import { json, limit, methodNotAllowed, readBearer, readJson } from "../../lib/server/http";

const SYSTEM_INSTRUCTIONS = [
  "You are Nova, a helpful personal Android assistant.",
  "Understand English, Tamil, and Tanglish. Be concise and natural.",
  "Help plan tasks, but never claim a phone action, search, purchase, message, or booking succeeded unless a trusted tool result confirms it.",
  "This endpoint is conversation-only in the first backend milestone. Do not invent tool execution or live search results.",
  "Never ask for or expose passwords, OTPs, API keys, payment credentials, or authentication tokens.",
  "For messages, purchases, orders, payments, deletion, or security changes, explain that Nova will require a separate explicit confirmation before execution.",
].join("\n");

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

    const maxOutputTokens = Math.min(
      Math.max(Number(process.env.NOVA_MAX_OUTPUT_TOKENS ?? "500") || 500, 100),
      1_000,
    );

    if (process.env.NOVA_CLOUD_AI_ENABLED !== "true") {
      return json({
        error: "cloud_ai_disabled",
        message: "Cloud AI is not enabled yet. No provider request was made.",
        offlineFallback: "Use Nova's supported on-device commands.",
      }, 503);
    }
    const apiKey = process.env.OPENAI_API_KEY;
    const model = process.env.NOVA_OPENAI_MODEL;
    if (!apiKey || !model) {
      return json({
        error: "provider_not_configured",
        message: "OpenAI credentials and model configuration are required on the server.",
      }, 503);
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 30_000);
    try {
      const upstream = await fetch("https://api.openai.com/v1/responses", {
        method: "POST",
        headers: {
          "Authorization": "Bearer " + apiKey,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          model,
          instructions: SYSTEM_INSTRUCTIONS,
          input: message.trim(),
          max_output_tokens: maxOutputTokens,
          store: false,
        }),
        signal: controller.signal,
      });
      const payload = await upstream.json().catch(() => null) as Record<string, unknown> | null;
      if (!upstream.ok) {
        const status = upstream.status === 429 ? 429 : 502;
        return json({
          error: upstream.status === 429 ? "provider_rate_limited" : "provider_error",
          message: "The AI provider could not complete this request.",
          requestId: upstream.headers.get("x-request-id"),
        }, status);
      }

      const outputText = extractOutputText(payload);
      if (!outputText) return json({ error: "empty_provider_response" }, 502);
      const usage = payload?.usage as Record<string, unknown> | undefined;
      return json({
        reply: outputText,
        usage: usage ? {
          inputTokens: usage.input_tokens ?? null,
          outputTokens: usage.output_tokens ?? null,
          totalTokens: usage.total_tokens ?? null,
        } : null,
        requestId: upstream.headers.get("x-request-id"),
        execution: "cloud",
      });
    } catch (error) {
      const timedOut = error instanceof Error && error.name === "AbortError";
      return json({
        error: timedOut ? "provider_timeout" : "provider_unavailable",
        message: timedOut ? "Cloud AI timed out. Try again." : "Cloud AI is temporarily unavailable.",
        offlineFallback: "Use Nova's supported on-device commands.",
      }, 503);
    } finally {
      clearTimeout(timeout);
    }
  },
};

function extractOutputText(payload: Record<string, unknown> | null): string | null {
  if (!payload || !Array.isArray(payload.output)) return null;
  const parts: string[] = [];
  for (const item of payload.output) {
    if (!item || typeof item !== "object") continue;
    const content = (item as Record<string, unknown>).content;
    if (!Array.isArray(content)) continue;
    for (const part of content) {
      if (!part || typeof part !== "object") continue;
      const value = part as Record<string, unknown>;
      if (value.type === "output_text" && typeof value.text === "string") parts.push(value.text);
    }
  }
  return parts.join("\n").trim() || null;
}
