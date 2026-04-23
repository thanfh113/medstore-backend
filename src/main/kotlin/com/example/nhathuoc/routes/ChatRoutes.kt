package com.example.nhathuoc.routes

import com.example.nhathuoc.service.ChatService
import com.example.nhathuoc.service.CreateChatSessionRequest
import com.example.nhathuoc.service.SendChatMessageRequest
import com.example.nhathuoc.service.UpdateChatSessionStatusRequest
import com.example.nhathuoc.util.AppRoles
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.getRole
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
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json

fun Route.chatRoutes() {
    val chatService = ChatService()
    val json = Json { ignoreUnknownKeys = true }

    authenticate("auth-jwt") {
        route("/chat") {
            get("/sessions") {
                try {
                    val userId = call.requireAuthUserId() ?: return@get
                    val sessions = chatService.getUserSessions(userId)

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = sessions,
                            message = "Chat sessions retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to get chat sessions: ${e.message}")
                    )
                }
            }

            post("/sessions") {
                try {
                    val userId = call.requireAuthUserId() ?: return@post
                    val request = call.receive<CreateChatSessionRequest>()
                    val session = chatService.createOrResumeSession(userId, request.productId)

                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(
                            data = session,
                            message = "Chat session created successfully"
                        )
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        RouteErrorResponse(e.message ?: "Chat session not found")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        RouteErrorResponse(e.message ?: "Invalid chat session request")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to create chat session: ${e.message}")
                    )
                }
            }

            get("/sessions/{id}/messages") {
                try {
                    val userId = call.requireAuthUserId() ?: return@get
                    val sessionId = call.parameters["id"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            RouteErrorResponse("Session ID is required")
                        )

                    call.ensureSessionOwnership(chatService, sessionId, userId) ?: return@get

                    val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
                    val offset = call.parameters["offset"]?.toLongOrNull() ?: 0L
                    val messages = chatService.getMessages(sessionId, limit, offset)

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = messages,
                            message = "Chat messages retrieved successfully"
                        )
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        RouteErrorResponse(e.message ?: "Chat session not found")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        RouteErrorResponse(e.message ?: "Invalid chat message request")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to get chat messages: ${e.message}")
                    )
                }
            }

            post("/sessions/{id}/messages") {
                try {
                    val userId = call.requireAuthUserId() ?: return@post
                    val sessionId = call.parameters["id"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            RouteErrorResponse("Session ID is required")
                        )

                    call.ensureSessionOwnership(chatService, sessionId, userId) ?: return@post

                    val request = call.receive<SendChatMessageRequest>()
                    val message = chatService.saveMessage(
                        sessionId = sessionId,
                        senderId = userId,
                        content = request.content,
                        type = request.type,
                        metadata = request.metadata
                    )
                    ChatRealtimeHub.broadcast(sessionId, message)

                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(
                            data = message,
                            message = "Chat message sent successfully"
                        )
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        RouteErrorResponse(e.message ?: "Chat session not found")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        RouteErrorResponse(e.message ?: "Invalid chat message request")
                    )
                } catch (e: IllegalStateException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        RouteErrorResponse(e.message ?: "Chat session is not active")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to send chat message: ${e.message}")
                    )
                }
            }

            patch("/sessions/{id}/status") {
                try {
                    val userId = call.requireAuthUserId() ?: return@patch
                    val sessionId = call.parameters["id"]
                        ?: return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            RouteErrorResponse("Session ID is required")
                        )

                    call.ensureSessionOwnership(chatService, sessionId, userId) ?: return@patch

                    val request = call.receive<UpdateChatSessionStatusRequest>()
                    if (request.status != ChatService.SESSION_STATUS_RESOLVED) {
                        return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            RouteErrorResponse("Only RESOLVED status is supported")
                        )
                    }

                    val session = chatService.resolveSession(sessionId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = session,
                            message = "Chat session updated successfully"
                        )
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        RouteErrorResponse(e.message ?: "Chat session not found")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        RouteErrorResponse(e.message ?: "Invalid chat session request")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to update chat session: ${e.message}")
                    )
                }
            }
        }

        webSocket("/ws/chat/{sessionId}") {
            val principal = call.principal<JWTPrincipal>()
            val userId = principal?.getUserId()
            val role = principal?.getRole()
            val sessionId = call.parameters["sessionId"]

            if (userId.isNullOrBlank() || sessionId.isNullOrBlank()) {
                return@webSocket
            }

            val resolvedSessionId = sessionId

            val session = chatService.getSessionById(resolvedSessionId)
            val canAccess = session != null && (
                role in AppRoles.internalRoles || session.userId == userId
            )

            if (!canAccess) {
                return@webSocket
            }

            ChatRealtimeHub.register(resolvedSessionId, this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText().trim()
                        if (text.isBlank()) continue

                        val request = runCatching {
                            json.decodeFromString<SendChatMessageRequest>(text)
                        }.getOrElse {
                            SendChatMessageRequest(content = text)
                        }

                        val messageResult = runCatching {
                            chatService.saveMessage(
                                sessionId = resolvedSessionId,
                                senderId = userId,
                                content = request.content,
                                type = request.type,
                                metadata = request.metadata
                            )
                        }
                        if (messageResult.isFailure) {
                            continue
                        }

                        val message = messageResult.getOrThrow()
                        ChatRealtimeHub.broadcast(resolvedSessionId, message)
                    }
                }
            } finally {
                ChatRealtimeHub.unregister(resolvedSessionId, this)
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.requireAuthUserId(): String? {
    val userId = principal<JWTPrincipal>()
        ?.payload
        ?.getClaim("userId")
        ?.asString()

    if (userId.isNullOrBlank()) {
        respond(
            HttpStatusCode.Unauthorized,
            RouteErrorResponse("User ID not found")
        )
        return null
    }
    return userId
}

private suspend fun io.ktor.server.application.ApplicationCall.ensureSessionOwnership(
    chatService: ChatService,
    sessionId: String,
    userId: String
): Unit? {
    val session = chatService.getSessionById(sessionId)
        ?: return respond(
            HttpStatusCode.NotFound,
            RouteErrorResponse("Chat session not found")
        )

    if (session.userId != userId) {
        respond(
            HttpStatusCode.Forbidden,
            RouteErrorResponse("You do not have access to this chat session")
        )
        return null
    }

    return Unit
}
