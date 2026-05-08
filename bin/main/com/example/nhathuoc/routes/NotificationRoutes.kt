package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.NotificationsTable
import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.util.getUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

@Serializable
private data class NotificationDto(
    val id: String,
    val title: String,
    val body: String?,
    val type: String,
    val refId: String?,
    val isRead: Boolean,
    val createdAt: String
)

@Serializable
private data class NotificationReadAllResponse(
    val updatedCount: Int
)

@Serializable
private data class PushTokenRequest(
    val fcmToken: String,
    val platform: String = "ANDROID",
    val deviceId: String? = null,
    val appVersion: String? = null
)

@Serializable
private data class PushTokenResponse(
    val id: String,
    val isActive: Boolean
)

fun Route.notificationRoutes() {
    authenticate("auth-jwt") {
        route("/notifications") {
            post("/push-token") {
                try {
                    val userId = call.principal<JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, RouteErrorResponse("Unauthorized"))
                    val request = call.receive<PushTokenRequest>()
                    val token = request.fcmToken.trim()
                    if (token.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, RouteErrorResponse("FCM token is required"))
                    }

                    val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    val platform = request.platform.trim().uppercase().ifBlank { "ANDROID" }.take(20)
                    val updated = transaction {
                        UsersTable.update({ UsersTable.id eq userId }) {
                            it[UsersTable.fcmToken] = token
                            it[UsersTable.fcmPlatform] = platform
                            it[UsersTable.fcmUpdatedAt] = now
                            it[UsersTable.updatedAt] = now
                        }
                    }
                    if (updated == 0) {
                        return@post call.respond(HttpStatusCode.NotFound, RouteErrorResponse("User not found"))
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = PushTokenResponse(id = userId, isActive = true),
                            message = "Push token registered"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to register push token: ${e.message}")
                    )
                }
            }

            delete("/push-token") {
                try {
                    val userId = call.principal<JWTPrincipal>()?.getUserId()
                        ?: return@delete call.respond(HttpStatusCode.Unauthorized, RouteErrorResponse("Unauthorized"))
                    val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    val updated = transaction {
                        UsersTable.update({ UsersTable.id eq userId }) {
                            it[UsersTable.fcmToken] = null
                            it[UsersTable.fcmPlatform] = null
                            it[UsersTable.fcmUpdatedAt] = now
                            it[UsersTable.updatedAt] = now
                        }
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = NotificationReadAllResponse(updatedCount = updated),
                            message = "Push token deactivated"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to deactivate push token: ${e.message}")
                    )
                }
            }

            get {
                try {
                    val userId = call.principal<JWTPrincipal>()?.getUserId()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                    val unreadOnly = call.parameters["unreadOnly"]?.toBooleanStrictOrNull() ?: false
                    val limit = call.parameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50

                    val notifications = transaction {
                        NotificationsTable
                            .selectAll()
                            .where {
                                (NotificationsTable.userId eq userId) and
                                    if (unreadOnly) {
                                        NotificationsTable.isRead eq false
                                    } else {
                                        NotificationsTable.id.isNotNull()
                                    }
                            }
                            .orderBy(NotificationsTable.createdAt to SortOrder.DESC)
                            .limit(limit)
                            .map { row ->
                                NotificationDto(
                                    id = row[NotificationsTable.id],
                                    title = row[NotificationsTable.title],
                                    body = row[NotificationsTable.body],
                                    type = row[NotificationsTable.type],
                                    refId = row[NotificationsTable.refId],
                                    isRead = row[NotificationsTable.isRead],
                                    createdAt = row[NotificationsTable.createdAt].toString()
                                )
                            }
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = notifications,
                            message = "Notifications retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to get notifications: ${e.message}")
                    )
                }
            }

            put("/{id}/read") {
                try {
                    val userId = call.principal<JWTPrincipal>()?.getUserId()
                        ?: return@put call.respond(HttpStatusCode.Unauthorized, RouteErrorResponse("Unauthorized"))
                    val notificationId = call.parameters["id"]
                        ?: return@put call.respond(HttpStatusCode.BadRequest, RouteErrorResponse("Notification ID is required"))

                    val updated = transaction {
                        NotificationsTable.update({
                            (NotificationsTable.id eq notificationId) and
                                (NotificationsTable.userId eq userId)
                        }) {
                            it[isRead] = true
                        }
                    }

                    if (updated == 0) {
                        return@put call.respond(HttpStatusCode.NotFound, RouteErrorResponse("Notification not found"))
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = NotificationReadAllResponse(updatedCount = updated),
                            message = "Notification marked as read"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to mark notification as read: ${e.message}")
                    )
                }
            }

            post("/{id}/read") {
                try {
                    val userId = call.principal<JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, RouteErrorResponse("Unauthorized"))
                    val notificationId = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, RouteErrorResponse("Notification ID is required"))

                    val updated = transaction {
                        NotificationsTable.update({
                            (NotificationsTable.id eq notificationId) and
                                (NotificationsTable.userId eq userId)
                        }) {
                            it[isRead] = true
                        }
                    }

                    if (updated == 0) {
                        return@post call.respond(HttpStatusCode.NotFound, RouteErrorResponse("Notification not found"))
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = NotificationReadAllResponse(updatedCount = updated),
                            message = "Notification marked as read"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to mark notification as read: ${e.message}")
                    )
                }
            }

            put("/read-all") {
                try {
                    val userId = call.principal<JWTPrincipal>()?.getUserId()
                        ?: return@put call.respond(HttpStatusCode.Unauthorized, RouteErrorResponse("Unauthorized"))

                    val updated = transaction {
                        NotificationsTable.update({
                            (NotificationsTable.userId eq userId) and (NotificationsTable.isRead eq false)
                        }) {
                            it[isRead] = true
                        }
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = NotificationReadAllResponse(updatedCount = updated),
                            message = "All notifications marked as read"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to mark all notifications as read: ${e.message}")
                    )
                }
            }

            post("/read-all") {
                try {
                    val userId = call.principal<JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, RouteErrorResponse("Unauthorized"))

                    val updated = transaction {
                        NotificationsTable.update({
                            (NotificationsTable.userId eq userId) and (NotificationsTable.isRead eq false)
                        }) {
                            it[isRead] = true
                        }
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = NotificationReadAllResponse(updatedCount = updated),
                            message = "All notifications marked as read"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to mark all notifications as read: ${e.message}")
                    )
                }
            }
        }
    }
}

