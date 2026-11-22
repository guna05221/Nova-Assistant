import { json, methodNotAllowed } from "../lib/server/http";

export default {
  async fetch(request: Request): Promise<Response> {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    return json({
      service: "nova-api",
      status: "ok",
      cloudAiEnabled: process.env.NOVA_CLOUD_AI_ENABLED === "true",
      providers: {
        geminiConfigured: Boolean(process.env.GEMINI_API_KEY && process.env.NOVA_GEMINI_MODEL),
        groqConfigured: Boolean(process.env.GROQ_API_KEY && process.env.NOVA_GROQ_MODEL),
      },
      authConfigured: Boolean(process.env.NOVA_PAIRING_CODE) &&
        Boolean(process.env.NOVA_SESSION_SECRET),
      timestamp: new Date().toISOString(),
    });
  },
};
