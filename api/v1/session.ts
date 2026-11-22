import { issueSession, verifyPairingCode } from "../../lib/server/auth";
import { json, limit, methodNotAllowed, readJson } from "../../lib/server/http";

export default {
  async fetch(request: Request): Promise<Response> {
    if (request.method !== "POST") return methodNotAllowed(["POST"]);
    if (!limit(request, "pairing", 5, 15 * 60 * 1000)) {
      return json({ error: "rate_limited", message: "Try again later." }, 429);
    }
    const body = await readJson(request, 2_048);
    if (!body || typeof body.pairingCode !== "string" || body.pairingCode.length > 256) {
      return json({ error: "invalid_request" }, 400);
    }
    if (!process.env.NOVA_SESSION_SECRET || process.env.NOVA_SESSION_SECRET.length < 32) {
      return json({ error: "server_not_configured", message: "Session signing is not configured." }, 503);
    }
    if (!verifyPairingCode(body.pairingCode)) {
      return json({ error: "unauthorized" }, 401);
    }
    return json({
      accessToken: issueSession(),
      tokenType: "Bearer",
      expiresIn: 604800,
    });
  },
};
