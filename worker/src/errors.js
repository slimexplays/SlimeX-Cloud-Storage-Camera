/**
 * Structured JSON errors.
 *
 * Every error the Worker returns has the same shape, so the Android client can show a real message
 * instead of "HTTP 500":
 *
 *   { "error": { "code": "invalid_key", "message": "…" } }
 *
 * Nothing sensitive (tokens, keys, signed URLs, bucket names) is ever included.
 */

export class HttpError extends Error {
  constructor(status, code, message, cause) {
    super(message);
    this.name = 'HttpError';
    this.status = status;
    this.code = code;
    this.cause = cause;
  }
}

export function error(status, code, message, cause, extra) {
  const instance = new HttpError(status, code, message, cause);
  if (extra) Object.assign(instance, extra);
  return instance;
}

/** Converts anything thrown into a `Response`. Unknown failures never leak their message. */
export function toResponse(cause, corsHeaders = {}) {
  const known = cause instanceof HttpError;
  const status = known ? cause.status : 500;
  const code = known ? cause.code : 'internal_error';
  const message = known
    ? cause.message
    : 'The Worker hit an unexpected error. Check the Worker logs (wrangler tail).';

  if (!known && cause) {
    // Log server-side only; the response stays generic.
    console.error('[slimex-worker] unhandled', cause && cause.stack ? cause.stack : String(cause));
  }

  return json(
    {
      error: { code, message },
      // `auth: "rejected"` lets the client tell "wrong token" apart from "network problem".
      ...(cause && cause.auth ? { auth: cause.auth } : {}),
    },
    status,
    corsHeaders,
  );
}

/** JSON response helper. */
export function json(payload, status = 200, corsHeaders = {}) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      // Never let a signed URL or user data end up in an intermediary cache.
      'x-content-type-options': 'nosniff',
      ...corsHeaders,
    },
  });
}
