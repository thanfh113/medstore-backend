package com.example.nhathuoc.routes

import com.example.nhathuoc.service.ChatService
import com.example.nhathuoc.service.SendChatMessageRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.requireInternalAccess

fun Route.internalChatRoutes() {
    val chatService = ChatService()

    authenticate("auth-jwt") {
        route("/internal/chat") {
            get("/sessions") {
                try {
                    call.requireInternalAccess()
                    val status = call.request.queryParameters["status"]?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                    val sessions = chatService.getQueueSessions(status)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = sessions,
                            message = "Internal chat sessions retrieved successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, RouteErrorResponse(e.message ?: "Invalid chat queue request"))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to get internal chat sessions: ${e.message}")
                    )
                }
            }

            get("/sessions/{id}/messages") {
                try {
                    call.requireInternalAccess()
                    val sessionId = call.parameters["id"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            RouteErrorResponse("Session ID is required")
                        )

                    val messages = chatService.getMessages(sessionId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = messages,
                            message = "Internal chat messages retrieved successfully"
                        )
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, RouteErrorResponse(e.message ?: "Chat session not found"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, RouteErrorResponse(e.message ?: "Invalid chat message request"))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to get internal chat messages: ${e.message}")
                    )
                }
            }

            post("/sessions/{id}/messages") {
                try {
                    val (principal, _) = call.requireInternalAccess()
                    val staffId = principal.getUserId()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            RouteErrorResponse("User ID not found")
                        )
                    val sessionId = call.parameters["id"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            RouteErrorResponse("Session ID is required")
                        )
                    val request = call.receive<SendChatMessageRequest>()
                    val message = chatService.saveMessage(
                        sessionId = sessionId,
                        senderId = staffId,
                        content = request.content,
                        type = request.type,
                        metadata = request.metadata
                    )
                    ChatRealtimeHub.broadcast(sessionId, message)

                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(
                            data = message,
                            message = "Internal chat message sent successfully"
                        )
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, RouteErrorResponse(e.message ?: "Chat session not found"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, RouteErrorResponse(e.message ?: "Invalid chat message request"))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, RouteErrorResponse(e.message ?: "Chat session is not active"))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to send internal chat message: ${e.message}")
                    )
                }
            }

            patch("/sessions/{id}/assign") {
                try {
                    val (principal, _) = call.requireInternalAccess()
                    val staffId = principal.getUserId()
                        ?: return@patch call.respond(
                            HttpStatusCode.Unauthorized,
                            RouteErrorResponse("User ID not found")
                        )
                    val sessionId = call.parameters["id"]
                        ?: return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            RouteErrorResponse("Session ID is required")
                        )
                    val session = chatService.assignSession(sessionId, staffId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = session,
                            message = "Chat session assigned successfully"
                        )
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, RouteErrorResponse(e.message ?: "Chat session not found"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, RouteErrorResponse(e.message ?: "Invalid chat session request"))
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, RouteErrorResponse(e.message ?: "Chat session is not active"))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to assign chat session: ${e.message}")
                    )
                }
            }

            patch("/sessions/{id}/resolve") {
                try {
                    call.requireInternalAccess()
                    val sessionId = call.parameters["id"]
                        ?: return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            RouteErrorResponse("Session ID is required")
                        )
                    val session = chatService.resolveSession(sessionId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = session,
                            message = "Chat session resolved successfully"
                        )
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, RouteErrorResponse(e.message ?: "Chat session not found"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, RouteErrorResponse(e.message ?: "Invalid chat session request"))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to resolve chat session: ${e.message}")
                    )
                }
            }
        }
    }
}
