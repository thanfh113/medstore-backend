package com.example.nhathuoc.routes

import com.example.nhathuoc.service.OrderFulfillmentService
import com.example.nhathuoc.util.requireShopAccess
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.orderFulfillmentRoutes() {
    val orderFulfillmentService = OrderFulfillmentService()

    route("/orders") {
        authenticate("auth-jwt") {
            // POST /api/v1/orders/{orderId}/confirm-pack - SHOP only
            post("/{orderId}/confirm-pack") {
                try {
                    val (principal, shopId) = call.requireShopAccess()

                    val orderId = call.parameters["orderId"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Order ID is required")
                        )

                    val result = orderFulfillmentService.confirmPack(orderId, shopId)

                    if (result.successful) {
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf(
                                "data" to mapOf(
                                    "orderId" to result.orderId,
                                    "allocations" to result.allocatedBatches
                                ),
                                "message" to result.message
                            )
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to result.message)
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to confirm pack order: ${e.message}")
                    )
                }
            }

            // GET /api/v1/orders/{orderId}/fulfillment-details - SHOP only
            get("/{orderId}/fulfillment-details") {
                try {
                    val (principal, shopId) = call.requireShopAccess()

                    val orderId = call.parameters["orderId"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Order ID is required")
                        )

                    val details = orderFulfillmentService.getOrderFulfillmentDetails(orderId, shopId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to details,
                            "message" to "Get order fulfillment details successfully"
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
                        mapOf("error" to "Failed to get order fulfillment details: ${e.message}")
                    )
                }
            }
        }
    }
}