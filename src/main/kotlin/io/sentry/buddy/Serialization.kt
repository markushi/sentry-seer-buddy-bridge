package io.sentry.buddy

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

/**
 * The `Json` of the HTTP API. `encodeDefaults` is on so that a field that holds its default —
 * `status`, `severity`, `resolvable` — is present in the answer and the app does not have to infer
 * it from an absent key.
 */
val appJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(appJson)
    }
}
