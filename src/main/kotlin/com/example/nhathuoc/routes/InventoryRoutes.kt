package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.ShopsTable
import com.example.nhathuoc.service.CreateBatchRequest
import com.example.nhathuoc.service.InventoryService
import com.example.nhathuoc.util.requireAnyRole
import com.example.nhathuoc.util.requireShopAccess
import com.example.nhathuoc.util.getUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.inventoryRoutes() {
    val inventoryService = InventoryService()

    route("/inventory") {
        authenticate("auth-jwt") {
            // POST /api/v1/inventory/batches - SHOP only
            post("/batches") {
                try {
                    val (principal, shopId) = call.requireShopAccess()

                    val request = call.receive<CreateBatchRequest>()

                    // Validate request
                    if (request.quantity <= 0) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Quantity must be greater than 0")
                        )
                    }

                    val batchId = inventoryService.createBatch(shopId, request)

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to mapOf("id" to batchId),
                            "message" to "Inventory batch created successfully"
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
                        mapOf("error" to "Failed to create inventory batch: ${e.message}")
                    )
                }
            }

            // GET /api/v1/inventory/batches - SHOP + ADMIN read
            get("/batches") {
                try {
                    val principal = call.requireAnyRole(setOf("SHOP", "ADMIN"))
                    val role = principal.payload.getClaim("role").asString()
                    val userId = principal.getUserId()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))

                    // Determine shop filter based on role
                    val shopId = when (role) {
                        "SHOP" -> {
                            // Shop can only see their own batches
                            transaction {
                                ShopsTable
                                    .selectAll()
                                    .where { ShopsTable.ownerId eq userId }
                                    .singleOrNull()
                                    ?.get(ShopsTable.id)
                                    ?: return@transaction null
                            } ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "No shop found for user")
                            )
                        }
                        "ADMIN" -> {
                            // Admin can filter by shopId or see all
                            call.parameters["shopId"]
                        }
                        else -> return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                    }

                    // Get query parameters
                    val productId = call.parameters["productId"]
                    val expired = call.parameters["expired"]?.toBoolean()
                    val expWithinDays = call.parameters["expWithinDays"]?.toIntOrNull()
                    val expBefore = call.parameters["expBefore"]?.let { LocalDate.parse(it) }
                    val page = call.parameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.parameters["limit"]?.toIntOrNull() ?: 20

                    if (page < 1 || limit < 1 || limit > 100) {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid page or limit")
                        )
                    }

                    val batches = inventoryService.getBatches(
                        shopId = shopId,
                        productId = productId,
                        expired = expired,
                        expWithinDays = expWithinDays,
                        expBefore = expBefore,
                        page = page,
                        limit = limit
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to batches,
                            "pagination" to mapOf(
                                "page" to page,
                                "limit" to limit
                            ),
                            "message" to "Get inventory batches successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get inventory batches: ${e.message}")
                    )
                }
            }

            // GET /api/v1/inventory/alerts/expiring - SHOP + ADMIN read
            get("/alerts/expiring") {
                try {
                    val principal = call.requireAnyRole(setOf("SHOP", "ADMIN"))
                    val role = principal.payload.getClaim("role").asString()
                    val userId = principal.getUserId()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))

                    val (shopId, defaultDays) = when (role) {
                        "SHOP" -> {
                            // Shop can only see their own alerts
                            val shop = transaction {
                                ShopsTable
                                    .selectAll()
                                    .where { ShopsTable.ownerId eq userId }
                                    .singleOrNull()
                            } ?: return@get call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "No shop found for user")
                            )
                            Pair(shop[ShopsTable.id], shop[ShopsTable.expiryAlertDays])
                        }
                        "ADMIN" -> {
                            // Admin can see all or filter by shopId, default to 30 days
                            Pair(call.parameters["shopId"], 30)
                        }
                        else -> return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
                    }

                    // Get days parameter, fallback to default
                    val days = call.parameters["days"]?.toIntOrNull() ?: defaultDays

                    if (days < 1 || days > 365) {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Days must be between 1 and 365")
                        )
                    }

                    val alerts = inventoryService.getExpiringBatches(shopId, days)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to alerts,
                            "alertDays" to days,
                            "message" to "Get expiring batches successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get expiring batches: ${e.message}")
                    )
                }
            }
        }
    }
}