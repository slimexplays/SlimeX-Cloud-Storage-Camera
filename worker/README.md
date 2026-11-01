# SlimeX media Worker

Cloudflare Worker that brokers access to the private Cloudflare R2 bucket used by the
SlimeX Cloud Camera Android app.

- The app authenticates with a **Supabase** access token.
- The Worker verifies that token against Supabase, derives the allowed prefix (`{userId}/`) and
  returns **short-lived presigned URLs** so the phone streams ciphertext straight to R2.
- The R2 account id, access key, secret key and bucket name exist only as **Worker secrets**.
  They are never sent to the app, never logged, and must never be committed.

Full deployment guide, API reference, limits and troubleshooting:
**[`../docs/CLOUDFLARE_R2_WORKER_SETUP.md`](../docs/CLOUDFLARE_R2_WORKER_SETUP.md)**

## Files

| File | Purpose |
| --- | --- |
| `src/index.js` | routes: `/health`, `/v1/me`, `/v1/usage`, `/v1/uploads/authorize`, `/v1/downloads/authorize`, `/v1/objects/stat`, `DELETE /v1/objects` |
| `src/sigv4.js` | AWS Signature V4 on WebCrypto (presigned URLs + internal S3 calls) |
| `src/crypto.js` | SHA-256 / HMAC helpers, RFC 3986 encoding, canonical query strings |
| `src/auth.js` | Supabase JWT verification via `/auth/v1/user` (60 s isolate cache) |
| `src/validation.js` | object-key, size and content-type rules |
| `src/ratelimit.js` | per-isolate fixed-window limiter |
| `src/errors.js` | structured `{ "error": { "code", "message" } }` responses |
| `wrangler.toml` | non-secret configuration only |
| `.dev.vars.example` | placeholders for local `wrangler dev` |
| `test/` | offline tests (route behaviour + SigV4 parity with `aws4fetch`) |

## Quick start

```bash
npm install
cp .dev.vars.example .dev.vars     # fill in placeholders, never commit this
npm test                           # 13 tests, no network
npx wrangler secret put R2_ACCOUNT_ID      # …repeat for every secret in the guide
npx wrangler deploy
curl -s https://<your-worker>.workers.dev/health
```

## Guarantees

- No response ever contains a credential, the bucket name, or a URL for an object you do not own.
- Only `GET`/`PUT` presigned URLs are ever produced, valid for 5 minutes (max 15).
- Only `application/octet-stream` uploads are authorized — plaintext media cannot be stored.
- Only keys shaped `{userId}/{yyyy}/{MM}/{file}.enc` are accepted, and the `{userId}` must match the
  verified Supabase user.
