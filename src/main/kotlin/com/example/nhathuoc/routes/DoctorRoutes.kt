package com.example.nhathuoc.routes

import com.example.nhathuoc.service.DoctorService
import com.example.nhathuoc.service.DoctorCreateRequest
import com.example.nhathuoc.service.DoctorUpdateRequest
import com.example.nhathuoc.util.getUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.doctorRoutes() {
    val doctorService = DoctorService()

    route("/doctors") {
        // ─────────────────────────────────────────────────────────
        // PUBLIC ENDPOINTS - Browse doctors
        // ─────────────────────────────────────────────────────────

        // GET /api/v1/doctors - Get all doctors with optional filtering
        get {
            try {
                val specialization = call.request.queryParameters["specialization"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                val doctors = doctorService.getAllDoctors(
                    specialization = specialization,
                    limit = limit,
                    offset = offset
                )

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "data" to doctors,
                        "message" to "Doctors retrieved successfully",
                        "count" to doctors.size
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to retrieve doctors: ${e.message}")
                )
            }
        }

        // GET /api/v1/doctors/search - Search doctors by name or specialization
        get("/search") {
            try {
                val query = call.request.queryParameters["q"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Query parameter 'q' is required"))

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

                val doctors = doctorService.searchDoctors(query, limit)

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "data" to doctors,
                        "message" to "Search results",
                        "count" to doctors.size
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Search failed: ${e.message}")
                )
            }
        }

        // GET /api/v1/doctors/specializations - Get all specializations
        get("/specializations") {
            try {
                val specializations = doctorService.getAllSpecializations()

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "data" to specializations,
                        "message" to "Specializations retrieved successfully",
                        "count" to specializations.size
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to retrieve specializations: ${e.message}")
                )
            }
        }

        // GET /api/v1/doctors/top-rated - Get top-rated doctors
        get("/top-rated") {
            try {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10

                val doctors = doctorService.getTopRatedDoctors(limit)

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "data" to doctors,
                        "message" to "Top-rated doctors retrieved successfully",
                        "count" to doctors.size
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to retrieve top-rated doctors: ${e.message}")
                )
            }
        }

        // GET /api/v1/doctors/by-specialization/:specialization - Get doctors by specialization
        get("/by-specialization/{specialization}") {
            try {
                val specialization = call.parameters["specialization"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Specialization is required"))

                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20

                val doctors = doctorService.getDoctorsBySpecialization(specialization, limit)

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "data" to doctors,
                        "message" to "Doctors retrieved successfully",
                        "count" to doctors.size
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to retrieve doctors: ${e.message}")
                )
            }
        }

        // GET /api/v1/doctors/:id - Get doctor details
        get("/{id}") {
            try {
                val doctorId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Doctor ID is required"))

                val doctor = doctorService.getDoctorById(doctorId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Doctor not found"))

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "data" to doctor,
                        "message" to "Doctor retrieved successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to retrieve doctor: ${e.message}")
                )
            }
        }

        // GET /api/v1/doctors/:id/availability - Get doctor's availability slots
        get("/{id}/availability") {
            try {
                val doctorId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Doctor ID is required"))

                val date = call.request.queryParameters["date"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Date parameter is required (YYYY-MM-DD)"))

                val slots = doctorService.getDoctorAvailability(doctorId, date)

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "data" to slots,
                        "message" to "Availability slots retrieved successfully",
                        "count" to slots.size
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
                    mapOf("error" to "Failed to retrieve availability: ${e.message}")
                )
            }
        }

        // ─────────────────────────────────────────────────────────
        // ADMIN ENDPOINTS - Manage doctors
        // ─────────────────────────────────────────────────────────

        authenticate("auth-jwt") {
            // POST /api/v1/doctors - Create new doctor (Admin only)
            post {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                    // TODO: Check if user is admin
                    val request = call.receive<DoctorCreateRequest>()

                    val doctorId = doctorService.createDoctor(request)

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to mapOf("id" to doctorId),
                            "message" to "Doctor created successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to create doctor: ${e.message}")
                    )
                }
            }

            // PUT /api/v1/doctors/:id - Update doctor (Admin only)
            put("/{id}") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                    val doctorId = call.parameters["id"]
                        ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Doctor ID is required"))

                    // TODO: Check if user is admin

                    val request = call.receive<DoctorUpdateRequest>()
                    val updated = doctorService.updateDoctor(doctorId, request)

                    if (!updated) {
                        return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Doctor not found"))
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Doctor updated successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update doctor: ${e.message}")
                    )
                }
            }

            // DELETE /api/v1/doctors/:id - Deactivate doctor (Admin only)
            delete("/{id}") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                    val doctorId = call.parameters["id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Doctor ID is required"))

                    // TODO: Check if user is admin

                    val deactivated = doctorService.deactivateDoctor(doctorId)

                    if (!deactivated) {
                        return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Doctor not found"))
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Doctor deactivated successfully")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to deactivate doctor: ${e.message}")
                    )
                }
            }
        }
    }
}
