import { createHmac, timingSafeEqual } from "node:crypto";

type SessionClaims = { sub: string; iat: number; exp: number; scope: string[] };
const encoder = (value: string) => Buffer.from(value).toString("base64url");

function signature(input: string, secret: string): string {
  return createHmac("sha256", secret).update(input).digest("base64url");
}

function safeEqual(a: string, b: string): boolean {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
}

export function issueSession(subject = "nova-device"): string {
  const secret = process.env.NOVA_SESSION_SECRET;
  if (!secret || secret.length < 32) throw new Error("NOVA_SESSION_SECRET must contain at least 32 characters");
  const now = Math.floor(Date.now() / 1000);
  const header = encoder(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const payload = encoder(JSON.stringify({
    sub: subject,
    iat: now,
    exp: now + 60 * 60 * 24 * 7,
    scope: ["chat"],
  } satisfies SessionClaims));
  const unsigned = header + "." + payload;
  return unsigned + "." + signature(unsigned, secret);
}

export function verifySession(token: string): SessionClaims | null {
  const secret = process.env.NOVA_SESSION_SECRET;
  if (!secret || secret.length < 32) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const unsigned = parts[0] + "." + parts[1];
  if (!safeEqual(signature(unsigned, secret), parts[2])) return null;
  try {
    const claims = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8")) as SessionClaims;
    const now = Math.floor(Date.now() / 1000);
    if (typeof claims.sub !== "string" || !Array.isArray(claims.scope)) return null;
    if (typeof claims.exp !== "number" || claims.exp <= now) return null;
    if (!claims.scope.includes("chat")) return null;
    return claims;
  } catch {
    return null;
  }
}

export function verifyPairingCode(candidate: string): boolean {
  const expected = process.env.NOVA_PAIRING_CODE;
  if (!expected || expected.length < 12) return false;
  return safeEqual(candidate, expected);
}
