package com.example.nhathuoc.routes

import com.example.nhathuoc.service.CheckoutRequest
import com.example.nhathuoc.service.CheckoutService
import com.example.nhathuoc.util.getUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.checkoutRoutes() {
    val checkoutService = CheckoutService()

    authenticate("auth-jwt") {
        route("/checkout") {
            // POST /api/v1/checkout/preview - Get checkout summary without creating order
            post("/preview") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                    val request = call.receive<CheckoutRequest>()

                    // Validate cart first
                    val (isValid, errors) = checkoutService.validateCart(userId)
                    if (!isValid) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Cart validation failed", "errors" to errors)
                        )
                    }

                    // Get preview
                    val preview = checkoutService.getCheckoutPreview(
                        userId = userId,
                        addressId = request.addressId,
                        useRewardPoints = request.rewardPointsToUse
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to preview,
                            "message" to "Checkout preview retrieved successfully"
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
                        mapOf("error" to "Failed to get checkout preview: ${e.message}")
                    )
                }
            }

            // POST /api/v1/checkout/create - Create order and initiate payment
            post("/create") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                    val request = call.receive<CheckoutRequest>()

                    // Create order
                    val order = checkoutService.createOrder(userId, request)

                    // For VNPAY and MOMO, payment is handled by PaymentService in next step
                    // For COD, order is ready immediately
                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to order,
                            "message" to when (request.paymentMethod) {
                                "COD" -> "Order created successfully. Ready for delivery."
                                else -> "Order created. Proceed to payment."
                            }
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
                        mapOf("error" to "Failed to create order: ${e.message}")
                    )
                }
            }

            // GET /api/v1/checkout/{orderId} - Get order details
            get("/{orderId}") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                    val orderId = call.parameters["orderId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Order ID is required"))

                    val order = checkoutService.getOrderDetails(orderId, userId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to order,
                            "message" to "Order details retrieved successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get order details: ${e.message}")
                    )
                }
            }
        }
    }
}
