package com.example.nhathuoc.routes

import com.example.nhathuoc.service.CreateStockReceiptRequest
import com.example.nhathuoc.service.InventoryService
import com.example.nhathuoc.util.requireInternalAccess
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDate

fun Route.inventoryRoutes() {
    val inventoryService = InventoryService()

    route("/inventory") {
        authenticate("auth-jwt") {
            // POST /api/v1/inventory/stock - product-level stock receipt
            post("/stock") {
                try {
                    call.requireInternalAccess()

                    val request = call.receive<CreateStockReceiptRequest>()

                    // Validate request
                    if (request.quantity <= 0) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Quantity must be greater than 0")
                        )
                    }

                    val productId = inventoryService.receiveStock(request)

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to mapOf("id" to productId),
                            "message" to "Product stock updated successfully"
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
                        mapOf("error" to "Failed to update product stock: ${e.message}")
                    )
                }
            }

            // GET /api/v1/inventory/stock - internal roles only
            get("/stock") {
                try {
                    call.requireInternalAccess()

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

                    val stockEntries = inventoryService.getStockEntries(
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
                            "data" to stockEntries,
                            "pagination" to mapOf(
                                "page" to page,
                                "limit" to limit
                            ),
                            "message" to "Get product stock entries successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get product stock entries: ${e.message}")
                    )
                }
            }

            // GET /api/v1/inventory/alerts/expiring - internal roles only
            get("/alerts/expiring") {
                try {
                    call.requireInternalAccess()

                    val days = call.parameters["days"]?.toIntOrNull() ?: 30

                    if (days < 1 || days > 365) {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Days must be between 1 and 365")
                        )
                    }

                    val alerts = inventoryService.getExpiringStockAlerts(days)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to alerts,
                            "alertDays" to days,
                            "message" to "Get expiring products successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get expiring products: ${e.message}")
                    )
                }
            }
        }
    }
}
