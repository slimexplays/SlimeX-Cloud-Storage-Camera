/**
 * SlimeX media Worker — the only component that knows how to talk to the private R2 bucket.
 *
 * Responsibilities
 *   • verify the Supabase session on every protected call,
 *   • decide what the caller is allowed to touch (their own `{userId}/` prefix, nothing else),
 *   • hand out short-lived presigned URLs so the phone can stream ciphertext straight to R2,
 *   • answer "what is actually stored" questions (usage, size, existence) from R2 itself,
 *   • delete a single object on request.
 *
 * Non-responsibilities, on purpose
 *   • it never sees plaintext media — the app encrypts before upload,
 *   • it never returns an R2 credential, a bucket name or a long-lived URL,
 *   • it never trusts a client-supplied bucket, path, key prefix or content type.
 *
 * Routes (all JSON):
 *   GET    /health                     public liveness + bucket readiness
 *   GET    /v1/me                      validates the Supabase JWT
 *   GET    /v1/usage                   verified bytes/count under the caller's prefix
 *   POST   /v1/uploads/authorize       { objectKey, sizeBytes, contentType } → { uploadUrl, … }
 *   GET    /v1/downloads/authorize     ?objectKey=… → { downloadUrl, sizeBytes, … }
 *   GET    /v1/objects/stat            ?objectKey=… → { objectKey, sizeBytes, etag, uploadedAt }
 *   DELETE /v1/objects                 ?objectKey=… → { deleted: true }
 */

import { authenticate } from './auth.js';
import { error, json, toResponse } from './errors.js';
import { rateLimit } from './ratelimit.js';
import {
  bucketPath,
  objectPath,
  presignObjectUrl,
  r2Endpoint,
  s3Request,
} from './sigv4.js';
import {
  maxUploadBytes,
  presignTtlSeconds,
  requireObjectKey,
  requireUploadRequest,
} from './validation.js';

const VERSION = '4.0.0';
const LIST_PAGE_SIZE = 1000;
const LIST_MAX_PAGES = 10;

/**
 * Server-side allowlist of media operations.
 *
 * `ALLOWED_OPERATIONS` (comma separated: upload, download, stat, delete, usage, health) is a Worker
 * variable, so the set of things this deployment will do is decided on the server — never by the
 * client. Empty means "all supported operations".
 */
function operationAllowed(env, operation) {
  const raw = String(env.ALLOWED_OPERATIONS || '').trim();
  if (raw.length === 0) return true;
  return raw
    .split(',')
    .map((value) => value.trim().toLowerCase())
    .filter((value) => value.length > 0)
    .includes(operation);
}

function requireOperation(env, operation) {
  if (!operationAllowed(env, operation)) {
    throw error(
      403,
      'operation_disabled',
      `This Worker is not allowed to perform "${operation}" operations.`,
    );
  }
}

export default {
  async fetch(request, env) {
    const cors = corsHeaders(request, env);

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: cors });
    }

    try {
      const url = new URL(request.url);
      const path = url.pathname.replace(/\/+$/, '') || '/';

      if (matches(path, '/health')) return await handleHealth(request, env, cors);
      if (matches(path, '/v1/me')) return await handleMe(request, env, cors);
      if (matches(path, '/v1/usage')) return await handleUsage(request, env, cors);
      if (matches(path, '/v1/uploads/authorize')) return await handleAuthorizeUpload(request, env, cors);
      if (matches(path, '/v1/downloads/authorize')) return await handleAuthorizeDownload(request, env, cors);
      if (matches(path, '/v1/objects/stat')) return await handleStat(request, env, cors);
      if (matches(path, '/v1/objects')) return await handleDelete(request, env, cors);

      throw error(404, 'not_found', 'Unknown route. See the Worker README for the API surface.');
    } catch (cause) {
      const response = toResponse(cause, cors);
      if (cause && cause.retryAfter) {
        response.headers.set('retry-after', String(cause.retryAfter));
      }
      return response;
    }
  },
};

/**
 * Tolerant route matching: the app appends its path to whatever base URL the user saved, so a
 * Worker mounted under a path prefix (`/media/health`) still works.
 */
function matches(path, suffix) {
  return path === suffix || path.endsWith(suffix);
}

// --------------------------------------------------------------------- routes

/** Public: no user data, just "is this Worker alive and can it reach the bucket?". */
async function handleHealth(request, env, cors) {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    throw error(405, 'method_not_allowed', 'Use GET /health.');
  }

  requireOperation(env, 'health');
  const started = Date.now();
  let bucketReady = false;
  let detail;

  if (!configured(env)) {
    detail = 'Worker secrets are incomplete. Set R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, ' +
      'R2_SECRET_ACCESS_KEY, R2_BUCKET, SUPABASE_URL and SUPABASE_ANON_KEY.';
  } else {
    try {
      const response = await s3Request({
        env,
        method: 'GET',
        path: bucketPath(env),
        query: { 'list-type': '2', 'max-keys': '1' },
      });
      bucketReady = response.ok;
      if (!response.ok) detail = `R2 answered HTTP ${response.status} for the bucket listing.`;
    } catch (cause) {
      detail = 'The Worker could not reach R2. Check the R2 secrets and the S3 endpoint.';
    }
  }

  return json(
    {
      ok: true,
      service: 'slimex-media-worker',
      version: VERSION,
      provider: env.PROVIDER_NAME || 'Cloudflare R2',
      bucketReady,
      maxUploadBytes: maxUploadBytes(env),
      presignTtlSeconds: presignTtlSeconds(env),
      operations: operationList(env),
      latencyMs: Date.now() - started,
      ...(detail ? { detail } : {}),
    },
    200,
    cors,
  );
}

/** Validates the bearer token only; the app uses it to prove the Worker accepts the session. */
async function handleMe(request, env, cors) {
  if (request.method !== 'GET') throw error(405, 'method_not_allowed', 'Use GET /v1/me.');
  const user = await authenticate(request, env);
  rateLimit(user.userId, 'read');
  return json({ ok: true, userId: user.userId, email: user.email }, 200, cors);
}

/** Verified usage: the Worker lists only the caller's own prefix. */
async function handleUsage(request, env, cors) {
  if (request.method !== 'GET') throw error(405, 'method_not_allowed', 'Use GET /v1/usage.');
  const user = await authenticate(request, env);
  rateLimit(user.userId, 'read');
  requireOperation(env, 'usage');

  const prefix = `${user.userId}/`;
  let objectCount = 0;
  let totalBytes = 0;
  let truncated = false;
  let token;

  for (let page = 0; page < LIST_MAX_PAGES; page += 1) {
    const query = {
      'list-type': '2',
      prefix,
      'max-keys': String(LIST_PAGE_SIZE),
    };
    if (token) query['continuation-token'] = token;

    const response = await s3Request({ env, method: 'GET', path: bucketPath(env), query });
    if (!response.ok) {
      throw error(
        response.status === 403 ? 403 : 502,
        'r2_list_failed',
        `R2 could not list your objects (HTTP ${response.status}).`,
      );
    }
    const xml = await response.text();
    const parsed = parseListXml(xml);
    objectCount += parsed.objects.length;
    totalBytes += parsed.objects.reduce((sum, object) => sum + object.size, 0);

    if (!parsed.truncated) {
      truncated = false;
      token = undefined;
      break;
    }
    token = parsed.token;
    truncated = true;
    if (!token) break;
  }

  return json(
    {
      ok: true,
      provider: env.PROVIDER_NAME || 'Cloudflare R2',
      prefix,
      objectCount,
      totalBytes,
      truncated,
      // R2 publishes no per-bucket quota through the S3 API; the app shows "not reported".
      freeQuotaBytes: null,
      checkedAt: new Date().toISOString(),
    },
    200,
    cors,
  );
}

/** Short-lived PUT URL for exactly one object, after checking ownership and size. */
async function handleAuthorizeUpload(request, env, cors) {
  if (request.method !== 'POST') {
    throw error(405, 'method_not_allowed', 'Use POST /v1/uploads/authorize.');
  }
  const user = await authenticate(request, env);
  rateLimit(user.userId, 'authorize');
  requireOperation(env, 'upload');

  const body = await readJson(request);
  const { objectKey, sizeBytes, contentType } = requireUploadRequest(body, user.userId, env);
  const expiresInSeconds = presignTtlSeconds(env);

  const uploadUrl = await presignObjectUrl({
    env,
    method: 'PUT',
    key: objectKey,
    expiresIn: expiresInSeconds,
  });

  return json(
    {
      ok: true,
      objectKey,
      uploadUrl,
      expiresInSeconds,
      maxUploadBytes: maxUploadBytes(env),
      contentType,
      // Proof that the URL was signed for this size, so the app can fail fast on a change.
      expectedBytes: sizeBytes,
    },
    200,
    cors,
  );
}

/** Short-lived GET URL for exactly one object the caller owns. */
async function handleAuthorizeDownload(request, env, cors) {
  if (request.method !== 'GET') {
    throw error(405, 'method_not_allowed', 'Use GET /v1/downloads/authorize.');
  }
  const user = await authenticate(request, env);
  rateLimit(user.userId, 'authorize');
  requireOperation(env, 'download');

  const url = new URL(request.url);
  const objectKey = requireObjectKey(url.searchParams.get('objectKey'), user.userId);

  const head = await headObject(env, objectKey);
  if (!head) throw error(404, 'object_not_found', 'That file is not in the bucket.');

  const expiresInSeconds = presignTtlSeconds(env);
  const downloadUrl = await presignObjectUrl({
    env,
    method: 'GET',
    key: objectKey,
    expiresIn: expiresInSeconds,
  });

  return json(
    {
      ok: true,
      objectKey,
      downloadUrl,
      sizeBytes: head.sizeBytes,
      expiresInSeconds,
    },
    200,
    cors,
  );
}

/** HEAD one object and return its stored size/etag/date, or 404. */
async function handleStat(request, env, cors) {
  if (request.method !== 'GET') throw error(405, 'method_not_allowed', 'Use GET /v1/objects/stat.');
  const user = await authenticate(request, env);
  rateLimit(user.userId, 'read');
  requireOperation(env, 'stat');

  const url = new URL(request.url);
  const objectKey = requireObjectKey(url.searchParams.get('objectKey'), user.userId);

  const head = await headObject(env, objectKey);
  if (!head) throw error(404, 'object_not_found', 'The object is not in the bucket yet.');

  return json({ ok: true, objectKey, ...head }, 200, cors);
}

/** Deletes exactly one object. A missing object counts as deleted. */
async function handleDelete(request, env, cors) {
  if (request.method !== 'DELETE') {
    throw error(405, 'method_not_allowed', 'Use DELETE /v1/objects?objectKey=…');
  }
  const user = await authenticate(request, env);
  rateLimit(user.userId, 'write');
  requireOperation(env, 'delete');

  const url = new URL(request.url);
  const objectKey = requireObjectKey(url.searchParams.get('objectKey'), user.userId);

  const existing = await headObject(env, objectKey);
  if (!existing) {
    return json({ ok: true, deleted: true, existed: false, objectKey }, 200, cors);
  }

  const response = await s3Request({ env, method: 'DELETE', path: objectPath(env, objectKey) });
  if (!response.ok && response.status !== 404) {
    throw error(502, 'r2_delete_failed', `R2 could not delete the object (HTTP ${response.status}).`);
  }
  // R2 answers 204 for a delete; 404 means someone else removed it first, which is fine.
  return json({ ok: true, deleted: true, existed: true, objectKey }, 200, cors);
}

// -------------------------------------------------------------------- helpers

async function headObject(env, objectKey) {
  const response = await s3Request({ env, method: 'HEAD', path: objectPath(env, objectKey) });
  if (response.status === 404) return null;
  if (!response.ok) {
    throw error(502, 'r2_head_failed', `R2 could not answer for that object (HTTP ${response.status}).`);
  }
  const length = Number(response.headers.get('content-length') || '0');
  return {
    sizeBytes: Number.isFinite(length) ? length : 0,
    etag: (response.headers.get('etag') || '').replace(/"/g, '') || null,
    uploadedAt: response.headers.get('last-modified') || null,
    contentType: response.headers.get('content-type') || 'application/octet-stream',
  };
}

async function readJson(request) {
  const text = await request.text();
  if (!text || text.length > 8_192) {
    throw error(400, 'invalid_body', 'Expected a small JSON body.');
  }
  try {
    return JSON.parse(text);
  } catch {
    throw error(400, 'invalid_body', 'The request body is not valid JSON.');
  }
}

function operationList(env) {
  const raw = String(env.ALLOWED_OPERATIONS || '').trim();
  if (raw.length === 0) return ['upload', 'download', 'stat', 'delete', 'usage', 'health'];
  return raw
    .split(',')
    .map((value) => value.trim().toLowerCase())
    .filter((value) => value.length > 0);
}

function configured(env) {
  const required = [
    'R2_ACCOUNT_ID',
    'R2_ACCESS_KEY_ID',
    'R2_SECRET_ACCESS_KEY',
    'R2_BUCKET',
    'SUPABASE_URL',
    'SUPABASE_ANON_KEY',
  ];
  return required.every((name) => typeof env[name] === 'string' && env[name].trim().length > 0);
}

/** Minimal XML reader for ListObjectsV2 — only the fields this Worker needs. */
function parseListXml(xml) {
  const objects = [];
  const blockPattern = /<Contents>([\s\S]*?)<\/Contents>/g;
  let match = blockPattern.exec(xml);
  while (match !== null) {
    const block = match[1];
    const key = xmlTag(block, 'Key');
    const size = Number(xmlTag(block, 'Size') || '0');
    if (key) objects.push({ key, size: Number.isFinite(size) ? size : 0 });
    match = blockPattern.exec(xml);
  }
  return {
    objects,
    truncated: /<IsTruncated>\s*true\s*<\/IsTruncated>/i.test(xml),
    token: xmlTag(xml, 'NextContinuationToken'),
  };
}

function xmlTag(xml, tag) {
  const match = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(xml);
  if (!match) return '';
  return match[1]
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&');
}

/**
 * CORS is optional: the Android app does not need it. It exists so the Worker can be probed from a
 * browser during deployment, and it only answers origins the operator listed in ALLOWED_ORIGINS.
 */
function corsHeaders(request, env) {
  const origin = request.headers.get('origin');
  if (!origin) return {};
  const allowed = String(env.ALLOWED_ORIGINS || '')
    .split(',')
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
  if (!allowed.includes(origin)) return {};
  return {
    'access-control-allow-origin': origin,
    'access-control-allow-methods': 'GET, POST, DELETE, OPTIONS',
    'access-control-allow-headers': 'authorization, content-type',
    'access-control-max-age': '600',
    vary: 'origin',
  };
}

// Exported for tests / tooling that want to inspect configuration without deploying.
export { r2Endpoint, VERSION };
