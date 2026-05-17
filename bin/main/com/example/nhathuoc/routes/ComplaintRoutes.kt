package com.example.nhathuoc.routes

import com.example.nhathuoc.service.AddComplaintAttachmentsRequest
import com.example.nhathuoc.service.ComplaintMessageRequest
import com.example.nhathuoc.service.ComplaintService
import com.example.nhathuoc.service.CreateComplaintRequest
import com.example.nhathuoc.service.UpdateComplaintRequest
import com.example.nhathuoc.util.getRole
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.requireInternalAccess
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.complaintRoutes() {
    val complaintService = ComplaintService()

    authenticate("auth-jwt") {
        route("/complaints") {
            get {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                call.respond(
                    HttpStatusCode.OK,
                    RouteDataMessageResponse(
                        data = complaintService.listUserComplaints(userId),
                        message = "Complaints retrieved successfully"
                    )
                )
            }

            post {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val request = call.receive<CreateComplaintRequest>()

                try {
                    val complaint = complaintService.createComplaint(userId, request)
                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(
                            data = complaint,
                            message = "Complaint created successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }

            get("/{id}") {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val complaintId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Complaint ID is required"))
                val complaint = complaintService.getComplaintForUser(complaintId, userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Complaint not found"))

                call.respond(
                    HttpStatusCode.OK,
                    RouteDataMessageResponse(
                        data = complaint,
                        message = "Complaint retrieved successfully"
                    )
                )
            }

            post("/{id}/messages") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val userId = principal.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val complaintId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Complaint ID is required"))
                val request = call.receive<ComplaintMessageRequest>()

                try {
                    val message = complaintService.addMessage(
                        complaintId = complaintId,
                        senderUserId = userId,
                        senderRole = principal.getRole() ?: "USER",
                        request = request,
                        userScoped = true
                    )
                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(
                            data = message,
                            message = "Complaint message created successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }

            post("/{id}/attachments") {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val complaintId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Complaint ID is required"))
                val request = call.receive<AddComplaintAttachmentsRequest>()

                try {
                    val complaint = complaintService.addAttachments(complaintId, userId, request.attachments)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(data = complaint, message = "Attachments added successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }

            post("/{id}/request-refund") {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val complaintId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Complaint ID is required"))

                try {
                    val complaint = complaintService.requestRefund(complaintId, userId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(data = complaint, message = "Refund requested successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }

        route("/internal/complaints") {
            get {
                call.requireInternalAccess()
                val status = call.request.queryParameters["status"]
                val priority = call.request.queryParameters["priority"]
                val type = call.request.queryParameters["type"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                call.respond(
                    HttpStatusCode.OK,
                    RouteDataMessageResponse(
                        data = complaintService.listInternalComplaints(status, priority, type, limit),
                        message = "Internal complaints retrieved successfully"
                    )
                )
            }

            get("/{id}") {
                call.requireInternalAccess()
                val complaintId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Complaint ID is required"))
                val complaint = complaintService.getInternalComplaint(complaintId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Complaint not found"))

                call.respond(
                    HttpStatusCode.OK,
                    RouteDataMessageResponse(
                        data = complaint,
                        message = "Internal complaint retrieved successfully"
                    )
                )
            }

            patch("/{id}") {
                val principal = call.requireInternalAccess().first
                val actorUserId = principal.getUserId()
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val complaintId = call.parameters["id"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Complaint ID is required"))
                val request = call.receive<UpdateComplaintRequest>()

                try {
                    val complaint = complaintService.updateComplaint(complaintId, actorUserId, request)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = complaint,
                            message = "Complaint updated successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }

            post("/{id}/messages") {
                val principal = call.requireInternalAccess().first
                val actorUserId = principal.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val complaintId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Complaint ID is required"))
                val request = call.receive<ComplaintMessageRequest>()

                try {
                    val message = complaintService.addMessage(
                        complaintId = complaintId,
                        senderUserId = actorUserId,
                        senderRole = principal.getRole() ?: "EMPLOYEE",
                        request = request,
                        userScoped = false
                    )
                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(
                            data = message,
                            message = "Complaint message created successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }
    }
}
