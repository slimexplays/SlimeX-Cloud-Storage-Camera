/**
 * Small WebCrypto helpers used by the SigV4 implementation.
 *
 * Everything here runs on the Cloudflare runtime (`crypto.subtle`), so the Worker ships with no
 * dependencies at all — nothing to audit, nothing to keep up to date.
 */

const encoder = new TextEncoder();

/** Encodes a string as UTF-8 bytes. */
export function bytes(value) {
  return typeof value === 'string' ? encoder.encode(value) : value;
}

/** Lowercase hex encoding of an ArrayBuffer/Uint8Array. */
export function toHex(buffer) {
  const view = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer);
  let out = '';
  for (let i = 0; i < view.length; i += 1) {
    out += view[i].toString(16).padStart(2, '0');
  }
  return out;
}

/** SHA-256 of a string or byte array, as lowercase hex. */
export async function sha256Hex(value) {
  const digest = await crypto.subtle.digest('SHA-256', bytes(value));
  return toHex(digest);
}

/** HMAC-SHA256; `key` may be a string or raw bytes. */
export async function hmacSha256(key, value) {
  const cryptoKey = await crypto.subtle.importKey(
    'raw',
    bytes(key),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const signature = await crypto.subtle.sign('HMAC', cryptoKey, bytes(value));
  return new Uint8Array(signature);
}

/**
 * RFC 3986 encoding, which is what SigV4 canonicalisation requires.
 * `encodeURIComponent` leaves `!'()*` unescaped, so those are handled explicitly.
 */
export function rfc3986(value) {
  return encodeURIComponent(value).replace(
    /[!'()*]/g,
    (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`,
  );
}

/** Encodes an object key (`a/b/c.enc`) without destroying the `/` separators. */
export function encodeKeyPath(key) {
  return key
    .split('/')
    .filter((segment) => segment.length > 0)
    .map(rfc3986)
    .join('/');
}

/** Deterministic canonical query string (sorted by encoded key, then value). */
export function canonicalQueryString(params) {
  return Object.keys(params)
    .map((key) => [rfc3986(key), rfc3986(String(params[key]))])
    .sort((a, b) => (a[0] === b[0] ? (a[1] < b[1] ? -1 : 1) : a[0] < b[0] ? -1 : 1))
    .map(([key, value]) => `${key}=${value}`)
    .join('&');
}

/** `YYYYMMDDTHHMMSSZ` and the matching `YYYYMMDD` stamp, in UTC. */
export function amzTimestamps(date = new Date()) {
  const iso = date.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  return { amzDate: iso, dateStamp: iso.slice(0, 8) };
}
