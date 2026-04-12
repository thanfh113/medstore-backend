package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.SyncChangesTable
import com.example.nhathuoc.database.tables.SyncCheckpointsTable
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.requireInternalAccess
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

@Serializable
private data class PushSyncChangeRequest(
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: JsonElement? = null,
    val clientMutationId: String? = null
)

@Serializable
private data class PushSyncRequest(
    val deviceId: String,
    val changes: List<PushSyncChangeRequest>
)

@Serializable
private data class PushSyncAckItem(
    val entityType: String,
    val entityId: String,
    val clientMutationId: String? = null,
    val serverVersion: Long,
    val status: String
)

@Serializable
private data class PushSyncResponse(
    val accepted: List<PushSyncAckItem>,
    val latestServerVersion: Long,
    val message: String
)

@Serializable
private data class PullSyncChangeItem(
    val id: String,
    val serverVersion: Long,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payloadJson: String? = null,
    val sourceDeviceId: String? = null,
    val createdAt: String
)

@Serializable
private data class PullSyncResponse(
    val data: List<PullSyncChangeItem>,
    val latestServerVersion: Long,
    val message: String
)

fun Route.syncRoutes() {
    route("/internal/sync") {
        authenticate("auth-jwt") {
            post("/push") {
                try {
                    val (principal, shopId) = call.requireInternalAccess()
                    val userId = principal.getUserId()
                    val request = call.receive<PushSyncRequest>()

                    if (request.deviceId.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "deviceId is required"))
                    }

                    val validOps = setOf("CREATE", "UPDATE", "DELETE")
                    val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

                    val response = transaction {
                        val maxVersionExpr = SyncChangesTable.serverVersion.max()
                        var currentVersion = SyncChangesTable
                            .select(maxVersionExpr)
                            .where { SyncChangesTable.shopId eq shopId }
                            .firstOrNull()
                            ?.get(maxVersionExpr)
                            ?: 0L

                        val accepted = mutableListOf<PushSyncAckItem>()

                        request.changes.forEach { change ->
                            val op = change.operation.trim().uppercase()
                            if (op !in validOps) {
                                throw IllegalArgumentException("Invalid operation: ${change.operation}")
                            }

                            val mutationId = change.clientMutationId?.trim()?.takeIf { it.isNotEmpty() }
                            if (mutationId != null) {
                                val existed = SyncChangesTable
                                    .selectAll()
                                    .where {
                                        (SyncChangesTable.shopId eq shopId) and
                                            (SyncChangesTable.clientMutationId eq mutationId)
                                    }
                                    .firstOrNull()

                                if (existed != null) {
                                    accepted += PushSyncAckItem(
                                        entityType = change.entityType,
                                        entityId = change.entityId,
                                        clientMutationId = mutationId,
                                        serverVersion = existed[SyncChangesTable.serverVersion],
                                        status = "DUPLICATE"
                                    )
                                    return@forEach
                                }
                            }

                            currentVersion += 1
                            SyncChangesTable.insert {
                                it[id] = UUID.randomUUID().toString()
                                it[SyncChangesTable.shopId] = shopId
                                it[entityType] = change.entityType
                                it[entityId] = change.entityId
                                it[operation] = op
                                it[payloadJson] = change.payload?.toString()
                                it[sourceDeviceId] = request.deviceId
                                it[clientMutationId] = mutationId
                                it[serverVersion] = currentVersion
                                it[createdByUserId] = userId
                                it[createdAt] = now
                            }

                            accepted += PushSyncAckItem(
                                entityType = change.entityType,
                                entityId = change.entityId,
                                clientMutationId = mutationId,
                                serverVersion = currentVersion,
                                status = "APPLIED"
                            )
                        }

                        upsertCheckpoint(shopId = shopId, deviceId = request.deviceId, pushedVersion = currentVersion, syncedAt = now)

                        PushSyncResponse(
                            accepted = accepted,
                            latestServerVersion = currentVersion,
                            message = "Push sync completed"
                        )
                    }

                    call.respond(HttpStatusCode.OK, response)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid sync request")))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Push sync failed: ${e.message}"))
                }
            }

            get("/pull") {
                try {
                    val (_, shopId) = call.requireInternalAccess()
                    val deviceId = call.request.queryParameters["deviceId"]?.trim().orEmpty()
                    val sinceVersion = call.request.queryParameters["sinceVersion"]?.toLongOrNull() ?: 0L
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 1000)

                    if (deviceId.isBlank()) {
                        return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "deviceId is required"))
                    }

                    val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    val response = transaction {
                        val rows = SyncChangesTable
                            .selectAll()
                            .where {
                                (SyncChangesTable.shopId eq shopId) and
                                    (SyncChangesTable.serverVersion greater sinceVersion)
                            }
                            .orderBy(SyncChangesTable.serverVersion, SortOrder.ASC)
                            .limit(limit)
                            .toList()

                        val latest = rows.lastOrNull()?.get(SyncChangesTable.serverVersion)
                            ?: (SyncChangesTable.run {
                                val maxVersionExpr = serverVersion.max()
                                select(maxVersionExpr)
                                .where { SyncChangesTable.shopId eq shopId }
                                .firstOrNull()
                                ?.get(maxVersionExpr)
                                    ?: sinceVersion
                            })

                        upsertCheckpoint(shopId = shopId, deviceId = deviceId, pulledVersion = latest, syncedAt = now)

                        PullSyncResponse(
                            data = rows.map {
                                PullSyncChangeItem(
                                    id = it[SyncChangesTable.id],
                                    serverVersion = it[SyncChangesTable.serverVersion],
                                    entityType = it[SyncChangesTable.entityType],
                                    entityId = it[SyncChangesTable.entityId],
                                    operation = it[SyncChangesTable.operation],
                                    payloadJson = it[SyncChangesTable.payloadJson],
                                    sourceDeviceId = it[SyncChangesTable.sourceDeviceId],
                                    createdAt = it[SyncChangesTable.createdAt].toString()
                                )
                            },
                            latestServerVersion = latest,
                            message = "Pull sync completed"
                        )
                    }

                    call.respond(HttpStatusCode.OK, response)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Pull sync failed: ${e.message}"))
                }
            }
        }
    }
}

private fun upsertCheckpoint(
    shopId: String,
    deviceId: String,
    syncedAt: kotlinx.datetime.LocalDateTime,
    pulledVersion: Long? = null,
    pushedVersion: Long? = null
) {
    val existing = SyncCheckpointsTable
        .selectAll()
        .where {
            (SyncCheckpointsTable.shopId eq shopId) and
                (SyncCheckpointsTable.deviceId eq deviceId)
        }
        .firstOrNull()

    if (existing == null) {
        SyncCheckpointsTable.insert {
            it[id] = UUID.randomUUID().toString()
            it[SyncCheckpointsTable.shopId] = shopId
            it[SyncCheckpointsTable.deviceId] = deviceId
            it[lastPulledVersion] = pulledVersion ?: 0L
            it[lastPushedVersion] = pushedVersion ?: 0L
            it[lastSyncAt] = syncedAt
            it[createdAt] = syncedAt
            it[updatedAt] = syncedAt
        }
        return
    }

    SyncCheckpointsTable.update({ SyncCheckpointsTable.id eq existing[SyncCheckpointsTable.id] }) {
        if (pulledVersion != null) {
            val current = existing[SyncCheckpointsTable.lastPulledVersion]
            it[lastPulledVersion] = maxOf(current, pulledVersion)
        }
        if (pushedVersion != null) {
            val current = existing[SyncCheckpointsTable.lastPushedVersion]
            it[lastPushedVersion] = maxOf(current, pushedVersion)
        }
        it[lastSyncAt] = syncedAt
        it[updatedAt] = syncedAt
    }
}



