package com.slimex.cloudcamera.cloud

import com.slimex.cloudcamera.core.result.AppFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Shared HTTP → [AppFailure] mapping so every network error reads the same way in the UI. */
internal object CloudFailures {

    fun transport(outcome: HttpOutcome, action: String): AppFailure? {
        val error = outcome.transportError ?: return null
        return AppFailure.network(message = "Could not $action: $error")
    }

    fun auth(outcome: HttpOutcome, action: String): AppFailure = AppFailure.unauthorized(
        serverMessage(outcome, action) ?: "Supabase rejected the request (HTTP ${outcome.code}).",
    )

    fun storage(outcome: HttpOutcome, action: String): AppFailure {
        transport(outcome, action)?.let { return it }
        val server = serverMessage(outcome, action)
        return when (outcome.code) {
            401, 403 -> AppFailure.unauthorized(
                server ?: "Not allowed to $action. Check the storage policies in supabase/storage.sql.",
            )
            404 -> AppFailure.unknown(server ?: "Object or bucket not found while trying to $action.")
            409 -> AppFailure.unknown(server ?: "An object with that name already exists.")
            402, 413 -> AppFailure.storage(
                server ?: "Your Supabase storage quota or size limit was reached while trying to $action.",
            )
            else -> AppFailure.unknown(server ?: "Could not $action (HTTP ${outcome.code}).")
        }
    }

    fun database(outcome: HttpOutcome, action: String): AppFailure {
        transport(outcome, action)?.let { return it }
        val server = serverMessage(outcome, action)
        return when (outcome.code) {
            401, 403 -> AppFailure.unauthorized(
                server ?: "Not allowed to $action. Check row level security in supabase/schema.sql.",
            )
            404 -> AppFailure.unknown(
                server ?: "Table not found while trying to $action. Run supabase/schema.sql first.",
            )
            else -> AppFailure.unknown(server ?: "Could not $action (HTTP ${outcome.code}).")
        }
    }

    /** Extracts `message`, `msg`, `error_description` or `error` from a Supabase error body. */
    fun serverMessage(outcome: HttpOutcome, action: String): String? {
        val body = outcome.body?.takeIf { it.isNotBlank() } ?: return null
        val text = runCatching {
            val obj = Json.parseToJsonElement(body) as? JsonObject
            listOf("message", "msg", "error_description", "error", "hint")
                .firstNotNullOfOrNull { key ->
                    (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                }
        }.getOrNull()
        if (text != null) return text
        return body.takeIf { it.length <= 180 }?.let { "Could not $action: $it" }
    }
}
