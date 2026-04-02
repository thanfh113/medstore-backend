package com.example.nhathuoc.routes

import com.example.nhathuoc.service.PaymentService
import com.example.nhathuoc.util.getUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────
// REQUEST DTOs
// ─────────────────────────────────────────────────────────

@Serializable
data class PaymentInitRequest(
    val orderId: String,
    val amount: Long,
    val returnUrl: String
)

@Serializable
data class CODPaymentRequest(
    val orderId: String
)

fun Route.paymentRoutes() {
    val paymentService = PaymentService()

    // ─────────────────────────────────────────────────────────
    // PAYMENT INITIATION - User authenticated
    // ─────────────────────────────────────────────────────────
    authenticate("auth-jwt") {
        route("/payments") {
            // POST /api/v1/payments/vnpay/init - Initiate VNPay payment
            post("/vnpay/init") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                    val request = call.receive<PaymentInitRequest>()

                    val response = paymentService.initiateVNPayment(
                        orderId = request.orderId,
                        amount = request.amount,
                        returnUrl = request.returnUrl
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to response,
                            "message" to "VNPay payment initiated. Redirect user to paymentUrl"
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
                        mapOf("error" to "Failed to initiate VNPay payment: ${e.message}")
                    )
                }
            }

            // POST /api/v1/payments/momo/init - Initiate MoMo payment
            post("/momo/init") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                    val request = call.receive<PaymentInitRequest>()

                    val response = paymentService.initiateMoMoPayment(
                        orderId = request.orderId,
                        amount = request.amount,
                        returnUrl = request.returnUrl
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to response,
                            "message" to "MoMo payment initiated. Redirect user to paymentUrl"
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
                        mapOf("error" to "Failed to initiate MoMo payment: ${e.message}")
                    )
                }
            }

            // POST /api/v1/payments/cod/create - Create COD payment (no gateway needed)
            post("/cod/create") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                    val request = call.receive<CODPaymentRequest>()

                    val paymentId = paymentService.createCODPayment(request.orderId)

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to mapOf("paymentId" to paymentId),
                            "message" to "COD payment created. Order is ready for delivery."
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
                        mapOf("error" to "Failed to create COD payment: ${e.message}")
                    )
                }
            }

            // GET /api/v1/payments/{orderId} - Get payment status
            get("/{orderId}") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                    val orderId = call.parameters["orderId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Order ID is required"))

                    val paymentStatus = paymentService.getPaymentStatus(orderId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to paymentStatus,
                            "message" to "Payment status retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get payment status: ${e.message}")
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // PAYMENT CALLBACKS - Public webhooks (no auth)
    // ─────────────────────────────────────────────────────────
    route("/webhooks") {
        // POST /api/v1/webhooks/vnpay/callback - VNPay calls this on payment complete
        post("/vnpay/callback") {
            try {
                // VNPay sends all parameters as query string
                val params = mutableMapOf<String, String>()
                call.request.queryParameters.forEach { key, values ->
                    params[key] = values.firstOrNull() ?: ""
                }

                val (success, message) = paymentService.verifyVNPayCallback(params)

                if (success) {
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "message" to message,
                            "code" to "00"  // VNPay expects "00" for success
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "message" to message,
                            "code" to "99"  // Non-zero code indicates failure
                        )
                    )
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "message" to "Callback processing error: ${e.message}",
                        "code" to "99"
                    )
                )
            }
        }

        // POST /api/v1/webhooks/momo/callback - MoMo calls this on payment complete
        post("/momo/callback") {
            try {
                // MoMo sends JSON in request body
                val params = call.receive<Map<String, String>>()

                val (success, message) = paymentService.verifyMoMoCallback(params)

                if (success) {
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "message" to message,
                            "status" to "success"
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "message" to message,
                            "status" to "failed"
                        )
                    )
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "message" to "Callback processing error: ${e.message}",
                        "status" to "error"
                    )
                )
            }
        }
    }
}