/**
 * SigV4 parity test.
 *
 * The Worker signs its own requests (Cloudflare Workers have no AWS SDK available by default), so
 * the signature implementation is compared byte-for-byte against `aws4fetch`, the reference client
 * Cloudflare's own examples use.
 *
 * If `aws4fetch` is not installed (no `npm install` in `worker/`), the test is skipped instead of
 * failing — the Worker itself has zero runtime dependencies.
 *
 *   cd worker && npm install && npm test
 */

import assert from 'node:assert/strict';
import test from 'node:test';

const FIXED_TIME = Date.parse('2026-01-15T10:20:30Z');
const RealDate = Date;

globalThis.Date = class extends RealDate {
  constructor(...args) {
    if (args.length === 0) super(FIXED_TIME);
    else super(...args);
  }

  static now() {
    return FIXED_TIME;
  }
};

const env = {
  R2_ACCOUNT_ID: 'abcdef0123456789abcdef0123456789',
  R2_ACCESS_KEY_ID: 'AKIAEXAMPLEKEYID0001',
  R2_SECRET_ACCESS_KEY: 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY',
  R2_BUCKET: 'slimex-media-private',
};

const { presignObjectUrl, s3Request } = await import('../src/sigv4.js');

const aws4fetch = await import('aws4fetch').catch(() => null);
const skip = aws4fetch === null ? 'aws4fetch is not installed (run npm install in worker/)' : false;

test.after(() => {
  globalThis.Date = RealDate;
});

const KEY = '3f7c1a9e-2b4d-4e6a-9c1d-8a2b3c4d5e6f/2026/01/9b1d2c3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e.enc';

test('presigned PUT matches aws4fetch signQuery', { skip }, async () => {
  const ours = new URL(await presignObjectUrl({ env, method: 'PUT', key: KEY, expiresIn: 300 }));

  const client = new aws4fetch.AwsClient({
    accessKeyId: env.R2_ACCESS_KEY_ID,
    secretAccessKey: env.R2_SECRET_ACCESS_KEY,
    service: 's3',
    region: 'auto',
  });
  const reference = new URL(
    `https://${env.R2_ACCOUNT_ID}.r2.cloudflarestorage.com/${env.R2_BUCKET}/${KEY}`,
  );
  reference.searchParams.set('X-Amz-Expires', '300');
  const signed = await client.sign(reference.toString(), { method: 'PUT', aws: { signQuery: true } });
  const expected = new URL(signed.url);

  for (const param of [
    'X-Amz-Algorithm',
    'X-Amz-Credential',
    'X-Amz-Date',
    'X-Amz-Expires',
    'X-Amz-SignedHeaders',
    'X-Amz-Signature',
  ]) {
    assert.equal(ours.searchParams.get(param), expected.searchParams.get(param), param);
  }
});

test('header-signed list request matches aws4fetch', { skip }, async () => {
  const originalFetch = globalThis.fetch;
  let captured;
  globalThis.fetch = async (url, init) => {
    captured = { url, init };
    return new Response('', { status: 200 });
  };

  try {
    await s3Request({
      env,
      method: 'GET',
      path: `/${env.R2_BUCKET}`,
      query: { 'list-type': '2', prefix: '3f7c1a9e-2b4d-4e6a-9c1d-8a2b3c4d5e6f/', 'max-keys': '1000' },
    });
  } finally {
    globalThis.fetch = originalFetch;
  }

  const client = new aws4fetch.AwsClient({
    accessKeyId: env.R2_ACCESS_KEY_ID,
    secretAccessKey: env.R2_SECRET_ACCESS_KEY,
    service: 's3',
    region: 'auto',
  });
  const prefix = encodeURIComponent('3f7c1a9e-2b4d-4e6a-9c1d-8a2b3c4d5e6f/');
  const reference = await client.sign(
    new Request(
      `https://${env.R2_ACCOUNT_ID}.r2.cloudflarestorage.com/${env.R2_BUCKET}` +
        `?list-type=2&max-keys=1000&prefix=${prefix}`,
      { method: 'GET' },
    ),
  );

  const oursSignature = /Signature=([0-9a-f]+)/.exec(captured.init.headers.authorization)?.[1];
  const referenceSignature = /Signature=([0-9a-f]+)/.exec(reference.headers.get('authorization'))?.[1];
  assert.ok(oursSignature);
  assert.equal(oursSignature, referenceSignature);
  assert.equal(captured.init.headers['x-amz-content-sha256'], 'UNSIGNED-PAYLOAD');
});
