package com.example.nhathuoc.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ShopSettingsRequest(
    val name: String? = null,
    val description: String? = null,
    val logoUrl: String? = null,
    val licenseNumber: String? = null,
    val expiryAlertDays: Int? = null
)

@Serializable
data class ShopSettingsResponse(
    val id: String,
    val name: String,
    val description: String? = null,
    val logoUrl: String? = null,
    val licenseNumber: String? = null,
    val isApproved: Boolean,
    val expiryAlertDays: Int
)

private val settingsJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

private fun settingsFile(): File = File(".internal-settings.json")

private fun loadSettings(): ShopSettingsResponse {
    val file = settingsFile()
    if (!file.exists()) {
        return ShopSettingsResponse(
            id = "default-store",
            name = "Nha thuoc",
            description = "Single-store desktop configuration",
            logoUrl = null,
            licenseNumber = null,
            isApproved = true,
            expiryAlertDays = 30
        )
    }

    return runCatching {
        settingsJson.decodeFromString<ShopSettingsResponse>(file.readText())
    }.getOrElse {
        ShopSettingsResponse(
            id = "default-store",
            name = "Nha thuoc",
            description = "Single-store desktop configuration",
            logoUrl = null,
            licenseNumber = null,
            isApproved = true,
            expiryAlertDays = 30
        )
    }
}

private fun saveSettings(settings: ShopSettingsResponse) {
    settingsFile().writeText(settingsJson.encodeToString(ShopSettingsResponse.serializer(), settings))
}

fun Route.settingsRoutes() {
    authenticate("auth-jwt") {
        route("/shop/settings") {
            get {
                try {
                    call.respond(HttpStatusCode.OK, loadSettings())
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Failed to fetch settings"))
                    )
                }
            }

            put {
                try {
                    val request = call.receive<ShopSettingsRequest>()

                    if (!request.name.isNullOrBlank() && request.name!!.length > 200) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Shop name too long (max 200 chars)")
                        )
                    }
                    if (request.expiryAlertDays != null && request.expiryAlertDays < 1) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Expiry alert days must be > 0")
                        )
                    }

                    val current = loadSettings()
                    val updatedSettings = current.copy(
                        name = request.name?.trim()?.ifBlank { current.name } ?: current.name,
                        description = request.description?.trim()?.ifBlank { null } ?: current.description,
                        logoUrl = request.logoUrl?.trim()?.ifBlank { null } ?: current.logoUrl,
                        licenseNumber = request.licenseNumber?.trim()?.ifBlank { null } ?: current.licenseNumber,
                        expiryAlertDays = request.expiryAlertDays ?: current.expiryAlertDays
                    )
                    saveSettings(updatedSettings)
                    call.respond(HttpStatusCode.OK, updatedSettings)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Failed to update settings"))
                    )
                }
            }
        }
    }
}

