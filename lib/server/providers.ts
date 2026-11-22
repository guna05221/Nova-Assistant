export type ProviderName = "gemini" | "groq";

export type ProviderReply = {
  provider: ProviderName;
  model: string;
  text: string;
  inputTokens: number | null;
  outputTokens: number | null;
  requestId: string | null;
};

export class ProviderError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly provider: ProviderName,
  ) {
    super(message);
    this.name = "ProviderError";
  }
}

const SYSTEM_INSTRUCTIONS = [
  "You are Nova, a helpful personal Android assistant.",
  "Understand English, Tamil, and Tanglish. Be concise and natural.",
  "Help plan tasks, but never claim a phone action, search, purchase, message, or booking succeeded unless a trusted tool result confirms it.",
  "This endpoint is conversation-only in the first backend milestone. Do not invent tool execution or live search results.",
  "Never ask for or expose passwords, OTPs, API keys, payment credentials, or authentication tokens.",
  "For messages, purchases, orders, payments, deletion, or security changes, explain that Nova will require a separate explicit confirmation before execution.",
].join("\n");

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" ? value as Record<string, unknown> : null;
}

function numberOrNull(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

async function parseJson(response: Response): Promise<Record<string, unknown> | null> {
  return asRecord(await response.json().catch(() => null));
}

async function callGemini(message: string, maxOutputTokens: number, signal: AbortSignal): Promise<ProviderReply> {
  const key = process.env.GEMINI_API_KEY;
  const model = process.env.NOVA_GEMINI_MODEL;
  if (!key || !model) throw new ProviderError("Gemini is not configured.", 503, "gemini");

  const response = await fetch(
    "https://generativelanguage.googleapis.com/v1beta/models/" + encodeURIComponent(model) + ":generateContent",
    {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": key },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: SYSTEM_INSTRUCTIONS }] },
        contents: [{ role: "user", parts: [{ text: message }] }],
        generationConfig: { maxOutputTokens, temperature: 0.4 },
      }),
      signal,
    },
  );
  const payload = await parseJson(response);
  if (!response.ok) throw new ProviderError("Gemini request failed.", response.status, "gemini");

  const candidates = Array.isArray(payload?.candidates) ? payload.candidates : [];
  const candidate = asRecord(candidates[0]);
  const content = asRecord(candidate?.content);
  const parts = Array.isArray(content?.parts) ? content.parts : [];
  const text = parts.map(asRecord).filter(Boolean)
    .map(part => typeof part!.text === "string" ? part!.text as string : "")
    .filter(Boolean).join("\n").trim();
  if (!text) throw new ProviderError("Gemini returned no text.", 502, "gemini");

  const usage = asRecord(payload?.usageMetadata);
  return {
    provider: "gemini",
    model,
    text,
    inputTokens: numberOrNull(usage?.promptTokenCount),
    outputTokens: numberOrNull(usage?.candidatesTokenCount),
    requestId: response.headers.get("x-request-id"),
  };
}

async function callGroq(message: string, maxOutputTokens: number, signal: AbortSignal): Promise<ProviderReply> {
  const key = process.env.GROQ_API_KEY;
  const model = process.env.NOVA_GROQ_MODEL;
  if (!key || !model) throw new ProviderError("Groq is not configured.", 503, "groq");

  const response = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: { Authorization: "Bearer " + key, "Content-Type": "application/json" },
    body: JSON.stringify({
      model,
      messages: [
        { role: "system", content: SYSTEM_INSTRUCTIONS },
        { role: "user", content: message },
      ],
      max_tokens: maxOutputTokens,
      temperature: 0.4,
    }),
    signal,
  });
  const payload = await parseJson(response);
  if (!response.ok) throw new ProviderError("Groq request failed.", response.status, "groq");

  const choices = Array.isArray(payload?.choices) ? payload.choices : [];
  const choice = asRecord(choices[0]);
  const messageData = asRecord(choice?.message);
  const text = typeof messageData?.content === "string" ? messageData.content.trim() : "";
  if (!text) throw new ProviderError("Groq returned no text.", 502, "groq");

  const usage = asRecord(payload?.usage);
  return {
    provider: "groq",
    model,
    text,
    inputTokens: numberOrNull(usage?.prompt_tokens),
    outputTokens: numberOrNull(usage?.completion_tokens),
    requestId: typeof payload?.id === "string" ? payload.id : response.headers.get("x-request-id"),
  };
}

export async function generateWithFreeProviders(
  message: string,
  maxOutputTokens: number,
  signal: AbortSignal,
): Promise<ProviderReply> {
  const order: ProviderName[] = ["gemini", "groq"];
  const failures: string[] = [];

  for (const provider of order) {
    const configured = provider === "gemini"
      ? Boolean(process.env.GEMINI_API_KEY && process.env.NOVA_GEMINI_MODEL)
      : Boolean(process.env.GROQ_API_KEY && process.env.NOVA_GROQ_MODEL);
    if (!configured) continue;

    try {
      return provider === "gemini"
        ? await callGemini(message, maxOutputTokens, signal)
        : await callGroq(message, maxOutputTokens, signal);
    } catch (error) {
      if (error instanceof ProviderError) {
        failures.push(provider + ":" + error.status);
        // Fail over only for quota/rate-limit or temporary upstream failures.
        if (![408, 429, 500, 502, 503, 504].includes(error.status)) throw error;
      } else {
        failures.push(provider + ":network");
      }
    }
  }

  throw new Error(failures.length
    ? "No free AI provider could complete this request (" + failures.join(", ") + ")."
    : "No free AI provider is configured.");
}
