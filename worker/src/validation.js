/**
 * Input validation.
 *
 * The rule the whole design rests on: **the client never gets to choose where an object lives**.
 * A key must start with the caller's own `{userId}/` prefix and match the app's layout exactly
 * (`{userId}/{yyyy}/{MM}/{uuid}.enc`, optionally `….thumb.enc`). Anything else is rejected before
 * a URL is signed, so a crafted request cannot read or overwrite another user's object, a bucket
 * root, or a path the app never wrote.
 */

import { error } from './errors.js';

const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const KEY_PATTERN = new RegExp(`^(${UUID})/(\\d{4})/(0[1-9]|1[0-2])/([A-Za-z0-9._-]{1,128})$`);

/** Hard ceiling; `MAX_UPLOAD_BYTES` can only lower it, never raise it above R2's single-PUT limit. */
export const ABSOLUTE_MAX_UPLOAD_BYTES = 500 * 1024 * 1024;

export const ALLOWED_CONTENT_TYPES = ['application/octet-stream', 'binary/octet-stream'];

export function maxUploadBytes(env) {
  const configured = Number.parseInt(env.MAX_UPLOAD_BYTES ?? '', 10);
  if (!Number.isFinite(configured) || configured <= 0) return ABSOLUTE_MAX_UPLOAD_BYTES;
  return Math.min(configured, ABSOLUTE_MAX_UPLOAD_BYTES);
}

export function presignTtlSeconds(env) {
  const configured = Number.parseInt(env.PRESIGN_TTL_SECONDS ?? '', 10);
  if (!Number.isFinite(configured) || configured <= 0) return 300;
  // Short-lived means short-lived: five minutes by default, never more than fifteen.
  return Math.min(configured, 900);
}

/**
 * Validates an object key against the caller's own prefix.
 * @returns {string} the trimmed, validated key.
 */
export function requireObjectKey(rawKey, userId) {
  const key = typeof rawKey === 'string' ? rawKey.trim() : '';
  if (key.length === 0) {
    throw error(400, 'missing_object_key', 'The request did not include an objectKey.');
  }
  if (key.length > 200) {
    throw error(400, 'invalid_key', 'That objectKey is too long.');
  }
  if (key.startsWith('/') || key.includes('..') || key.includes('//') || key.includes('\\')) {
    throw error(400, 'invalid_key', 'The objectKey must be a relative path without any parent segments.');
  }

  const match = KEY_PATTERN.exec(key);
  if (!match) {
    throw error(
      400,
      'invalid_key',
      'The objectKey must look like {userId}/{yyyy}/{MM}/{file}.enc — nothing else is accepted.',
    );
  }
  if (match[1] !== userId) {
    // Deliberately the same status/shape as an unknown object: never confirm that another user's
    // object exists.
    throw error(403, 'forbidden', 'That object does not belong to your account.');
  }
  return key;
}

/** Validates the body of an upload authorization request. */
export function requireUploadRequest(body, userId, env) {
  if (!body || typeof body !== 'object') {
    throw error(400, 'invalid_body', 'Expected a JSON body with objectKey, sizeBytes and contentType.');
  }
  const objectKey = requireObjectKey(body.objectKey, userId);

  const sizeBytes = Number(body.sizeBytes);
  if (!Number.isFinite(sizeBytes) || !Number.isInteger(sizeBytes) || sizeBytes <= 0) {
    throw error(400, 'invalid_size', 'sizeBytes must be a positive integer.');
  }
  const limit = maxUploadBytes(env);
  if (sizeBytes > limit) {
    throw error(
      413,
      'file_too_large',
      `That file is larger than the ${Math.floor(limit / (1024 * 1024))} MB limit for this Worker.`,
    );
  }

  const contentType = String(body.contentType || 'application/octet-stream').trim().toLowerCase();
  if (!ALLOWED_CONTENT_TYPES.includes(contentType)) {
    // Media is always uploaded as ciphertext, so anything else means a client bug or a probe.
    throw error(415, 'invalid_content_type', 'Only encrypted media (application/octet-stream) can be uploaded.');
  }

  return { objectKey, sizeBytes, contentType };
}
