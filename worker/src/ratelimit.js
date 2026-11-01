/**
 * Very small fixed-window rate limiter.
 *
 * Honest limitation: Cloudflare runs many isolates, and this counter lives in one isolate's memory,
 * so it slows a single abusive client but is not a global guarantee. For a hard limit add a
 * Cloudflare Rate Limiting rule (see docs/CLOUDFLARE_R2_WORKER_SETUP.md) or move the counter into a
 * Durable Object. It is a second line of defence — the primary one is that every request needs a
 * valid Supabase JWT and can only touch its own prefix.
 */

import { error } from './errors.js';

const WINDOW_MS = 60_000;
const buckets = new Map();

/** Default budget per user per minute, split between reads and writes. */
const LIMITS = {
  authorize: 60,
  read: 240,
  write: 60,
};

export function rateLimit(userId, kind) {
  const limit = LIMITS[kind] ?? LIMITS.read;
  const now = Date.now();
  const key = `${kind}:${userId}`;
  const current = buckets.get(key);

  if (!current || current.resetAt <= now) {
    buckets.set(key, { count: 1, resetAt: now + WINDOW_MS });
    prune(now);
    return;
  }
  current.count += 1;
  if (current.count > limit) {
    const retryAfter = Math.max(1, Math.ceil((current.resetAt - now) / 1000));
    const limited = error(429, 'rate_limited', `Too many requests. Retry in ${retryAfter}s.`);
    limited.retryAfter = retryAfter;
    throw limited;
  }
}

function prune(now) {
  if (buckets.size < 5_000) return;
  for (const [key, value] of buckets) {
    if (value.resetAt <= now) buckets.delete(key);
  }
}
