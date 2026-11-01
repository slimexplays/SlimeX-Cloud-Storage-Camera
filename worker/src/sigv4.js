/**
 * AWS Signature Version 4 for Cloudflare R2, implemented on WebCrypto.
 *
 * Two flavours are used:
 *
 *   • `presign*` — query-string signatures handed to the Android app. Only `host` is signed and the
 *     payload hash is `UNSIGNED-PAYLOAD`, so the URL authorises exactly one method on exactly one
 *     object for a few minutes. This is the only thing the app ever receives.
 *   • `s3Request` — header signatures used *inside* the Worker (list / HEAD / DELETE) with the R2
 *     secret. Those never leave the Worker; responses are reduced to JSON before they are returned.
 *
 * The secret access key is read from `env` and used only for HMAC derivation — it is never logged,
 * never echoed and never placed in a response.
 */

import { error } from './errors.js';
import {
  canonicalQueryString,
  encodeKeyPath,
  amzTimestamps,
  hmacSha256,
  rfc3986,
  sha256Hex,
  toHex,
} from './crypto.js';

const ALGORITHM = 'AWS4-HMAC-SHA256';
const SERVICE = 's3';

/** R2 ignores the region but the signature still has to name one; `auto` is the documented value. */
function region(env) {
  return env.R2_REGION || 'auto';
}

/**
 * Resolves the S3 endpoint. The Android app never sees this value — it is a Worker secret.
 * `R2_S3_ENDPOINT` is optional: it defaults to the account endpoint Cloudflare documents.
 */
export function r2Endpoint(env) {
  const explicit = (env.R2_S3_ENDPOINT || '').trim();
  const base = explicit.length > 0
    ? explicit
    : `https://${env.R2_ACCOUNT_ID}.r2.cloudflarestorage.com`;
  return base.replace(/\/+$/, '');
}

/** Derived SigV4 signing key. */
async function signingKey(env, dateStamp) {
  const kDate = await hmacSha256(`AWS4${env.R2_SECRET_ACCESS_KEY}`, dateStamp);
  const kRegion = await hmacSha256(kDate, region(env));
  const kService = await hmacSha256(kRegion, SERVICE);
  return hmacSha256(kService, 'aws4_request');
}

function assertCredentials(env) {
  const missing = ['R2_ACCOUNT_ID', 'R2_ACCESS_KEY_ID', 'R2_SECRET_ACCESS_KEY', 'R2_BUCKET']
    .filter((name) => !env[name] || String(env[name]).trim().length === 0);
  if (missing.length > 0) {
    throw error(
      500,
      'worker_misconfigured',
      `The Worker is missing required secrets: ${missing.join(', ')}.`,
    );
  }
}

/** Builds the canonical request + string to sign shared by both signing modes. */
async function sign({ env, method, canonicalUri, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash }) {
  const { amzDate, dateStamp } = amzTimestamps();
  const scope = `${dateStamp}/${region(env)}/${SERVICE}/aws4_request`;

  const canonicalRequest = [
    method,
    canonicalUri,
    canonicalQuery,
    canonicalHeaders,
    signedHeaders,
    payloadHash,
  ].join('\n');

  const stringToSign = [
    ALGORITHM,
    amzDate,
    scope,
    await sha256Hex(canonicalRequest),
  ].join('\n');

  const signature = toHex(await hmacSha256(await signingKey(env, dateStamp), stringToSign));
  return { amzDate, dateStamp, scope, signature };
}

/**
 * Creates a short-lived presigned URL for one object.
 *
 * @param {object} options
 * @param {object} options.env           Worker env (secrets).
 * @param {'GET'|'PUT'} options.method   Only GET and PUT are ever signed.
 * @param {string} options.key           Object key, already validated as `{userId}/…`.
 * @param {number} options.expiresIn     Seconds until the URL stops working.
 */
export async function presignObjectUrl({ env, method, key, expiresIn }) {
  assertCredentials(env);
  if (method !== 'GET' && method !== 'PUT') {
    throw error(400, 'invalid_method', 'Only GET and PUT URLs can be presigned.');
  }

  const endpoint = new URL(r2Endpoint(env));
  const bucket = encodeKeyPath(String(env.R2_BUCKET));
  const path = `/${bucket}/${encodeKeyPath(key)}`;
  const { amzDate, dateStamp } = amzTimestamps();
  const scope = `${dateStamp}/${region(env)}/${SERVICE}/aws4_request`;

  const params = {
    'X-Amz-Algorithm': ALGORITHM,
    'X-Amz-Credential': `${env.R2_ACCESS_KEY_ID}/${scope}`,
    'X-Amz-Date': amzDate,
    'X-Amz-Expires': String(expiresIn),
    'X-Amz-SignedHeaders': 'host',
  };

  const canonicalQuery = canonicalQueryString(params);
  const canonicalHeaders = `host:${endpoint.host}\n`;
  const canonicalRequest = [
    method,
    path,
    canonicalQuery,
    canonicalHeaders,
    'host',
    'UNSIGNED-PAYLOAD',
  ].join('\n');

  const stringToSign = [ALGORITHM, amzDate, scope, await sha256Hex(canonicalRequest)].join('\n');
  const signature = toHex(await hmacSha256(await signingKey(env, dateStamp), stringToSign));

  return `${endpoint.origin}${path}?${canonicalQuery}&X-Amz-Signature=${signature}`;
}

/**
 * Performs a signed S3 request from inside the Worker.
 *
 * @param {object} options
 * @param {object} options.env
 * @param {string} options.method
 * @param {string} options.path      Raw object path starting with `/` (bucket included).
 * @param {object} [options.query]
 * @param {string} [options.body]
 */
export async function s3Request({ env, method, path, query = {}, body = '' }) {
  assertCredentials(env);
  const endpoint = new URL(r2Endpoint(env));
  const canonicalUri = `/${encodeKeyPath(path)}`;
  const canonicalQuery = canonicalQueryString(query);
  // S3 accepts `UNSIGNED-PAYLOAD` for header-signed requests; every internal call made here is a
  // GET/HEAD/DELETE with no body, so there is nothing to hash. This matches what the S3 SDKs and
  // aws4fetch do, and it keeps the Worker free of any body buffering.
  const payloadHash = body && body.length > 0 ? await sha256Hex(body) : 'UNSIGNED-PAYLOAD';

  const { amzDate } = amzTimestamps();
  const canonicalHeaders = [
    `host:${endpoint.host}`,
    `x-amz-content-sha256:${payloadHash}`,
    `x-amz-date:${amzDate}`,
    '',
  ].join('\n');
  const signedHeaders = 'host;x-amz-content-sha256;x-amz-date';

  const { scope, signature } = await sign({
    env,
    method,
    canonicalUri,
    canonicalQuery,
    canonicalHeaders,
    signedHeaders,
    payloadHash,
  });
  const signedAt = amzDate;
  const authorization =
    `${ALGORITHM} Credential=${env.R2_ACCESS_KEY_ID}/${scope}, ` +
    `SignedHeaders=${signedHeaders}, Signature=${signature}`;

  const url = `${endpoint.origin}${canonicalUri}${canonicalQuery ? `?${canonicalQuery}` : ''}`;
  return fetch(url, {
    method,
    headers: {
      authorization,
      'x-amz-content-sha256': payloadHash,
      'x-amz-date': signedAt,
    },
    body: method === 'GET' || method === 'HEAD' ? undefined : body,
  });
}

/** Convenience wrapper: `/{bucket}/{key}` for one object. */
export function objectPath(env, key) {
  return `/${env.R2_BUCKET}/${key}`;
}

/** Convenience wrapper: the bucket root, used by `/health`. */
export function bucketPath(env) {
  return `/${env.R2_BUCKET}`;
}

export { rfc3986 };
