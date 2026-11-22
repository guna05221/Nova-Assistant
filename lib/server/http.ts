import { checkRateLimit } from "./rate-limit";

export function json(data: unknown, status = 200): Response {
  return Response.json(data, {
    status,
    headers: {
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

export function methodNotAllowed(allowed: string[]): Response {
  return json({ error: "method_not_allowed", allowed }, 405);
}

export function readBearer(request: Request): string | null {
  const value = request.headers.get("authorization") ?? "";
  const match = /^Bearer ([A-Za-z0-9._~-]+)$/.exec(value);
  return match?.[1] ?? null;
}

export async function readJson(request: Request, maxBytes = 16_384): Promise<Record<string, unknown> | null> {
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > maxBytes) return null;
  const raw = await request.text();
  if (new TextEncoder().encode(raw).byteLength > maxBytes) return null;
  try {
    const value: unknown = JSON.parse(raw);
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

export function limit(request: Request, key: string, max: number, windowMs: number): boolean {
  const forwarded = request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
  return checkRateLimit(key + ":" + forwarded, max, windowMs);
}
