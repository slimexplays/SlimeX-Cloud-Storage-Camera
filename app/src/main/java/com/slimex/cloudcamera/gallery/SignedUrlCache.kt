package com.slimex.cloudcamera.gallery

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small, thread-safe LRU of short-lived signed URLs.
 *
 * Supabase signed URLs expire (one hour by default), so caching them forever silently
 * breaks thumbnails. Entries are stored with their expiry and re-requested when stale.
 */
@Singleton
class SignedUrlCache @Inject constructor() {

    private data class Entry(val url: String, val expiresAtMillis: Long)

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {}

    fun get(assetId: String): String? = synchronized(lock) {
        val entry = entries[assetId] ?: return null
        if (entry.expiresAtMillis <= System.currentTimeMillis()) {
            entries.remove(assetId)
            null
        } else {
            entry.url
        }
    }

    fun put(assetId: String, url: String, ttlSeconds: Long) {
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                entries.keys.firstOrNull()?.let(entries::remove)
            }
            val safeTtl = (ttlSeconds - SAFETY_MARGIN_SECONDS).coerceAtLeast(60)
            entries[assetId] = Entry(url, System.currentTimeMillis() + safeTtl * 1_000L)
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private companion object {
        const val MAX_ENTRIES = 64
        const val SAFETY_MARGIN_SECONDS = 120L
    }
}
