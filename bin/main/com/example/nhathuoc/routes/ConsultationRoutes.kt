package com.example.nhathuoc.routes

import com.example.nhathuoc.util.getUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * CONSULTATION ROUTES - STUB IMPLEMENTATION
 *
 * ⚠️ TIER 3: INCOMPLETE - Services exist but not fully compatible
 * ConsultationService has data model mismatches with Exposed ORM.
 * This is a placeholder until service is fully implemented.
 */
fun Route.consultationRoutes() {
    authenticate("auth-jwt") {
        route("/consultations") {
            // GET /api/v1/consultations - Placeholder
            get {
                try {
                    val userId = call.principal<JWTPrincipal>()?.getUserId()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to emptyList<Any>(),
                            "message" to "Consultations endpoint not yet available",
                            "status" to "TIER_3_INCOMPLETE"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed: ${e.message}")
                    )
                }
            }

            // POST /api/v1/consultations - Placeholder (booking)
            post {
                call.respond(
                    HttpStatusCode.NotImplemented,
                    mapOf(
                        "error" to "Consultation booking not yet available",
                        "status" to "TIER_3_INCOMPLETE",
                        "message" to "Backend service contains ORM compatibility issues. Coming soon."
                    )
                )
            }

            // GET /api/v1/consultations/{id} - Placeholder (details)
            get("/{id}") {
                call.respond(
                    HttpStatusCode.NotImplemented,
                    mapOf(
                        "error" to "Consultation details not yet available",
                        "status" to "TIER_3_INCOMPLETE"
                    )
                )
            }
        }
    }
}
