type Bucket = { count: number; resetAt: number };
const buckets = new Map<string, Bucket>();

// Best-effort per-instance protection only. Serverless instances do not share memory.
// A durable store is required for global/per-user enforcement in production.
export function checkRateLimit(key: string, max: number, windowMs: number): boolean {
  const now = Date.now();
  const current = buckets.get(key);
  if (!current || current.resetAt <= now) {
    buckets.set(key, { count: 1, resetAt: now + windowMs });
    return true;
  }
  if (current.count >= max) return false;
  current.count += 1;
  if (buckets.size > 2_000) {
    for (const [bucketKey, bucket] of buckets) {
      if (bucket.resetAt <= now) buckets.delete(bucketKey);
    }
  }
  return true;
}
