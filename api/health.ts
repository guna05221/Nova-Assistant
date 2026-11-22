import { json, methodNotAllowed } from "../lib/server/http";

export default {
  async fetch(request: Request): Promise<Response> {
    if (request.method !== "GET") return methodNotAllowed(["GET"]);
    return json({
      service: "nova-api",
      status: "ok",
      cloudAiConfigured: process.env.NOVA_CLOUD_AI_ENABLED === "true" &&
        Boolean(process.env.OPENAI_API_KEY) &&
        Boolean(process.env.NOVA_OPENAI_MODEL),
      authConfigured: Boolean(process.env.NOVA_PAIRING_CODE) &&
        Boolean(process.env.NOVA_SESSION_SECRET),
      timestamp: new Date().toISOString(),
    });
  },
};
