# Cloudflare R2 + Worker setup — SlimeX Cloud Camera (Part 4)

This is the guide for the media half of the app. By the end you will have:

- a **private** Cloudflare R2 bucket that holds the encrypted photos and videos,
- a **Cloudflare Worker** that is the only thing holding the R2 credentials,
- an app that talks to that Worker with your Supabase session and never sees a key or a bucket URL.

Everything stays inside Cloudflare's free/paid plans — there is no VPS, no server to patch, no
long-running process.

---

## 0. How it fits together

```
┌──────────────────────────┐
│ Android app              │
│  • AES-256-GCM encrypts  │
│  • holds Supabase JWT    │
│  • knows ONLY the        │
│    Worker URL            │
└──────────┬───────────────┘
           │  1. POST /v1/uploads/authorize   (Bearer <Supabase JWT>)
           │  2. PUT  <presigned URL>          (ciphertext, direct to R2)
           │  3. GET  /v1/objects/stat         (size/etag verification)
           ▼
┌──────────────────────────┐        ┌───────────────────────────────┐
│ Cloudflare Worker        │        │ Supabase                      │
│  secrets: R2_ACCOUNT_ID, │───────▶│  /auth/v1/user (JWT check)    │
│  R2_ACCESS_KEY_ID,       │        │  Postgres (metadata, RLS)     │
│  R2_SECRET_ACCESS_KEY,   │        └───────────────────────────────┘
│  R2_BUCKET, SUPABASE_*   │
│  signs SigV4 itself      │───────▶ Private R2 bucket (ciphertext)
└──────────────────────────┘         no public URL, no public access
```

Three rules the design depends on:

1. **The R2 secret never leaves the Worker.** The APK, the SharedPreferences store and the Supabase
   database contain no R2 credential — only the Worker's URL.
2. **The client never chooses a path.** The Worker derives the allowed prefix from the verified
   Supabase user id and rejects any key that does not start with `{userId}/`.
3. **The app never uploads plaintext.** The Worker refuses anything but
   `application/octet-stream`, and the bytes are AES-256-GCM ciphertext produced on the phone.

---

## 1. Create the bucket (private, no public access)

1. Open <https://dash.cloudflare.com> → **R2 Object Storage** → **Create bucket**.
2. Name it, for example `slimex-private-media`.
3. Location: pick the one closest to you (e.g. *Asia-Pacific* for India).
4. **Do not** enable *Public access*, and **do not** attach a custom domain or an `r2.dev`
   development URL. The bucket must be reachable only with credentials.
5. Optional but recommended: **Settings → Object lifecycle rules** → delete incomplete multipart
   uploads after 1 day (a cancelled upload should not linger).

You do not need to create any folder — keys are created on the first upload.

---

## 2. Create a scoped R2 API token

1. R2 → **Manage R2 API Tokens** → **Create API token**.
2. Permissions: **Object Read & Write**.
3. Scope it to **only the one bucket** from step 1.
4. Optionally set a TTL and a client IP filter.
5. Create it and copy — once — the three values shown:

| Value | Goes into |
| --- | --- |
| Access Key ID | Worker secret `R2_ACCESS_KEY_ID` |
| Secret Access Key | Worker secret `R2_SECRET_ACCESS_KEY` |
| (Account ID, shown on the R2 overview page) | Worker secret `R2_ACCOUNT_ID` |

> These three values must never be pasted into the app, an issue, a chat, or a commit. If one leaks,
> roll the token in the dashboard — the app keeps working because it never had them.

---

## 3. Deploy the Worker

The Worker source lives in [`worker/`](../worker). It has **no runtime dependencies** — the AWS
Signature V4 presigning is implemented on WebCrypto in
[`worker/src/sigv4.js`](../worker/src/sigv4.js).

### 3.1 Install the CLI

```bash
cd worker
npm install          # installs wrangler (dev dependency) + aws4fetch (for the parity test)
npx wrangler login   # opens the browser once
```

### 3.2 Review `wrangler.toml`

```toml
name = "slimex-media"
main = "src/index.js"
compatibility_date = "2024-11-01"

[vars]
MAX_UPLOAD_BYTES = "524288000"   # 500 MB
PRESIGN_TTL_SECONDS = "300"      # signed URLs live 5 minutes
PROVIDER_NAME = "Cloudflare R2"
ALLOWED_ORIGINS = ""             # only needed for browser probing
ALLOWED_OPERATIONS = ""          # empty = all; else e.g. "upload,download,stat,usage"
```

Everything sensitive is a **secret**, not a `[vars]` entry.

### 3.3 Set the secrets

```bash
npx wrangler secret put R2_ACCOUNT_ID
npx wrangler secret put R2_ACCESS_KEY_ID
npx wrangler secret put R2_SECRET_ACCESS_KEY
npx wrangler secret put R2_BUCKET                 # e.g. slimex-private-media
npx wrangler secret put SUPABASE_URL              # https://<ref>.supabase.co
npx wrangler secret put SUPABASE_ANON_KEY         # publishable (or legacy anon) key
```

Optional:

```bash
npx wrangler secret put R2_S3_ENDPOINT   # only for a jurisdiction endpoint (eu./fedramp.)
npx wrangler secret put R2_REGION        # defaults to "auto"
```

> **`SUPABASE_ANON_KEY` is the publishable key, never `service_role`.** The Worker only needs it to
> call `/auth/v1/user`. A `service_role` key in the Worker would give the Worker (and anything that
> can reach it) god-mode access to your database.

For local development, copy `worker/.dev.vars.example` to `worker/.dev.vars` and fill in
placeholders. `.dev.vars` is git-ignored.

### 3.4 Deploy

```bash
npx wrangler deploy
```

Wrangler prints the URL, e.g.

```
https://slimex-media.your-subdomain.workers.dev
```

That single URL is the **only** thing you will type into the app.

---

## 4. Verify the deployment

### 4.1 Public health check (no token needed)

```bash
curl -s https://slimex-media.your-subdomain.workers.dev/health | jq
```

Expected:

```json
{
  "ok": true,
  "service": "slimex-media-worker",
  "version": "4.0.0",
  "provider": "Cloudflare R2",
  "bucketReady": true,
  "maxUploadBytes": 524288000,
  "presignTtlSeconds": 300,
  "latencyMs": 41
}
```

- `bucketReady: false` with `detail` → the R2 secrets, the bucket name or the token scope is wrong.
  Run `npx wrangler tail` while retrying to see the R2 status code.
- `ok: false` or a crash → `npx wrangler tail` and check `R2_ACCOUNT_ID` / `R2_S3_ENDPOINT`.

### 4.2 Authenticated check

Sign in inside the app, then use the token from a debug session, or simply use the app:
**Settings → Cloud configuration → Cloudflare R2 → Test connection** runs all four checks
(Worker endpoint → Supabase session accepted → R2 reachable → storage access verified).

Manual version:

```bash
curl -s -H "authorization: Bearer $SUPABASE_ACCESS_TOKEN" \
  https://slimex-media.your-subdomain.workers.dev/v1/me | jq
```

```json
{ "ok": true, "userId": "3f7c1a9e-…", "email": "you@example.com" }
```

### 4.3 Negative checks worth doing once

```bash
# no token → 401
curl -s -o /dev/null -w '%{http_code}\n' https://…/v1/usage

# someone else's prefix → 403
curl -s -H "authorization: Bearer $SUPABASE_ACCESS_TOKEN" \
  "https://…/v1/objects/stat?objectKey=00000000-0000-0000-0000-000000000000/2026/01/x.enc" | jq

# plaintext content type → 415
curl -s -X POST -H "authorization: Bearer $SUPABASE_ACCESS_TOKEN" \
  -H 'content-type: application/json' \
  -d '{"objectKey":"<your-uid>/2026/01/a.enc","sizeBytes":10,"contentType":"image/jpeg"}' \
  https://…/v1/uploads/authorize | jq
```

---

## 5. API reference (what the app calls)

All responses are JSON. All errors are `{ "error": { "code": "...", "message": "..." } }`.

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/health` | none | liveness + bucket readiness (`ok`, `bucketReady`, `maxUploadBytes`, `latencyMs`) |
| `GET` | `/v1/me` | JWT | validates the Supabase session, returns `userId` |
| `GET` | `/v1/usage` | JWT | lists the caller's own prefix → `objectCount`, `totalBytes`, `truncated` |
| `POST` | `/v1/uploads/authorize` | JWT | `{objectKey, sizeBytes, contentType}` → `{uploadUrl, expiresInSeconds, maxUploadBytes}` |
| `GET` | `/v1/downloads/authorize` | JWT | `?objectKey=…` → `{downloadUrl, sizeBytes, expiresInSeconds}` |
| `GET` | `/v1/objects/stat` | JWT | `?objectKey=…` → `{sizeBytes, etag, uploadedAt}` (404 when absent) |
| `DELETE` | `/v1/objects` | JWT | `?objectKey=…` → `{deleted: true, existed: bool}` |

Error codes: `missing_token`, `invalid_token`, `forbidden`, `invalid_key`, `invalid_body`,
`invalid_size`, `invalid_content_type`, `file_too_large`, `object_not_found`, `rate_limited`,
`operation_disabled`, `r2_list_failed`, `r2_head_failed`, `r2_delete_failed`,
`worker_misconfigured`, `not_found`, `internal_error`.

**Not exposed on purpose:** there is no endpoint that returns a bucket URL, a listing of keys, a
signed URL for a key you do not own, or any configuration value.

---

## 6. Limits and what they are not

| Control | Where | Notes |
| --- | --- | --- |
| Upload size | `MAX_UPLOAD_BYTES` (500 MB cap, hard-coded ceiling) | checked before a URL is signed |
| Signed-URL lifetime | `PRESIGN_TTL_SECONDS` (≤ 900 s) | short-lived by design |
| Allowed content type | `validation.js` | `application/octet-stream` only |
| Operation allowlist | `ALLOWED_OPERATIONS` var | empty = all; otherwise a server-side subset of `upload,download,stat,delete,usage,health` |
| Key shape | `validation.js` | `{userId}/{yyyy}/{MM}/{file}.enc` and nothing else |
| Cross-user access | key prefix must equal the verified Supabase user id | 403 otherwise |
| Rate limiting | in-memory fixed window per user | **per-isolate**, so it blunts a single abusive client but is not a global guarantee. For a hard limit add a **Cloudflare WAF rate-limiting rule** on the Worker route (e.g. 120 requests/minute per IP), or move the counter into a Durable Object. |
| JWT verification | every protected route calls Supabase `/auth/v1/user` | results cached ~60 s per isolate to keep multi-object uploads cheap |

---

## 7. Operational notes

- **Rotation.** Rolling the R2 token only touches Worker secrets (`wrangler secret put …` then
  `wrangler deploy`). The app is unaffected — it never held them. Rotating the Supabase publishable
  key means updating it in the Worker **and** in the app.
- **Logs.** `npx wrangler tail` streams live logs. The Worker never logs tokens, keys, signed URLs or
  bodies; only unexpected exceptions print a stack trace.
- **Abuse.** Everything requires a valid Supabase account, so the blast radius of a leaked Worker URL
  is "can create an empty bucket listing for yourself".
- **Costs.** R2 charges storage + Class A/B operations; Workers free tier covers 100 000 requests/day.
  A phone uploading a 40 MB video uses ~4 requests.
- **Account deletion.** Delete the `{userId}/` prefix with `wrangler r2 object delete` or the
  dashboard; Supabase rows cascade when the auth user is deleted, R2 objects do not.

---

## 8. Local development

```bash
cd worker
npm install
cp .dev.vars.example .dev.vars     # fill in real values; never commit this file
npm test                           # 13 offline tests: routes + SigV4 parity with aws4fetch
npx wrangler dev                   # http://localhost:8787
```

For an emulator, the app accepts `http://10.0.2.2:8787` (plain HTTP is allowed only for
`localhost`, `127.0.0.1` and `10.0.2.2` — see `R2Validation.kt`). On a physical device use
`npx wrangler dev --remote` and the printed HTTPS URL.

---

## 9. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| App: "The Worker did not answer, so nothing was saved." | URL wrong, Worker not deployed, or route returns HTML | `curl …/health`; check the URL ends at the Worker root (no `/health`) |
| `bucketReady: false` | wrong bucket name, wrong account id, or token not scoped to the bucket | re-put `R2_BUCKET`, `R2_ACCOUNT_ID`; recreate the token scoped to that bucket |
| `invalid_token` in the app even though you are signed in | Supabase URL/anon key in the Worker do not match the project the app uses | set `SUPABASE_URL` + `SUPABASE_ANON_KEY` to the same project |
| `403 forbidden` on every stat/download | object keys are not prefixed with the Supabase user id | check `media_files.storage_path`; the app writes `{uid}/…` — old rows from another project will not match |
| Uploads fail with 403 from R2 right after authorizing | the signed URL expired (slow connection) | retry; the app re-authorizes automatically on the next WorkManager attempt |
| `file_too_large` | file over `MAX_UPLOAD_BYTES` | raise the var, redeploy (ceiling stays 500 MB) |
| `rate_limited` | too many authorizations per minute | wait; the limit is 60 authorizations/minute per user |
| Everything works but the app says "Not reported by provider" for free quota | R2 publishes no per-bucket quota over S3 | expected — the app never invents a figure |

---

## 10. Verification status (honest note)

The Worker was **not** deployed to Cloudflare from this environment, and no live R2 bucket was
contacted. What *was* verified locally (Node 22, `cd worker && npm test`):

- 11 route tests with mocked Supabase + R2: auth, ownership, traversal rejection, size/content-type
  limits, usage scoping, stat, delete, operation allowlist, structured errors;
- 2 signature-parity tests: the presigned PUT URL and the header-signed list request are
  **byte-identical** to `aws4fetch` 1.0.20, the reference implementation Cloudflare documents.

The Android side was not compiled in this environment either — see
[`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md) and [`BUILD_VERIFICATION.md`](BUILD_VERIFICATION.md).
