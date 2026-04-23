package com.example.nhathuoc.routes

import com.example.nhathuoc.service.MoMoResponse
import com.example.nhathuoc.service.PaymentService
import com.example.nhathuoc.service.PaymentStatusDto
import com.example.nhathuoc.service.VNPayResponse
import com.example.nhathuoc.service.ZaloPayResponse
import com.example.nhathuoc.util.getUserId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

// ─────────────────────────────────────────────────────────
// REQUEST DTOs
// ─────────────────────────────────────────────────────────

@Serializable
data class PaymentInitRequest(
    val orderId: String,
    val returnUrl: String
)

@Serializable
data class CODPaymentRequest(
    val orderId: String
)

@Serializable
data class PaymentInitResponseData(
    val paymentUrl: String,
    val transactionRef: String? = null,
    val requestId: String? = null,
    val amount: Long? = null,
    val status: String? = null,
    val qrContent: String? = null,
    val deeplink: String? = null
)

@Serializable
data class CODPaymentData(
    val paymentId: String
)

@Serializable
data class PaymentStatusResponseData(
    val orderId: String,
    val paymentId: String,
    val method: String,
    val amount: Double,
    val status: String,
    val transactionRef: String? = null,
    val paidAt: String? = null
)

private fun VNPayResponse.toRouteData() = PaymentInitResponseData(
    paymentUrl = paymentUrl,
    transactionRef = transactionRef,
    amount = amount,
    status = status
)

private fun MoMoResponse.toRouteData() = PaymentInitResponseData(
    paymentUrl = paymentUrl,
    requestId = requestId,
    amount = amount,
    status = status,
    qrContent = qrContent,
    deeplink = deeplink
)

private fun ZaloPayResponse.toRouteData() = PaymentInitResponseData(
    paymentUrl = paymentUrl,
    transactionRef = transactionRef,
    amount = amount,
    status = status,
    qrContent = qrContent
)

private fun PaymentStatusDto.toRouteData() = PaymentStatusResponseData(
    orderId = orderId,
    paymentId = paymentId,
    method = method,
    amount = amount.toDouble(),
    status = status,
    transactionRef = transactionRef,
    paidAt = paidAt?.toString()
)

fun Route.paymentRoutes() {
    val paymentService = PaymentService()
    val json = Json { ignoreUnknownKeys = true }
    fun extractQueryParams(call: ApplicationCall): Map<String, String> {
        val params = mutableMapOf<String, String>()
        call.request.queryParameters.forEach { key, values ->
            params[key] = values.firstOrNull() ?: ""
        }
        return params
    }

    suspend fun ApplicationCall.handleVNPayCallbackResponse() {
        try {
            val params = extractQueryParams(this)
            if (params.isEmpty()) {
                respond(HttpStatusCode.OK, mapOf("RspCode" to "99", "Message" to "Input data required"))
                return
            }
            val (success, message) = paymentService.verifyVNPayCallback(
                params = params,
                rawGatewayResponse = json.encodeToString(params)
            )

            val rspCode = when {
                success && message.contains("already", ignoreCase = true) -> "02"
                success -> "00"
                message.contains("order not found", ignoreCase = true) -> "01"
                message.contains("amount mismatch", ignoreCase = true) -> "04"
                message.contains("invalid secure hash", ignoreCase = true) ||
                    message.contains("invalid signature", ignoreCase = true) -> "97"
                else -> "99"
            }
            val rspMessage = when (rspCode) {
                "00" -> "Confirm Success"
                "02" -> "Order already confirmed"
                "01" -> "Order not found"
                "04" -> "Invalid amount"
                "97" -> "Invalid signature"
                else -> "Invalid request"
            }
            respond(HttpStatusCode.OK, mapOf("RspCode" to rspCode, "Message" to rspMessage))
        } catch (e: Exception) {
            respond(
                HttpStatusCode.OK,
                mapOf("RspCode" to "99", "Message" to "Unknown error")
            )
        }
    }

    fun buildPosReturnHtml(
        provider: String,
        orderReference: String,
        isSuccess: Boolean,
        statusText: String
    ): String {
        return """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <title>POS Payment Return</title>
                <style>
                    body { font-family: Arial, sans-serif; background: #f5f7fb; color: #102033; padding: 32px; }
                    .card { max-width: 560px; margin: 0 auto; background: #fff; border-radius: 16px; padding: 24px; box-shadow: 0 12px 32px rgba(16,32,51,.08); }
                    .ok { color: #0f8a4b; }
                    .warn { color: #b26a00; }
                    code { background: #eef3f9; padding: 2px 6px; border-radius: 6px; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2>${if (isSuccess) "Thanh toan da tiep nhan" else "Dang cho xac nhan"}</h2>
                    <p class="${if (isSuccess) "ok" else "warn"}">$statusText</p>
                    <p>Nha cung cap: <code>${provider.uppercase()}</code></p>
                    <p>Ma don / tham chieu: <code>$orderReference</code></p>
                    <p>Desktop POS se tiep tuc poll trang thai va luu hoa don PDF sau khi don chuyen sang <code>COMPLETED</code>.</p>
                    <p>Ban co the dong tab nay sau khi thanh toan xong.</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    // ─────────────────────────────────────────────────────────
    // PAYMENT INITIATION - User authenticated
    // ─────────────────────────────────────────────────────────
    authenticate("auth-jwt") {
        route("/payments") {
            // POST /api/v1/payments/vnpay/init - Initiate VNPay payment
            post("/vnpay/init") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, RouteErrorResponse("Unauthorized"))

                    val request = call.receive<PaymentInitRequest>()

                    val response = paymentService.initiateVNPayment(
                        userId = userId,
                        orderId = request.orderId,
                        returnUrl = request.returnUrl
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = response.toRouteData(),
                            message = "VNPay payment initiated. Redirect user to paymentUrl"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        RouteErrorResponse(e.message ?: "Invalid VNPay payment request")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to initiate VNPay payment: ${e.message}")
                    )
                }
            }

            // POST /api/v1/payments/momo/init - Initiate MoMo payment
            post("/momo/init") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, RouteErrorResponse("Unauthorized"))

                    val request = call.receive<PaymentInitRequest>()

                    val response = paymentService.initiateMoMoPayment(
                        userId = userId,
                        orderId = request.orderId,
                        returnUrl = request.returnUrl
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = response.toRouteData(),
                            message = "MoMo payment initiated. Redirect user to paymentUrl"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        RouteErrorResponse(e.message ?: "Invalid MoMo payment request")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to initiate MoMo payment: ${e.message}")
                    )
                }
            }

            // POST /api/v1/payments/zalopay/init - Initiate ZaloPay payment
            post("/zalopay/init") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, RouteErrorResponse("Unauthorized"))

                    val request = call.receive<PaymentInitRequest>()

                    val response = paymentService.initiateZaloPayPayment(
                        userId = userId,
                        orderId = request.orderId,
                        returnUrl = request.returnUrl
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = response.toRouteData(),
                            message = "ZaloPay payment initiated. Redirect user to paymentUrl"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        RouteErrorResponse(e.message ?: "Invalid ZaloPay payment request")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to initiate ZaloPay payment: ${e.message}")
                    )
                }
            }

            // POST /api/v1/payments/cod/create - Create COD payment (no gateway needed)
            post("/cod/create") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, RouteErrorResponse("Unauthorized"))

                    val request = call.receive<CODPaymentRequest>()

                    val paymentId = paymentService.createCODPayment(
                        userId = userId,
                        orderId = request.orderId
                    )

                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(
                            data = CODPaymentData(paymentId),
                            message = "COD payment created. Order is ready for delivery."
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        RouteErrorResponse(e.message ?: "Invalid COD payment request")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to create COD payment: ${e.message}")
                    )
                }
            }

            // GET /api/v1/payments/{orderId} - Get payment status
            get("/{orderId}") {
                try {
                    val userId = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()?.getUserId()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, RouteErrorResponse("Unauthorized"))

                    val orderId = call.parameters["orderId"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, RouteErrorResponse("Order ID is required"))

                    val paymentStatus = paymentService.getPaymentStatus(
                        userId = userId,
                        orderId = orderId
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = paymentStatus.toRouteData(),
                            message = "Payment status retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to get payment status: ${e.message}")
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // PAYMENT CALLBACKS - Public webhooks (no auth)
    // ─────────────────────────────────────────────────────────
    route("/payments") {
        get("/pos-return") {
            val params = extractQueryParams(call)
            val provider = params["provider"]
                ?: when {
                    params.containsKey("vnp_TxnRef") -> "vnpay"
                    params.containsKey("partnerCode") || params.containsKey("requestId") -> "momo"
                    params.containsKey("apptransid") || params.containsKey("app_trans_id") -> "zalopay"
                    else -> "gateway"
                }
            val orderReference = params["orderId"]
                ?: params["vnp_TxnRef"]
                ?: params["requestId"]
                ?: params["apptransid"]
                ?: params["app_trans_id"]
                ?: "unknown"

            val (success, message) = when {
                params.containsKey("vnp_TxnRef") && params.containsKey("vnp_SecureHash") -> {
                    paymentService.verifyVNPayCallback(
                        params = params,
                        rawGatewayResponse = json.encodeToString(params)
                    )
                }
                params.containsKey("partnerCode") && params.containsKey("signature") -> {
                    paymentService.verifyMoMoCallback(
                        params = params,
                        rawGatewayResponse = json.encodeToString(params)
                    )
                }
                params.containsKey("apptransid") || params.containsKey("app_trans_id") -> {
                    paymentService.handleZaloPayRedirect(params)
                }
                else -> {
                    val resultCode = params["resultCode"] ?: params["vnp_ResponseCode"] ?: "pending"
                    val isSuccess = resultCode == "0" || resultCode == "00"
                    Pair(isSuccess, if (isSuccess) "Thanh toan thanh cong" else "Dang cho backend doi soat hoac thanh toan that bai")
                }
            }

            call.respondText(
                text = buildPosReturnHtml(
                    provider = provider,
                    orderReference = orderReference,
                    isSuccess = success,
                    statusText = message
                ),
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.OK
            )
        }
    }

    route("/webhooks") {
        // GET /api/v1/webhooks/vnpay/callback - VNPay return URL redirect
        get("/vnpay/callback") {
            call.handleVNPayCallbackResponse()
        }

        // POST /api/v1/webhooks/vnpay/callback - manual/server-side verification
        post("/vnpay/callback") {
            call.handleVNPayCallbackResponse()
        }

        // POST /api/v1/webhooks/momo/callback - MoMo calls this on payment complete
        post("/momo/callback") {
            try {
                val rawBody = call.receiveText()
                val params = json.parseToJsonElement(rawBody).jsonObject.mapValues { (_, value) ->
                    (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
                }

                val (success, message) = paymentService.verifyMoMoCallback(
                    params = params,
                    rawGatewayResponse = rawBody
                )

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

        post("/zalopay/callback") {
            try {
                val rawBody = call.receiveText()
                val ack = paymentService.handleZaloPayCallback(rawBody)
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "return_code" to ack.returnCode,
                        "return_message" to ack.returnMessage
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "return_code" to 0,
                        "return_message" to (e.message ?: "callback error")
                    )
                )
            }
        }
    }
}
