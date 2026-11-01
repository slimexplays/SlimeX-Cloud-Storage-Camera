/**
 * Supabase JWT verification.
 *
 * The Worker does not trust a token just because it looks like a JWT: it asks Supabase's own
 * `/auth/v1/user` endpoint, with the project's publishable (anon) key, and uses the user id that
 * Supabase returns. That means revoked, expired or forged tokens are rejected by the source of
 * truth, and a user can only ever act inside their own `{userId}/` prefix.
 *
 * Only the publishable/anon key is stored in the Worker. The `service_role` key is never used here
 * and must never be configured.
 */

import { error } from './errors.js';

const TOKEN_CACHE_TTL_MS = 60_000;
const TOKEN_CACHE_MAX = 500;

/** Isolate-local cache: avoids one Supabase round trip per object during a multi-part upload. */
const tokenCache = new Map();

function supabaseUrl(env) {
  const raw = (env.SUPABASE_URL || '').trim().replace(/\/+$/, '');
  return raw;
}

function cacheKey(token) {
  // Cheap, non-reversible cache key so raw tokens are never held as Map keys for long.
  let hash = 0;
  for (let i = 0; i < token.length; i += 1) {
    hash = (hash * 31 + token.charCodeAt(i)) | 0;
  }
  return `${token.length}:${hash}`;
}

function remember(token, user) {
  if (tokenCache.size >= TOKEN_CACHE_MAX) tokenCache.clear();
  tokenCache.set(cacheKey(token), { user, expiresAt: Date.now() + TOKEN_CACHE_TTL_MS });
}

/**
 * Verifies the bearer token and returns `{ userId, email }`.
 * Throws an HTTP-shaped error (401) when the token is missing, malformed or rejected.
 */
export async function authenticate(request, env) {
  if (!supabaseUrl(env) || !env.SUPABASE_ANON_KEY) {
    throw error(500, 'worker_misconfigured', 'SUPABASE_URL / SUPABASE_ANON_KEY are not configured on the Worker.');
  }

  const header = request.headers.get('authorization') || request.headers.get('Authorization') || '';
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  if (!match) {
    throw error(401, 'missing_token', 'Sign in to your Supabase account and try again.');
  }
  const token = match[1].trim();
  if (token.split('.').length !== 3) {
    throw error(401, 'invalid_token', 'The session token is not a valid Supabase JWT.');
  }

  const cached = tokenCache.get(cacheKey(token));
  if (cached && cached.expiresAt > Date.now()) return cached.user;

  let response;
  try {
    response = await fetch(`${supabaseUrl(env)}/auth/v1/user`, {
      method: 'GET',
      headers: {
        apikey: env.SUPABASE_ANON_KEY,
        authorization: `Bearer ${token}`,
      },
    });
  } catch (cause) {
    throw error(503, 'supabase_unreachable', 'Could not reach Supabase to verify the session.', cause);
  }

  if (response.status === 401 || response.status === 403) {
    throw error(401, 'invalid_token', 'Your session expired. Sign in again.', undefined, { auth: 'rejected' });
  }
  if (!response.ok) {
    throw error(502, 'supabase_error', `Supabase rejected the verification request (HTTP ${response.status}).`);
  }

  let payload;
  try {
    payload = await response.json();
  } catch (cause) {
    throw error(502, 'supabase_error', 'Supabase returned an unreadable user object.', cause);
  }

  const userId = String(payload?.id || '').toLowerCase();
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(userId)) {
    throw error(401, 'invalid_token', 'Supabase did not return a usable user id for this session.');
  }

  const user = { userId, email: payload?.email || null };
  remember(token, user);
  return user;
}
