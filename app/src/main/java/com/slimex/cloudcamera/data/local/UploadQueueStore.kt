package com.slimex.cloudcamera.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.slimex.cloudcamera.domain.model.UploadTask
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable storage for the upload queue.
 *
 * The whole queue is a single JSON document inside DataStore, so a write is atomic: power loss
 * can never leave a half-written task behind. Live progress is *not* persisted (it restarts at
 * zero), only states that matter for correctness — status, attempts, paths, checksum.
 */
@Singleton
class UploadQueueStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {

    private val serializer = ListSerializer(UploadTaskEntity.serializer())
    private val writeMutex = Mutex()

    suspend fun read(): List<UploadTask> {
        val raw = dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()[KEY].orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrElse { emptyList() }
            .map { it.toDomain() }
    }

    suspend fun write(tasks: List<UploadTask>) {
        writeMutex.withLock {
            val payload = json.encodeToString(serializer, tasks.map { it.toEntity() })
            dataStore.edit { prefs -> prefs[KEY] = payload }
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("upload_queue_json")
    }
}
