package com.example.nhathuoc.routes

import com.example.nhathuoc.service.AiChatService
import com.example.nhathuoc.service.AiSendMessageRequest
import com.example.nhathuoc.service.CreateAiConversationRequest
import com.example.nhathuoc.util.getUserId
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.aiChatRoutes() {
    val service = AiChatService()

    authenticate("auth-jwt") {
        route("/ai-chat") {

            // POST /ai-chat/sessions — create a new AI conversation
            post("/sessions") {
                val userId = call.requireAuthUserId() ?: return@post
                val req = runCatching { call.receive<CreateAiConversationRequest>() }
                    .getOrDefault(CreateAiConversationRequest())
                try {
                    val conversation = service.createConversation(userId, req.productId)
                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(data = conversation, message = "Conversation created")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to create conversation: ${e.message}")
                    )
                }
            }

            // GET /ai-chat/sessions — list user's conversations
            get("/sessions") {
                val userId = call.requireAuthUserId() ?: return@get
                try {
                    val conversations = service.getUserConversations(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(data = conversations, message = "Conversations retrieved")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to get conversations: ${e.message}")
                    )
                }
            }

            route("/sessions/{id}") {

                // GET /ai-chat/sessions/{id}
                get {
                    val userId = call.requireAuthUserId() ?: return@get
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, RouteErrorResponse("Conversation ID required"))
                    try {
                        val conversation = service.getConversation(id, userId)
                        call.respond(
                            HttpStatusCode.OK,
                            RouteDataMessageResponse(data = conversation, message = "Conversation retrieved")
                        )
                    } catch (e: NoSuchElementException) {
                        call.respond(HttpStatusCode.NotFound, RouteErrorResponse(e.message ?: "Not found"))
                    } catch (e: IllegalAccessException) {
                        call.respond(HttpStatusCode.Forbidden, RouteErrorResponse("Access denied"))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, RouteErrorResponse("Error: ${e.message}"))
                    }
                }

                // POST /ai-chat/sessions/{id}/message
                post("/message") {
                    val userId = call.requireAuthUserId() ?: return@post
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, RouteErrorResponse("Conversation ID required"))
                    val req = runCatching { call.receive<AiSendMessageRequest>() }.getOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, RouteErrorResponse("Message body required"))
                    if (req.message.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, RouteErrorResponse("Message cannot be blank"))
                        return@post
                    }
                    try {
                        val response = service.sendMessage(id, userId, req.message.trim())
                        call.respond(
                            HttpStatusCode.OK,
                            RouteDataMessageResponse(data = response, message = "Message sent")
                        )
                    } catch (e: NoSuchElementException) {
                        call.respond(HttpStatusCode.NotFound, RouteErrorResponse(e.message ?: "Not found"))
                    } catch (e: IllegalAccessException) {
                        call.respond(HttpStatusCode.Forbidden, RouteErrorResponse("Access denied"))
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.Conflict, RouteErrorResponse(e.message ?: "Conversation inactive"))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, RouteErrorResponse("Error: ${e.message}"))
                    }
                }

                // POST /ai-chat/sessions/{id}/escalate
                post("/escalate") {
                    val userId = call.requireAuthUserId() ?: return@post
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, RouteErrorResponse("Conversation ID required"))
                    try {
                        val response = service.escalateToHuman(id, userId)
                        call.respond(
                            HttpStatusCode.OK,
                            RouteDataMessageResponse(data = response, message = "Escalated to human consultant")
                        )
                    } catch (e: NoSuchElementException) {
                        call.respond(HttpStatusCode.NotFound, RouteErrorResponse(e.message ?: "Not found"))
                    } catch (e: IllegalAccessException) {
                        call.respond(HttpStatusCode.Forbidden, RouteErrorResponse("Access denied"))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, RouteErrorResponse("Error: ${e.message}"))
                    }
                }
            }
        }
    }
}

// ── Helper to require authenticated user id ───────────────────────────────────
private suspend fun io.ktor.server.application.ApplicationCall.requireAuthUserId(): String? {
    val userId = principal<JWTPrincipal>()?.getUserId()
    if (userId.isNullOrBlank()) {
        respond(HttpStatusCode.Unauthorized, RouteErrorResponse("Authentication required"))
        return null
    }
    return userId
}
