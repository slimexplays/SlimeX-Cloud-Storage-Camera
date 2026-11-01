/**
 * Route-level tests for the media Worker.
 *
 * These run entirely offline: `fetch` is replaced with a stub that imitates Supabase's
 * `/auth/v1/user` and the R2 S3 API. Nothing is deployed, no credential is real.
 *
 *   cd worker && npm install && npm test
 */

import assert from 'node:assert/strict';
import test from 'node:test';

const USER_A = '3f7c1a9e-2b4d-4e6a-9c1d-8a2b3c4d5e6f';
const USER_B = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
const KEY_A = `${USER_A}/2026/01/9b1d2c3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e.enc`;
const GOOD_TOKEN = 'good.token.value';

const env = {
  R2_ACCOUNT_ID: 'abcdef0123456789abcdef0123456789',
  R2_ACCESS_KEY_ID: 'AKIAEXAMPLEKEYID0001',
  R2_SECRET_ACCESS_KEY: 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY',
  R2_BUCKET: 'slimex-media-private',
  SUPABASE_URL: 'https://project.supabase.co',
  SUPABASE_ANON_KEY: 'sb_publishable_test',
  MAX_UPLOAD_BYTES: '524288000',
  PRESIGN_TTL_SECONDS: '300',
};

const originalFetch = globalThis.fetch;

globalThis.fetch = async (input, init = {}) => {
  const url = typeof input === 'string' ? input : input.url;
  const method = (init.method || (typeof input === 'string' ? 'GET' : input.method) || 'GET').toUpperCase();

  if (url.startsWith('https://project.supabase.co/auth/v1/user')) {
    const auth = init.headers?.authorization || '';
    if (auth === `Bearer ${GOOD_TOKEN}`) {
      return new Response(JSON.stringify({ id: USER_A, email: 'a@example.com' }), { status: 200 });
    }
    return new Response(JSON.stringify({ error: 'bad jwt' }), { status: 401 });
  }

  if (url.includes('/slimex-media-private?') && url.includes('list-type=2')) {
    const isProbe = url.includes('max-keys=1&') || url.endsWith('max-keys=1');
    const xml = isProbe
      ? '<ListBucketResult><Name>b</Name><IsTruncated>false</IsTruncated></ListBucketResult>'
      : `<ListBucketResult>
           <IsTruncated>false</IsTruncated>
           <Contents><Key>${KEY_A}</Key><Size>1024</Size></Contents>
           <Contents><Key>${USER_A}/2026/01/aa11.thumb.enc</Key><Size>256</Size></Contents>
         </ListBucketResult>`;
    return new Response(xml, { status: 200 });
  }

  if (url.includes(`/${KEY_A}`) && method === 'HEAD') {
    return new Response(null, {
      status: 200,
      headers: {
        'content-length': '1024',
        etag: '"etag-1"',
        'last-modified': 'Mon, 12 Jan 2026 10:00:00 GMT',
      },
    });
  }
  if (url.includes('/missing') && method === 'HEAD') return new Response(null, { status: 404 });
  if (method === 'DELETE') return new Response(null, { status: 204 });

  return new Response(`not mocked: ${url}`, { status: 500 });
};

const worker = (await import('../src/index.js')).default;

async function call(method, path, { token, body } = {}) {
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (body !== undefined) headers['content-type'] = 'application/json';
  const request = new Request(`https://worker.example.workers.dev${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const response = await worker.fetch(request, env);
  const text = await response.text();
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    parsed = text;
  }
  return { status: response.status, json: parsed };
}

test.after(() => {
  globalThis.fetch = originalFetch;
});

test('/health reports a reachable private bucket and leaks no secret', async () => {
  const result = await call('GET', '/health');
  assert.equal(result.status, 200);
  assert.equal(result.json.ok, true);
  assert.equal(result.json.bucketReady, true);
  const serialised = JSON.stringify(result.json);
  assert.ok(!serialised.includes(env.R2_SECRET_ACCESS_KEY));
  assert.ok(!serialised.includes(env.R2_ACCESS_KEY_ID));
  assert.ok(!serialised.includes(env.R2_BUCKET));
});

test('protected routes reject missing or invalid tokens', async () => {
  const missing = await call('GET', '/v1/me');
  assert.equal(missing.status, 401);
  assert.equal(missing.json.error.code, 'missing_token');

  const rejected = await call('GET', '/v1/usage', { token: 'bad.token.value' });
  assert.equal(rejected.status, 401);
  assert.equal(rejected.json.error.code, 'invalid_token');
});

test('/v1/me returns the Supabase user id', async () => {
  const result = await call('GET', '/v1/me', { token: GOOD_TOKEN });
  assert.equal(result.status, 200);
  assert.equal(result.json.userId, USER_A);
});

test('object keys outside the caller prefix are refused', async () => {
  const otherUser = await call(
    'GET',
    `/v1/objects/stat?objectKey=${encodeURIComponent(`${USER_B}/2026/01/x.enc`)}`,
    { token: GOOD_TOKEN },
  );
  assert.equal(otherUser.status, 403);
  assert.equal(otherUser.json.error.code, 'forbidden');

  const traversal = await call(
    'GET',
    `/v1/downloads/authorize?objectKey=${encodeURIComponent('../../etc/passwd')}`,
    { token: GOOD_TOKEN },
  );
  assert.equal(traversal.status, 400);

  const parentSegment = await call(
    'GET',
    `/v1/downloads/authorize?objectKey=${encodeURIComponent(`${USER_A}/2026/01/ok.enc/../../x`)}`,
    { token: GOOD_TOKEN },
  );
  assert.equal(parentSegment.status, 400);
});

test('upload authorization returns a short-lived presigned PUT without the secret', async () => {
  const result = await call('POST', '/v1/uploads/authorize', {
    token: GOOD_TOKEN,
    body: { objectKey: KEY_A, sizeBytes: 1024, contentType: 'application/octet-stream' },
  });
  assert.equal(result.status, 200);
  assert.equal(result.json.objectKey, KEY_A);
  assert.equal(result.json.expiresInSeconds, 300);
  assert.ok(String(result.json.uploadUrl).includes('X-Amz-Signature='));
  assert.ok(!String(result.json.uploadUrl).includes(env.R2_SECRET_ACCESS_KEY));
});

test('upload authorization enforces size, content type and ownership', async () => {
  const oversize = await call('POST', '/v1/uploads/authorize', {
    token: GOOD_TOKEN,
    body: { objectKey: KEY_A, sizeBytes: 999_999_999, contentType: 'application/octet-stream' },
  });
  assert.equal(oversize.status, 413);
  assert.equal(oversize.json.error.code, 'file_too_large');

  const plaintext = await call('POST', '/v1/uploads/authorize', {
    token: GOOD_TOKEN,
    body: { objectKey: KEY_A, sizeBytes: 10, contentType: 'image/jpeg' },
  });
  assert.equal(plaintext.status, 415);

  const foreign = await call('POST', '/v1/uploads/authorize', {
    token: GOOD_TOKEN,
    body: { objectKey: `${USER_B}/2026/01/x.enc`, sizeBytes: 10, contentType: 'application/octet-stream' },
  });
  assert.equal(foreign.status, 403);

  const zero = await call('POST', '/v1/uploads/authorize', {
    token: GOOD_TOKEN,
    body: { objectKey: KEY_A, sizeBytes: 0, contentType: 'application/octet-stream' },
  });
  assert.equal(zero.status, 400);
});

test('usage is scoped to the caller prefix and never invents a quota', async () => {
  const result = await call('GET', '/v1/usage', { token: GOOD_TOKEN });
  assert.equal(result.status, 200);
  assert.equal(result.json.prefix, `${USER_A}/`);
  assert.equal(result.json.objectCount, 2);
  assert.equal(result.json.totalBytes, 1280);
  assert.equal(result.json.truncated, false);
  assert.equal(result.json.freeQuotaBytes, null);
});

test('download authorization and stat reflect what R2 holds', async () => {
  const download = await call(
    'GET',
    `/v1/downloads/authorize?objectKey=${encodeURIComponent(KEY_A)}`,
    { token: GOOD_TOKEN },
  );
  assert.equal(download.status, 200);
  assert.equal(download.json.sizeBytes, 1024);
  assert.ok(String(download.json.downloadUrl).includes('X-Amz-Signature='));

  const stat = await call('GET', `/v1/objects/stat?objectKey=${encodeURIComponent(KEY_A)}`, {
    token: GOOD_TOKEN,
  });
  assert.equal(stat.status, 200);
  assert.equal(stat.json.sizeBytes, 1024);
  assert.equal(stat.json.etag, 'etag-1');

  const missing = await call(
    'GET',
    `/v1/objects/stat?objectKey=${encodeURIComponent(`${USER_A}/2026/01/missing.enc`)}`,
    { token: GOOD_TOKEN },
  );
  assert.equal(missing.status, 404);
  assert.equal(missing.json.error.code, 'object_not_found');
});

test('delete removes one object and treats a missing object as done', async () => {
  const removed = await call('DELETE', `/v1/objects?objectKey=${encodeURIComponent(KEY_A)}`, {
    token: GOOD_TOKEN,
  });
  assert.equal(removed.status, 200);
  assert.equal(removed.json.deleted, true);
  assert.equal(removed.json.existed, true);

  const alreadyGone = await call(
    'DELETE',
    `/v1/objects?objectKey=${encodeURIComponent(`${USER_A}/2026/01/missing.enc`)}`,
    { token: GOOD_TOKEN },
  );
  assert.equal(alreadyGone.status, 200);
  assert.equal(alreadyGone.json.existed, false);
});

test('the operation allowlist is decided on the server', async () => {
  const restricted = { ...env, ALLOWED_OPERATIONS: 'download' };

  const upload = await worker.fetch(
    new Request('https://worker.example.workers.dev/v1/uploads/authorize', {
      method: 'POST',
      headers: { authorization: `Bearer ${GOOD_TOKEN}`, 'content-type': 'application/json' },
      body: JSON.stringify({ objectKey: KEY_A, sizeBytes: 10, contentType: 'application/octet-stream' }),
    }),
    restricted,
  );
  assert.equal(upload.status, 403);
  assert.equal((await upload.json()).error.code, 'operation_disabled');

  const download = await worker.fetch(
    new Request(
      `https://worker.example.workers.dev/v1/downloads/authorize?objectKey=${encodeURIComponent(KEY_A)}`,
      { headers: { authorization: `Bearer ${GOOD_TOKEN}` } },
    ),
    restricted,
  );
  assert.equal(download.status, 200);

  const health = await worker.fetch(new Request('https://worker.example.workers.dev/health'), restricted);
  assert.equal(health.status, 403);
});

test('unknown routes and wrong methods answer with structured JSON errors', async () => {
  const unknown = await call('GET', '/nope');
  assert.equal(unknown.status, 404);
  assert.equal(unknown.json.error.code, 'not_found');

  const wrongMethod = await call('POST', '/v1/objects', { token: GOOD_TOKEN });
  assert.equal(wrongMethod.status, 405);
});
