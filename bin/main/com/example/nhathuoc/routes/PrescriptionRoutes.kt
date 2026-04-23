package com.example.nhathuoc.routes

import com.example.nhathuoc.service.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.prescriptionRoutes() {
    val prescriptionService = PrescriptionService()

    authenticate("auth-jwt") {
        route("/prescriptions") {
            // GET /api/v1/prescriptions - Get user's prescriptions
            get {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val prescriptions = prescriptionService.getUserPrescriptions(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to prescriptions,
                            "message" to "Prescriptions retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get prescriptions: ${e.message}")
                    )
                }
            }

            // POST /api/v1/prescriptions - Upload new prescription
            post {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val request = call.receive<UploadPrescriptionRequest>()
                    val prescriptionId = prescriptionService.uploadPrescription(userId, request)

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to mapOf("prescriptionId" to prescriptionId),
                            "message" to "Prescription uploaded successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to upload prescription: ${e.message}")
                    )
                }
            }

            // GET /api/v1/prescriptions/summary - Get prescription summary
            get("/summary") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val summary = prescriptionService.getPrescriptionSummary(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to summary,
                            "message" to "Prescription summary retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get prescription summary: ${e.message}")
                    )
                }
            }

            // GET /api/v1/prescriptions/{id} - Get specific prescription
            get("/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val prescriptionId = call.parameters["id"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Prescription ID is required")
                        )

                    val prescription = prescriptionService.getPrescriptionById(userId, prescriptionId)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Prescription not found")
                        )

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to prescription,
                            "message" to "Prescription retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get prescription: ${e.message}")
                    )
                }
            }

            // POST /api/v1/prescriptions/{id}/link-order - Link prescription to order
            post("/{id}/link-order") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val prescriptionId = call.parameters["id"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Prescription ID is required")
                        )

                    val request = call.receive<LinkPrescriptionToOrderRequest>()
                    prescriptionService.linkPrescriptionToOrder(userId, prescriptionId, request)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Prescription linked to order successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to link prescription to order: ${e.message}")
                    )
                }
            }

            // DELETE /api/v1/prescriptions/{id} - Delete prescription (pending only)
            delete("/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@delete call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val prescriptionId = call.parameters["id"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Prescription ID is required")
                        )

                    val deleted = prescriptionService.deletePrescription(userId, prescriptionId)
                    if (deleted) {
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf("message" to "Prescription deleted successfully")
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Can only delete pending prescriptions")
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to delete prescription: ${e.message}")
                    )
                }
            }
        }
    }
}
