package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.OrderItemsTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.PaymentsTable
import com.example.nhathuoc.database.tables.ProductsTable
import com.example.nhathuoc.database.tables.RewardAccountsTable
import com.example.nhathuoc.database.tables.RewardTransactionsTable
import com.example.nhathuoc.util.Env
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.time.Duration
import java.util.TimeZone as JavaTimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class VNPayResponse(
    val paymentUrl: String,
    val transactionRef: String,
    val amount: Long,
    val status: String
)

data class MoMoResponse(
    val paymentUrl: String,
    val requestId: String,
    val amount: Long,
    val status: String,
    val qrContent: String? = null,
    val deeplink: String? = null
)

data class ZaloPayResponse(
    val paymentUrl: String,
    val transactionRef: String,
    val amount: Long,
    val status: String,
    val qrContent: String? = null
)

@Serializable
data class PosGatewayPaymentResponse(
    val orderId: String,
    val orderCode: String,
    val paymentMethod: String,
    val paymentUrl: String,
    val qrContent: String,
    val paymentReference: String,
    val amount: Long,
    val status: String
)

data class PaymentStatusDto(
    val orderId: String,
    val paymentId: String,
    val method: String,
    val amount: BigDecimal,
    val status: String,
    val transactionRef: String?,
    val paidAt: LocalDateTime?
)

@Serializable
private data class VNPayPendingMetadata(
    val createDate: String,
    val lastQueryAtEpochMs: Long? = null
)

@Serializable
private data class MoMoCreateRequest(
    val partnerCode: String,
    val requestId: String,
    val amount: Long,
    val orderId: String,
    val orderInfo: String,
    val redirectUrl: String,
    val ipnUrl: String,
    val requestType: String,
    val extraData: String,
    val autoCapture: Boolean = true,
    val lang: String = "vi",
    val signature: String
)

@Serializable
private data class MoMoCreateGatewayResponse(
    val partnerCode: String? = null,
    val requestId: String? = null,
    val orderId: String? = null,
    val amount: Long? = null,
    val responseTime: Long? = null,
    val message: String? = null,
    val resultCode: Int? = null,
    val payUrl: String? = null,
    val deeplink: String? = null,
    val qrCodeUrl: String? = null,
    val signature: String? = null
)

@Serializable
private data class MoMoQueryRequest(
    val partnerCode: String,
    val requestId: String,
    val orderId: String,
    val lang: String = "vi",
    val signature: String
)

@Serializable
private data class MoMoQueryResponse(
    val partnerCode: String? = null,
    val requestId: String? = null,
    val orderId: String? = null,
    val extraData: String? = null,
    val amount: Long? = null,
    val transId: Long? = null,
    val payType: String? = null,
    val resultCode: Int? = null,
    val message: String? = null,
    val responseTime: Long? = null
)

@Serializable
private data class ZaloPayCreateGatewayResponse(
    val return_code: Int = -1,
    val return_message: String? = null,
    val sub_return_code: Int? = null,
    val sub_return_message: String? = null,
    val order_url: String? = null,
    val order_token: String? = null,
    val zp_trans_token: String? = null,
    val qr_code: String? = null
)

@Serializable
private data class ZaloPayQueryResponse(
    val returnCode: Int? = null,
    val returnMessage: String? = null,
    val subReturnCode: Int? = null,
    val subReturnMessage: String? = null,
    val isProcessing: Boolean? = null,
    val amount: Long? = null,
    val discountAmount: Long? = null,
    val zpTransId: Long? = null
)

@Serializable
private data class ZaloPayCallbackEnvelope(
    val data: String? = null,
    val mac: String? = null,
    val type: Int? = null
)

@Serializable
private data class ZaloPayCallbackData(
    val app_id: Long? = null,
    val app_trans_id: String? = null,
    val app_time: Long? = null,
    val app_user: String? = null,
    val amount: Long? = null,
    val embed_data: String? = null,
    val item: String? = null,
    val zp_trans_id: Long? = null,
    val server_time: Long? = null,
    val channel: Int? = null,
    val merchant_user_id: String? = null,
    val user_fee_amount: Long? = null,
    val discount_amount: Long? = null
)

@Serializable
private data class ZaloPayPendingMetadata(
    val lastQueryAtEpochMs: Long? = null,
    val redirectSeenAtEpochMs: Long? = null
)

data class ZaloPayCallbackAck(
    val returnCode: Int,
    val returnMessage: String
)

private data class PreparedMoMoPayment(
    val internalOrderId: String,
    val orderCode: String,
    val requestId: String,
    val amount: Long,
    val orderInfo: String,
    val redirectUrl: String,
    val ipnUrl: String
)

private data class CreatedMoMoPayment(
    val paymentUrl: String,
    val qrContent: String,
    val deeplink: String?
)

private data class PendingPosGatewayPayment(
    val paymentId: String,
    val method: String,
    val orderCode: String,
    val amount: Long,
    val transactionId: String?,
    val gatewayMetadata: String?,
    val createdAt: LocalDateTime
)

private data class PendingUserGatewayPayment(
    val paymentId: String,
    val method: String,
    val orderCode: String,
    val amount: Long,
    val transactionId: String?,
    val gatewayMetadata: String?,
    val createdAt: LocalDateTime
)

private data class VNPayQueryResult(
    val fields: Map<String, String>,
    val rawBody: String
)

class PaymentService {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val gatewayHttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    private val paymentEnv = Env.get("PAYMENT_ENV")?.lowercase() ?: "sandbox"
    private val paymentPublicBaseUrl = Env.get("PAYMENT_PUBLIC_BASE_URL")
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.isNotBlank() }
    private val posReturnUrl = Env.get("POS_PAYMENT_RETURN_URL")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: paymentPublicBaseUrl?.let { "$it/api/v1/payments/pos-return" }
        ?: "http://localhost:8080/api/v1/payments/pos-return"
    private val momoIpnUrl = Env.get("MOMO_IPN_URL")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    private val momoRequestType = "captureWallet"

    private val vnpayUrl = Env.get("VNPAY_PAYMENT_URL") ?: when (paymentEnv) {
        "production" -> "https://api.vnpay.vn/paymentv2/vpcpay.html"
        else -> "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html"
    }
    private val vnpayQueryUrl = resolveVNPayQueryUrl(Env.get("VNPAY_QUERY_URL"))
    private val vnpayTmnCode = requireEnv("VNPAY_TMN_CODE")
    private val vnpaySecretKey = requireEnv("VNPAY_SECRET_KEY")
    private val vnpayVersion = "2.1.0"

    private val momoCreateUrl = resolveMoMoCreateUrl(Env.get("MOMO_CREATE_URL"))
    private val momoQueryUrl = resolveMoMoQueryUrl(Env.get("MOMO_QUERY_URL"))
    private val momoAccessKey = requireEnv("MOMO_ACCESS_KEY")
    private val momoPartnerCode = requireEnv("MOMO_PARTNER_CODE")
    private val momoSecretKey = requireEnv("MOMO_SECRET_KEY")

    private val zaloAppId = requireEnv("ZALO_APP_ID")
    private val zaloKey1 = requireEnv("ZALO_KEY1")
    private val zaloKey2 = requireEnv("ZALO_KEY2")
    private val zaloEndpoint = resolveZaloCreateUrl(Env.get("ZALO_ENDPOINT"))
    private val zaloQueryUrl = resolveZaloQueryUrl(Env.get("ZALO_QUERY_URL"))
    private val zaloExpireDurationSeconds = Env.get("ZALO_EXPIRE_DURATION_SECONDS")
        ?.toLongOrNull()
        ?.coerceIn(300L, 2_592_000L)
        ?: 900L
    private val zaloPosBranchId = Env.get("ZALO_POS_BRANCH_ID")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "POS-HCM"
    private val zaloPosStoreId = Env.get("ZALO_POS_STORE_ID")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "default-store"
    private val zaloPosStoreName = Env.get("ZALO_POS_STORE_NAME")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "MedStore POS"

    fun initiateVNPayment(userId: String, orderId: String, returnUrl: String): VNPayResponse {
        return transaction {
            val order = getOwnedOrderForPayment(userId, orderId)
            val orderCode = order[OrdersTable.orderCode]
            val amount = normalizePaymentAmount(order[OrdersTable.total])
            val createDate = getFormattedDateTime()
            val orderInfo = "Thanh toan don hang $orderCode"

            val vnpParams = mutableMapOf(
                "vnp_Version" to vnpayVersion,
                "vnp_Command" to "pay",
                "vnp_TmnCode" to vnpayTmnCode,
                "vnp_Amount" to (amount * 100).toString(),
                "vnp_CurrCode" to "VND",
                "vnp_TxnRef" to orderCode,
                "vnp_OrderInfo" to orderInfo,
                "vnp_OrderType" to "other",
                "vnp_Locale" to "vn",
                "vnp_ReturnUrl" to returnUrl,
                "vnp_IpAddr" to "127.0.0.1",
                "vnp_CreateDate" to createDate
            )
            vnpParams["vnp_SecureHash"] = generateVNPaySecureHash(vnpParams)

            upsertPendingPayment(
                orderId = orderId,
                method = "VNPAY",
                amount = BigDecimal(amount),
                transactionId = orderCode,
                gatewayResponse = buildPendingVNPayMetadata(createDate)
            )
            markOrderPaymentPending(orderId, "VNPAY")

            VNPayResponse(
                paymentUrl = buildVNPayUrl(vnpParams),
                transactionRef = orderCode,
                amount = amount,
                status = "PENDING"
            )
        }
    }

    fun initiateMoMoPayment(userId: String, orderId: String, returnUrl: String): MoMoResponse {
        val prepared = transaction {
            val order = getOwnedOrderForPayment(userId, orderId)
            prepareMoMoPayment(
                order = order,
                redirectUrl = returnUrl,
                orderInfo = "Thanh toan don hang ${order[OrdersTable.orderCode]}"
            )
        }
        val createdPayment = createMoMoPayment(prepared)

        transaction {
            upsertPendingPayment(
                orderId = prepared.internalOrderId,
                method = "MOMO",
                amount = BigDecimal(prepared.amount),
                transactionId = prepared.requestId
            )
            markOrderPaymentPending(prepared.internalOrderId, "MOMO")
        }

        return MoMoResponse(
            paymentUrl = createdPayment.paymentUrl,
            requestId = prepared.requestId,
            amount = prepared.amount,
            status = "PENDING",
            qrContent = createdPayment.qrContent,
            deeplink = createdPayment.deeplink
        )
    }

    fun initiateZaloPayPayment(userId: String, orderId: String, returnUrl: String): ZaloPayResponse {
        val finalReturnUrl = returnUrl.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Return URL is required")

        return transaction {
            val order = getOwnedOrderForPayment(userId, orderId)
            val orderCode = order[OrdersTable.orderCode]
            val amount = normalizePaymentAmount(order[OrdersTable.total])
            val transId = buildZaloAppTransId()
            val appTime = System.currentTimeMillis()
            val item = "[]"
            val callbackUrl = resolveZaloCallbackUrl(finalReturnUrl)
            val embedData = buildZaloEmbedData(
                redirectUrl = finalReturnUrl,
                orderCode = orderCode
            )
            val requestParams = linkedMapOf(
                "app_id" to zaloAppId,
                "app_trans_id" to transId,
                "app_user" to orderCode,
                "app_time" to appTime.toString(),
                "item" to item,
                "embed_data" to embedData,
                "amount" to amount.toString(),
                "description" to "Thanh toan don hang $orderCode",
                "expire_duration_seconds" to zaloExpireDurationSeconds.toString(),
                "bank_code" to "zalopayapp"
            )
            requestParams["callback_url"] = callbackUrl
            val signaturePayload = listOf(
                requestParams.getValue("app_id"),
                requestParams.getValue("app_trans_id"),
                requestParams.getValue("app_user"),
                requestParams.getValue("amount"),
                requestParams.getValue("app_time"),
                requestParams.getValue("embed_data"),
                requestParams.getValue("item")
            ).joinToString("|")
            requestParams["mac"] = generateZaloSignature(signaturePayload, zaloKey1)

            val requestBody = buildFormUrlEncodedBody(requestParams)
            println("DEBUG: Preparing online ZaloPay payment - transId=$transId, amount=$amount")

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(zaloEndpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = try {
                gatewayHttpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                throw IllegalStateException(
                    "ZaloPay gateway unreachable at $zaloEndpoint: ${rootCauseMessage(e)}",
                    e
                )
            }

            println("DEBUG: ZaloPay online response status=${response.statusCode()}")
            println("DEBUG: ZaloPay online response body=${response.body().take(500)}")
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException(
                    "ZaloPay create payment failed with HTTP ${response.statusCode()}: ${response.body().take(500)}"
                )
            }

            val gatewayResponse = try {
                json.decodeFromString<ZaloPayCreateGatewayResponse>(response.body())
            } catch (e: Exception) {
                throw IllegalStateException("ZaloPay response parsing failed: ${rootCauseMessage(e)}", e)
            }
            if (gatewayResponse.return_code != 1) {
                val responseMessage = listOfNotNull(
                    gatewayResponse.return_message?.takeIf { it.isNotBlank() },
                    gatewayResponse.sub_return_message?.takeIf { it.isNotBlank() }
                ).joinToString(" - ").ifBlank { "Unknown error" }
                throw IllegalStateException("ZaloPay create payment failed: $responseMessage")
            }

            val paymentUrl = gatewayResponse.order_url?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("ZaloPay did not return order_url")
            val paymentQrCode = gatewayResponse.qr_code?.takeIf { it.isNotBlank() } ?: paymentUrl

            upsertPendingPayment(
                orderId = orderId,
                method = "ZALOPAY",
                amount = BigDecimal(amount),
                transactionId = transId,
                gatewayResponse = buildPendingZaloMetadata()
            )
            markOrderPaymentPending(orderId, "ZALOPAY")

            ZaloPayResponse(
                paymentUrl = paymentUrl,
                transactionRef = transId,
                amount = amount,
                status = "PENDING",
                qrContent = paymentQrCode
            )
        }
    }

    fun initiatePosGatewayPayment(
        orderId: String,
        method: String,
        returnUrl: String? = null
    ): PosGatewayPaymentResponse {
        val normalizedMethod = method.trim().uppercase()
        println("DEBUG: initiatePosGatewayPayment called - orderId=$orderId, method=$normalizedMethod")
        return transaction {
            val order = getPosOrderForPayment(orderId, normalizedMethod)
            val orderCode = order[OrdersTable.orderCode]
            val amount = normalizePaymentAmount(order[OrdersTable.total])
            val finalReturnUrl = returnUrl?.trim()?.takeIf { it.isNotBlank() } ?: posReturnUrl
            println("DEBUG: POS Payment Init - orderCode=$orderCode, amount=$amount, method=$normalizedMethod")

            when (normalizedMethod) {
                "VNPAY" -> {
                    val createDate = getFormattedDateTime()
                    val orderInfo = "Thanh toan POS $orderCode"
                    val vnpParams = mutableMapOf(
                        "vnp_Version" to vnpayVersion,
                        "vnp_Command" to "pay",
                        "vnp_TmnCode" to vnpayTmnCode,
                        "vnp_Amount" to (amount * 100).toString(),
                        "vnp_CurrCode" to "VND",
                        "vnp_TxnRef" to orderCode,
                        "vnp_OrderInfo" to orderInfo,
                        "vnp_OrderType" to "other",
                        "vnp_Locale" to "vn",
                        "vnp_ReturnUrl" to finalReturnUrl,
                        "vnp_IpAddr" to "127.0.0.1",
                        "vnp_CreateDate" to createDate
                    )
                    vnpParams["vnp_SecureHash"] = generateVNPaySecureHash(vnpParams)

                    upsertPendingPayment(
                        orderId = orderId,
                        method = "VNPAY",
                        amount = BigDecimal(amount),
                        transactionId = orderCode,
                        gatewayResponse = buildPendingVNPayMetadata(createDate)
                    )
                    markOrderPaymentPending(orderId, "VNPAY")

                    val paymentUrl = buildVNPayUrl(vnpParams)
                    PosGatewayPaymentResponse(
                        orderId = orderId,
                        orderCode = orderCode,
                        paymentMethod = "VNPAY",
                        paymentUrl = paymentUrl,
                        qrContent = paymentUrl,
                        paymentReference = orderCode,
                        amount = amount,
                        status = "PENDING"
                    )
                }

                "MOMO" -> {
                    try {
                        println("DEBUG: Preparing MoMo payment for POS")
                        val prepared = prepareMoMoPayment(
                            order = order,
                            redirectUrl = finalReturnUrl,
                            orderInfo = "Thanh toan POS $orderCode"
                        )
                        println("DEBUG: Created MoMo payment request - requestId=${prepared.requestId}")
                        val createdPayment = createMoMoPayment(prepared)
                        println("DEBUG: MoMo response received - qrUrl=${createdPayment.qrContent?.take(50)}")

                        upsertPendingPayment(
                            orderId = orderId,
                            method = "MOMO",
                            amount = BigDecimal(amount),
                            transactionId = prepared.requestId
                        )
                        markOrderPaymentPending(orderId, "MOMO")

                        PosGatewayPaymentResponse(
                            orderId = orderId,
                            orderCode = orderCode,
                            paymentMethod = "MOMO",
                            paymentUrl = createdPayment.paymentUrl,
                            qrContent = createdPayment.qrContent,
                            paymentReference = prepared.requestId,
                            amount = amount,
                            status = "PENDING"
                        )
                    } catch (e: Exception) {
                        println("ERROR: MoMo payment init failed - ${rootCauseMessage(e)}")
                        e.printStackTrace()
                        throw IllegalStateException("MoMo payment initialization failed: ${rootCauseMessage(e)}", e)
                    }
                }

                "ZALOPAY" -> {
                    try {
                        val transId = buildZaloAppTransId()
                        val appTime = System.currentTimeMillis()
                        val appUser = orderCode
                        val item = "[]"
                        val callbackUrl = resolveZaloCallbackUrl(finalReturnUrl)
                        val embedData = buildZaloEmbedData(
                            redirectUrl = finalReturnUrl,
                            orderCode = orderCode
                        )
                        val requestParams = linkedMapOf(
                            "app_id" to zaloAppId,
                            "app_trans_id" to transId,
                            "app_user" to appUser,
                            "app_time" to appTime.toString(),
                            "item" to item,
                            "embed_data" to embedData,
                            "amount" to amount.toString(),
                            "description" to "Thanh toan POS $orderCode",
                            "expire_duration_seconds" to zaloExpireDurationSeconds.toString(),
                            "bank_code" to "zalopayapp"
                        )
                        requestParams["callback_url"] = callbackUrl
                        val signaturePayload = listOf(
                            requestParams.getValue("app_id"),
                            requestParams.getValue("app_trans_id"),
                            requestParams.getValue("app_user"),
                            requestParams.getValue("amount"),
                            requestParams.getValue("app_time"),
                            requestParams.getValue("embed_data"),
                            requestParams.getValue("item")
                        ).joinToString("|")
                        requestParams["mac"] = generateZaloSignature(signaturePayload, zaloKey1)

                        val requestBody = buildFormUrlEncodedBody(requestParams)
                        println("DEBUG: Preparing ZaloPay payment - transId=$transId, amount=$amount")
                        println("DEBUG: ZaloPay request body - ${requestBody.take(200)}")

                        val httpRequest = HttpRequest.newBuilder()
                            .uri(URI.create(zaloEndpoint))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                            .build()

                        println("DEBUG: Sending ZaloPay request to $zaloEndpoint")
                        val response = try {
                            gatewayHttpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
                        } catch (e: Exception) {
                            throw IllegalStateException(
                                "ZaloPay gateway unreachable at $zaloEndpoint: ${rootCauseMessage(e)}",
                                e
                            )
                        }
                        println("DEBUG: ZaloPay response status=${response.statusCode()}")
                        println("DEBUG: ZaloPay response body=${response.body().take(500)}")

                        if (response.statusCode() !in 200..299) {
                            throw IllegalStateException(
                                "ZaloPay create payment failed with HTTP ${response.statusCode()}: ${response.body().take(500)}"
                            )
                        }

                        val gatewayResponse = try {
                            json.decodeFromString<ZaloPayCreateGatewayResponse>(response.body())
                        } catch (e: Exception) {
                            println("ERROR: Failed to parse ZaloPay response: ${e.message}")
                            println("Raw response: ${response.body()}")
                            throw IllegalStateException("ZaloPay response parsing failed: ${rootCauseMessage(e)}", e)
                        }

                        println("DEBUG: ZaloPay response parsed - return_code=${gatewayResponse.return_code}")
                        if (gatewayResponse.return_code != 1) {
                            val responseMessage = listOfNotNull(
                                gatewayResponse.return_message?.takeIf { it.isNotBlank() },
                                gatewayResponse.sub_return_message?.takeIf { it.isNotBlank() }
                            ).joinToString(" - ").ifBlank { "Unknown error" }
                            throw IllegalStateException("ZaloPay create payment failed: $responseMessage")
                        }

                        val paymentUrl = gatewayResponse.order_url?.takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException("ZaloPay did not return order_url")
                        val paymentQrCode = gatewayResponse.qr_code?.takeIf { it.isNotBlank() } ?: paymentUrl

                        println("DEBUG: ZaloPay payment URL obtained - ${paymentUrl.take(50)}")

                        upsertPendingPayment(
                            orderId = orderId,
                            method = "ZALOPAY",
                            amount = BigDecimal(amount),
                            transactionId = transId,
                            gatewayResponse = buildPendingZaloMetadata()
                        )
                        markOrderPaymentPending(orderId, "ZALOPAY")

                        PosGatewayPaymentResponse(
                            orderId = orderId,
                            orderCode = orderCode,
                            paymentMethod = "ZALOPAY",
                            paymentUrl = paymentUrl,
                            qrContent = paymentQrCode,
                            paymentReference = transId,
                            amount = amount,
                            status = "PENDING"
                        )
                    } catch (e: Exception) {
                        println("ERROR: ZaloPay payment init failed - ${rootCauseMessage(e)}")
                        e.printStackTrace()
                        throw IllegalStateException("ZaloPay payment initialization failed: ${rootCauseMessage(e)}", e)
                    }
                }
                else -> throw IllegalArgumentException("Unsupported POS payment method: $normalizedMethod")
            }
        }
    }

    fun createCODPayment(userId: String, orderId: String): String {
        return transaction {
            val order = getOwnedOrderForPayment(userId, orderId)
            val amount = order[OrdersTable.total] ?: BigDecimal.ZERO
            val paymentId = upsertPendingPayment(
                orderId = orderId,
                method = "COD",
                amount = amount,
                transactionId = null
            )

            OrdersTable.update({ OrdersTable.id eq orderId }) {
                it[paymentMethod] = "COD"
                it[paymentStatus] = "UNPAID"
                it[status] = "PROCESSING"
            }

            paymentId
        }
    }

    fun getPaymentStatus(userId: String, orderId: String): PaymentStatusDto {
        val pendingGatewayPayment = transaction {
            val order = OrdersTable
                .selectAll()
                .where { (OrdersTable.id eq orderId) and (OrdersTable.userId eq userId) }
                .singleOrNull()
                ?: throw IllegalArgumentException("Order not found or access denied")

            val payment = PaymentsTable
                .selectAll()
                .where { PaymentsTable.orderId eq orderId }
                .orderBy(PaymentsTable.createdAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?: throw IllegalArgumentException("Payment not found")

            val method = payment[PaymentsTable.method]
            if (
                order[OrdersTable.status] != "CANCELLED" &&
                order[OrdersTable.paymentStatus] == "PENDING" &&
                payment[PaymentsTable.status] == "PENDING" &&
                method in setOf("MOMO", "VNPAY", "ZALOPAY")
            ) {
                PendingUserGatewayPayment(
                    paymentId = payment[PaymentsTable.id],
                    method = method,
                    orderCode = order[OrdersTable.orderCode],
                    amount = normalizePaymentAmount(order[OrdersTable.total]),
                    transactionId = payment[PaymentsTable.transactionId],
                    gatewayMetadata = payment[PaymentsTable.paymentGatewayResponse],
                    createdAt = payment[PaymentsTable.createdAt]
                )
            } else {
                null
            }
        }

        pendingGatewayPayment?.let { reconcilePendingUserGatewayPayment(orderId, it) }

        return transaction {
            OrdersTable
                .selectAll()
                .where { (OrdersTable.id eq orderId) and (OrdersTable.userId eq userId) }
                .singleOrNull()
                ?: throw IllegalArgumentException("Order not found or access denied")

            val payment = PaymentsTable
                .selectAll()
                .where { PaymentsTable.orderId eq orderId }
                .orderBy(PaymentsTable.createdAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?: throw IllegalArgumentException("Payment not found")

            PaymentStatusDto(
                orderId = payment[PaymentsTable.orderId],
                paymentId = payment[PaymentsTable.id],
                method = payment[PaymentsTable.method],
                amount = payment[PaymentsTable.amount],
                status = payment[PaymentsTable.status],
                transactionRef = payment[PaymentsTable.transactionId],
                paidAt = payment[PaymentsTable.paidAt]
            )
        }
    }

    fun verifyVNPayCallback(
        params: Map<String, String>,
        rawGatewayResponse: String = json.encodeToString(params)
    ): Pair<Boolean, String> {
        return try {
            val secureHash = params["vnp_SecureHash"] ?: return Pair(false, "Missing secure hash")
            val sortedParams = params.toMutableMap().apply {
                remove("vnp_SecureHash")
                remove("vnp_SecureHashType")
            }
            val calculatedHash = generateVNPaySecureHash(sortedParams)
            if (!calculatedHash.equals(secureHash, ignoreCase = true)) {
                return Pair(false, "Invalid secure hash")
            }

            if (!isVNPaySuccessCode(params["vnp_ResponseCode"])) {
                return Pair(false, "Payment failed with code: ${params["vnp_ResponseCode"] ?: "99"}")
            }
            params["vnp_TransactionStatus"]?.let { transactionStatus ->
                if (!isVNPaySuccessCode(transactionStatus)) {
                    return Pair(false, "Transaction failed with status: $transactionStatus")
                }
            }

            val txnRef = params["vnp_TxnRef"] ?: return Pair(false, "Missing transaction reference")
            val transactionNo = params["vnp_TransactionNo"] ?: txnRef
            val amount = params["vnp_Amount"]?.toLongOrNull()
                ?: return Pair(false, "Missing or invalid amount")

            transaction {
                val order = OrdersTable
                    .selectAll()
                    .where { OrdersTable.orderCode eq txnRef }
                    .singleOrNull()
                    ?: return@transaction Pair(false, "Order not found")

                if (amount != normalizeGatewayAmount(order[OrdersTable.total])) {
                    return@transaction Pair(false, "Payment amount mismatch")
                }

                val orderId = order[OrdersTable.id]
                val payment = findLatestPayment(orderId, "VNPAY")
                    ?: return@transaction Pair(false, "Payment record not found")

                if (order[OrdersTable.status] == "CANCELLED") {
                    return@transaction Pair(true, "VNPay callback already processed")
                }

                if (payment[PaymentsTable.status] == "COMPLETED") {
                    return@transaction Pair(true, "VNPay callback already processed")
                }

                if (completeSuccessfulOrderPayment(
                    order = order,
                    paymentId = payment[PaymentsTable.id],
                    transactionId = transactionNo,
                    gatewayResponse = rawGatewayResponse
                )) {
                    awardRewardPointsIfNeeded(order)
                }

                Pair(true, "Thanh toan VNPay thanh cong")
            }
        } catch (e: Exception) {
            Pair(false, "Error verifying VNPay: ${e.message}")
        }
    }

    fun verifyMoMoCallback(
        params: Map<String, String>,
        rawGatewayResponse: String = json.encodeToString(params)
    ): Pair<Boolean, String> {
        return try {
            val partnerCode = params["partnerCode"] ?: return Pair(false, "Missing partnerCode")
            if (partnerCode != momoPartnerCode) {
                return Pair(false, "Invalid partnerCode")
            }

            val signature = params["signature"] ?: return Pair(false, "Missing signature")
            val calculatedSignature = generateMoMoCallbackSignature(params)
            if (!signature.equals(calculatedSignature, ignoreCase = true)) {
                return Pair(false, "Invalid MoMo signature")
            }

            if (params["resultCode"] != "0") {
                return Pair(false, "MoMo payment failed with code: ${params["resultCode"] ?: "1"}")
            }

            val orderCode = params["orderId"] ?: return Pair(false, "Missing orderId")
            val requestId = params["requestId"] ?: return Pair(false, "Missing requestId")

            transaction {
                val order = OrdersTable
                    .selectAll()
                    .where { OrdersTable.orderCode eq orderCode }
                    .singleOrNull()
                    ?: return@transaction Pair(false, "Order not found")

                params["amount"]?.toLongOrNull()?.let { amount ->
                    if (amount != normalizePaymentAmount(order[OrdersTable.total])) {
                        return@transaction Pair(false, "Payment amount mismatch")
                    }
                }

                val orderId = order[OrdersTable.id]
                val payment = findLatestPayment(orderId, "MOMO")
                    ?: return@transaction Pair(false, "Payment record not found")

                if (order[OrdersTable.status] == "CANCELLED") {
                    return@transaction Pair(true, "MoMo callback already processed")
                }

                if (payment[PaymentsTable.status] == "COMPLETED") {
                    return@transaction Pair(true, "MoMo callback already processed")
                }

                val gatewayTransactionId = params["transId"]?.takeIf { it.isNotBlank() } ?: requestId
                if (completeSuccessfulOrderPayment(
                    order = order,
                    paymentId = payment[PaymentsTable.id],
                    transactionId = gatewayTransactionId,
                    gatewayResponse = rawGatewayResponse
                )) {
                    awardRewardPointsIfNeeded(order)
                }

                Pair(true, "Thanh toan MoMo thanh cong")
            }
        } catch (e: Exception) {
            Pair(false, "Error verifying MoMo: ${e.message}")
        }
    }

    fun handleZaloPayCallback(rawGatewayResponse: String): ZaloPayCallbackAck {
        return try {
            val envelope = json.decodeFromString<ZaloPayCallbackEnvelope>(rawGatewayResponse)
            val data = envelope.data?.takeIf { it.isNotBlank() }
                ?: return ZaloPayCallbackAck(0, "missing data")
            val mac = envelope.mac?.takeIf { it.isNotBlank() }
                ?: return ZaloPayCallbackAck(0, "missing mac")
            val calculatedMac = generateZaloSignature(data, zaloKey2)
            if (!mac.equals(calculatedMac, ignoreCase = true)) {
                return ZaloPayCallbackAck(-1, "mac not equal")
            }

            val callbackData = json.decodeFromString<ZaloPayCallbackData>(data)
            val appTransId = callbackData.app_trans_id?.takeIf { it.isNotBlank() }
                ?: return ZaloPayCallbackAck(0, "missing app_trans_id")

            transaction {
                val payment = findLatestPaymentByTransactionId(
                    method = "ZALOPAY",
                    transactionId = appTransId
                ) ?: return@transaction ZaloPayCallbackAck(0, "payment not found")

                val order = OrdersTable
                    .selectAll()
                    .where { OrdersTable.id eq payment[PaymentsTable.orderId] }
                    .singleOrNull()
                    ?: return@transaction ZaloPayCallbackAck(0, "order not found")

                callbackData.amount?.let { gatewayAmount ->
                    if (gatewayAmount != normalizePaymentAmount(order[OrdersTable.total])) {
                        return@transaction ZaloPayCallbackAck(0, "amount mismatch")
                    }
                }

                if (order[OrdersTable.status] == "CANCELLED") {
                    return@transaction ZaloPayCallbackAck(2, "duplicate")
                }

                if (payment[PaymentsTable.status] == "COMPLETED" || order[OrdersTable.paymentStatus] == "COMPLETED") {
                    return@transaction ZaloPayCallbackAck(2, "duplicate")
                }

                if (completeSuccessfulOrderPayment(
                    order = order,
                    paymentId = payment[PaymentsTable.id],
                    transactionId = callbackData.zp_trans_id?.toString() ?: appTransId,
                    gatewayResponse = rawGatewayResponse
                )) {
                    awardRewardPointsIfNeeded(order)
                }
                ZaloPayCallbackAck(1, "success")
            }
        } catch (e: Exception) {
            ZaloPayCallbackAck(0, e.message ?: "callback error")
        }
    }

    fun handleZaloPayRedirect(params: Map<String, String>): Pair<Boolean, String> {
        val appTransId = params["apptransid"]
            ?: params["app_trans_id"]
            ?: return Pair(false, "Missing ZaloPay transaction reference")

        params["checksum"]?.takeIf { it.isNotBlank() }?.let { checksum ->
            val checksumPayload = listOf(
                params["appid"].orEmpty(),
                appTransId,
                params["pmcid"].orEmpty(),
                params["bankcode"].orEmpty(),
                params["amount"].orEmpty(),
                params["discountamount"].orEmpty(),
                params["status"].orEmpty()
            ).joinToString("|")
            val calculatedChecksum = generateZaloSignature(checksumPayload, zaloKey2)
            if (!checksum.equals(calculatedChecksum, ignoreCase = true)) {
                return Pair(false, "Invalid ZaloPay redirect checksum")
            }
        }

        transaction {
            val payment = findLatestPaymentByTransactionId(
                method = "ZALOPAY",
                transactionId = appTransId
            ) ?: return@transaction
            val metadata = resolveZaloPendingMetadata(
                PendingPosGatewayPayment(
                    paymentId = payment[PaymentsTable.id],
                    method = payment[PaymentsTable.method],
                    orderCode = "",
                    amount = payment[PaymentsTable.amount].setScale(0, RoundingMode.HALF_UP).toLong(),
                    transactionId = payment[PaymentsTable.transactionId],
                    gatewayMetadata = payment[PaymentsTable.paymentGatewayResponse],
                    createdAt = payment[PaymentsTable.createdAt]
                )
            )
            PaymentsTable.update({ PaymentsTable.id eq payment[PaymentsTable.id] }) {
                it[PaymentsTable.paymentGatewayResponse] = buildPendingZaloMetadata(
                    lastQueryAtEpochMs = metadata.lastQueryAtEpochMs,
                    redirectSeenAtEpochMs = System.currentTimeMillis()
                )
            }
        }

        val isCompleted = transaction {
            val payment = findLatestPaymentByTransactionId(
                method = "ZALOPAY",
                transactionId = appTransId
            )
            payment?.get(PaymentsTable.status) == "COMPLETED"
        }
        return if (isCompleted) {
            Pair(true, "Thanh toan ZaloPay thanh cong")
        } else {
            Pair(false, "Da nhan redirect ZaloPay, dang doi soat giao dich")
        }
    }

    fun reconcilePendingPosGatewayPayment(orderId: String) {
        val pendingPayment = transaction {
            val order = OrdersTable
                .selectAll()
                .where { (OrdersTable.id eq orderId) and (OrdersTable.orderChannel eq "POS") }
                .singleOrNull()
                ?: return@transaction null

            if (order[OrdersTable.status] == "CANCELLED") {
                return@transaction null
            }

            val method = order[OrdersTable.paymentMethod]
            if (method !in setOf("MOMO", "VNPAY", "ZALOPAY") || order[OrdersTable.paymentStatus] != "PENDING") {
                return@transaction null
            }

            val payment = findLatestPayment(orderId, method!!)
                ?: return@transaction null
            if (payment[PaymentsTable.status] != "PENDING") {
                return@transaction null
            }

            PendingPosGatewayPayment(
                paymentId = payment[PaymentsTable.id],
                method = method,
                orderCode = order[OrdersTable.orderCode],
                amount = normalizePaymentAmount(order[OrdersTable.total]),
                transactionId = payment[PaymentsTable.transactionId],
                gatewayMetadata = payment[PaymentsTable.paymentGatewayResponse],
                createdAt = payment[PaymentsTable.createdAt]
            )
        } ?: return

        when (pendingPayment.method) {
            "MOMO" -> reconcilePendingMoMoPosPayment(orderId, pendingPayment)
            "VNPAY" -> reconcilePendingVNPayPosPayment(orderId, pendingPayment)
            "ZALOPAY" -> reconcilePendingZaloPayPosPayment(orderId, pendingPayment)
        }
    }

    private fun reconcilePendingUserGatewayPayment(
        orderId: String,
        pendingPayment: PendingUserGatewayPayment
    ) {
        when (pendingPayment.method) {
            "MOMO" -> reconcilePendingMoMoUserPayment(orderId, pendingPayment)
            "VNPAY" -> reconcilePendingVNPayUserPayment(orderId, pendingPayment)
            "ZALOPAY" -> reconcilePendingZaloPayUserPayment(orderId, pendingPayment)
        }
    }

    private fun reconcilePendingMoMoUserPayment(
        orderId: String,
        pendingPayment: PendingUserGatewayPayment
    ) {
        val queryResponse = try {
            queryMoMoPayment(orderCode = pendingPayment.orderCode)
        } catch (e: Exception) {
            println("WARN: MoMo user query reconciliation failed for orderId=$orderId - ${rootCauseMessage(e)}")
            return
        }

        if (queryResponse.resultCode != 0) {
            return
        }

        transaction {
            val order = OrdersTable
                .selectAll()
                .where { OrdersTable.id eq orderId }
                .singleOrNull()
                ?: return@transaction

            if (order[OrdersTable.status] == "CANCELLED" || order[OrdersTable.paymentStatus] == "COMPLETED") {
                return@transaction
            }

            val payment = findLatestPayment(orderId, "MOMO")
                ?: return@transaction
            if (payment[PaymentsTable.status] == "COMPLETED") {
                return@transaction
            }

            val gatewayAmount = queryResponse.amount
            if (gatewayAmount != null && gatewayAmount != normalizePaymentAmount(order[OrdersTable.total])) {
                println("WARN: MoMo user query amount mismatch for orderId=$orderId - gateway=$gatewayAmount")
                return@transaction
            }

            if (completeSuccessfulOrderPayment(
                order = order,
                paymentId = payment[PaymentsTable.id],
                transactionId = queryResponse.transId?.toString()
                    ?: payment[PaymentsTable.transactionId]
                    ?: pendingPayment.orderCode,
                gatewayResponse = json.encodeToString(queryResponse)
            )) {
                awardRewardPointsIfNeeded(order)
                println("INFO: Reconciled pending MoMo user payment successfully for orderId=$orderId")
            }
        }
    }

    private fun reconcilePendingVNPayUserPayment(
        orderId: String,
        pendingPayment: PendingUserGatewayPayment
    ) {
        val metadata = resolveVNPayPendingMetadata(pendingPayment.toPosGatewayPayment())
        val now = System.currentTimeMillis()
        val createdAtEpoch = parseVNPayDateTimeToEpochMs(metadata.createDate)
        if (createdAtEpoch != null && now - createdAtEpoch < 30_000L) {
            return
        }
        metadata.lastQueryAtEpochMs?.let { lastQueryAt ->
            if (now - lastQueryAt < 5 * 60 * 1000L) {
                return
            }
        }

        updatePendingVNPayMetadata(
            paymentId = pendingPayment.paymentId,
            metadata = metadata.copy(lastQueryAtEpochMs = now)
        )

        val queryResult = try {
            queryVNPayPayment(
                orderCode = pendingPayment.orderCode,
                transactionDate = metadata.createDate
            )
        } catch (e: Exception) {
            println("WARN: VNPay user query reconciliation failed for orderId=$orderId - ${rootCauseMessage(e)}")
            return
        }

        val fields = queryResult.fields
        if (fields["vnp_ResponseCode"] == "94") {
            return
        }
        if (!isVNPaySuccessCode(fields["vnp_ResponseCode"]) || !isVNPayQuerySettledSuccess(fields["vnp_TransactionStatus"])) {
            return
        }

        transaction {
            val order = OrdersTable
                .selectAll()
                .where { OrdersTable.id eq orderId }
                .singleOrNull()
                ?: return@transaction

            if (order[OrdersTable.status] == "CANCELLED" || order[OrdersTable.paymentStatus] == "COMPLETED") {
                return@transaction
            }

            val payment = findLatestPayment(orderId, "VNPAY")
                ?: return@transaction
            if (payment[PaymentsTable.status] == "COMPLETED") {
                return@transaction
            }

            val gatewayAmount = fields["vnp_Amount"]?.toLongOrNull()
            if (gatewayAmount != null && gatewayAmount != normalizeGatewayAmount(order[OrdersTable.total])) {
                println("WARN: VNPay user query amount mismatch for orderId=$orderId - gateway=$gatewayAmount")
                return@transaction
            }

            if (completeSuccessfulOrderPayment(
                order = order,
                paymentId = payment[PaymentsTable.id],
                transactionId = fields["vnp_TransactionNo"]
                    ?: payment[PaymentsTable.transactionId]
                    ?: pendingPayment.orderCode,
                gatewayResponse = queryResult.rawBody
            )) {
                awardRewardPointsIfNeeded(order)
                println("INFO: Reconciled pending VNPay user payment successfully for orderId=$orderId")
            }
        }
    }

    private fun reconcilePendingZaloPayUserPayment(
        orderId: String,
        pendingPayment: PendingUserGatewayPayment
    ) {
        val appTransId = pendingPayment.transactionId ?: return
        val metadata = resolveZaloPendingMetadata(pendingPayment.toPosGatewayPayment())
        val now = System.currentTimeMillis()
        metadata.lastQueryAtEpochMs?.let { lastQueryAt ->
            if (now - lastQueryAt < 10_000L) {
                return
            }
        }

        updatePendingZaloMetadata(
            paymentId = pendingPayment.paymentId,
            metadata = metadata.copy(lastQueryAtEpochMs = now)
        )

        val queryResponse = try {
            queryZaloPayPayment(appTransId)
        } catch (e: Exception) {
            println("WARN: ZaloPay user query reconciliation failed for orderId=$orderId - ${rootCauseMessage(e)}")
            return
        }

        if (queryResponse.returnCode != 1) {
            return
        }

        transaction {
            val order = OrdersTable
                .selectAll()
                .where { OrdersTable.id eq orderId }
                .singleOrNull()
                ?: return@transaction

            if (order[OrdersTable.status] == "CANCELLED" || order[OrdersTable.paymentStatus] == "COMPLETED") {
                return@transaction
            }

            val payment = findLatestPayment(orderId, "ZALOPAY")
                ?: return@transaction
            if (payment[PaymentsTable.status] == "COMPLETED") {
                return@transaction
            }

            val gatewayAmount = queryResponse.amount
            if (gatewayAmount != null && gatewayAmount != normalizePaymentAmount(order[OrdersTable.total])) {
                println("WARN: ZaloPay user query amount mismatch for orderId=$orderId - gateway=$gatewayAmount")
                return@transaction
            }

            if (completeSuccessfulOrderPayment(
                order = order,
                paymentId = payment[PaymentsTable.id],
                transactionId = queryResponse.zpTransId?.toString()
                    ?: payment[PaymentsTable.transactionId]
                    ?: appTransId,
                gatewayResponse = json.encodeToString(queryResponse)
            )) {
                awardRewardPointsIfNeeded(order)
                println("INFO: Reconciled pending ZaloPay user payment successfully for orderId=$orderId")
            }
        }
    }

    private fun PendingUserGatewayPayment.toPosGatewayPayment() = PendingPosGatewayPayment(
        paymentId = paymentId,
        method = method,
        orderCode = orderCode,
        amount = amount,
        transactionId = transactionId,
        gatewayMetadata = gatewayMetadata,
        createdAt = createdAt
    )

    private fun prepareMoMoPayment(
        order: ResultRow,
        redirectUrl: String,
        orderInfo: String
    ): PreparedMoMoPayment {
        val normalizedRedirectUrl = redirectUrl.trim().takeIf { it.isNotBlank() } ?: posReturnUrl
        return PreparedMoMoPayment(
            internalOrderId = order[OrdersTable.id],
            orderCode = order[OrdersTable.orderCode],
            requestId = UUID.randomUUID().toString(),
            amount = normalizePaymentAmount(order[OrdersTable.total]),
            orderInfo = orderInfo,
            redirectUrl = normalizedRedirectUrl,
            ipnUrl = resolveMoMoIpnUrl(normalizedRedirectUrl)
        )
    }

    private fun createMoMoPayment(prepared: PreparedMoMoPayment): CreatedMoMoPayment {
        val extraData = ""
        val requestPayload = MoMoCreateRequest(
            partnerCode = momoPartnerCode,
            requestId = prepared.requestId,
            amount = prepared.amount,
            orderId = prepared.orderCode,
            orderInfo = prepared.orderInfo,
            redirectUrl = prepared.redirectUrl,
            ipnUrl = prepared.ipnUrl,
            requestType = momoRequestType,
            extraData = extraData,
            signature = generateMoMoCreateSignature(
                amount = prepared.amount,
                extraData = extraData,
                ipnUrl = prepared.ipnUrl,
                orderId = prepared.orderCode,
                orderInfo = prepared.orderInfo,
                redirectUrl = prepared.redirectUrl,
                requestId = prepared.requestId,
                requestType = momoRequestType
            )
        )

        val requestBody = json.encodeToString(requestPayload)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(momoCreateUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = try {
            gatewayHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw IllegalStateException(
                "MoMo gateway unreachable at $momoCreateUrl: ${rootCauseMessage(e)}",
                e
            )
        }
        println("DEBUG: MoMo response status=${response.statusCode()}")
        println("DEBUG: MoMo response body=${response.body().take(500)}")
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "MoMo create payment failed with HTTP ${response.statusCode()}: ${response.body().take(500)}"
            )
        }

        val gatewayResponse = json.decodeFromString<MoMoCreateGatewayResponse>(response.body())
        validateMoMoCreateResponse(prepared, gatewayResponse)

        val payUrl = gatewayResponse.payUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("MoMo did not return payUrl")

        val deeplink = gatewayResponse.deeplink?.takeIf { it.isNotBlank() }
        val qrContent = gatewayResponse.qrCodeUrl?.takeIf { it.isNotBlank() }
            ?: deeplink
            ?: payUrl
        return CreatedMoMoPayment(
            paymentUrl = payUrl,
            qrContent = qrContent,
            deeplink = deeplink
        )
    }

    private fun queryMoMoPayment(orderCode: String): MoMoQueryResponse {
        val queryRequestId = UUID.randomUUID().toString()
        val requestPayload = MoMoQueryRequest(
            partnerCode = momoPartnerCode,
            requestId = queryRequestId,
            orderId = orderCode,
            signature = generateMoMoQuerySignature(
                orderId = orderCode,
                requestId = queryRequestId
            )
        )
        val requestBody = json.encodeToString(requestPayload)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(momoQueryUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = gatewayHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "MoMo query payment failed with HTTP ${response.statusCode()}: ${response.body().take(500)}"
            )
        }

        val gatewayResponse = json.decodeFromString<MoMoQueryResponse>(response.body())
        println(
            "DEBUG: MoMo query result - orderCode=$orderCode, resultCode=${gatewayResponse.resultCode}, transId=${gatewayResponse.transId}"
        )
        return gatewayResponse
    }

    private fun validateMoMoCreateResponse(
        prepared: PreparedMoMoPayment,
        response: MoMoCreateGatewayResponse
    ) {
        val resultCode = response.resultCode ?: throw IllegalStateException("MoMo create payment missing resultCode")
        if (resultCode != 0) {
            throw IllegalStateException(response.message ?: "MoMo create payment failed with resultCode $resultCode")
        }

        if (response.partnerCode != momoPartnerCode) {
            throw IllegalStateException("MoMo create payment returned invalid partnerCode")
        }
        if (response.requestId != prepared.requestId) {
            throw IllegalStateException("MoMo create payment returned unexpected requestId")
        }
        if (response.orderId != prepared.orderCode) {
            throw IllegalStateException("MoMo create payment returned unexpected orderId")
        }
        if (response.amount != prepared.amount) {
            throw IllegalStateException("MoMo create payment returned unexpected amount")
        }

        val responseSignature = response.signature?.takeIf { it.isNotBlank() }
        if (responseSignature == null) {
            println("WARN: MoMo create payment response missing signature; skipping response signature validation")
            return
        }
        val expectedSignature = generateMoMoCreateResponseSignature(response)
        if (!responseSignature.equals(expectedSignature, ignoreCase = true)) {
            throw IllegalStateException("MoMo create payment signature mismatch")
        }
    }

    private fun resolveMoMoIpnUrl(redirectUrl: String): String {
        momoIpnUrl?.let { return it }
        paymentPublicBaseUrl?.let { return "$it/api/v1/webhooks/momo/callback" }
        deriveBaseUrl(redirectUrl)?.let { return "$it/api/v1/webhooks/momo/callback" }
        return "http://localhost:8080/api/v1/webhooks/momo/callback"
    }

    private fun deriveBaseUrl(url: String): String? {
        return runCatching {
            val uri = URI.create(url.trim())
            val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = when {
                uri.port < 0 -> ""
                scheme == "http" && uri.port == 80 -> ""
                scheme == "https" && uri.port == 443 -> ""
                else -> ":${uri.port}"
            }
            "$scheme://$host$port"
        }.getOrNull()
    }

    private fun getOwnedOrderForPayment(userId: String, orderId: String): ResultRow {
        val order = OrdersTable
            .selectAll()
            .where { (OrdersTable.id eq orderId) and (OrdersTable.userId eq userId) }
            .singleOrNull()
            ?: throw IllegalArgumentException("Order not found or access denied")

        if (order[OrdersTable.paymentStatus] == "COMPLETED") {
            throw IllegalArgumentException("Order already paid")
        }

        return order
    }

    private fun getPosOrderForPayment(orderId: String, method: String): ResultRow {
        val order = OrdersTable
            .selectAll()
            .where { (OrdersTable.id eq orderId) and (OrdersTable.orderChannel eq "POS") }
            .singleOrNull()
            ?: throw IllegalArgumentException("POS order not found")

        if (order[OrdersTable.paymentStatus] == "COMPLETED") {
            throw IllegalArgumentException("POS order already paid")
        }

        if (order[OrdersTable.paymentMethod] != method) {
            throw IllegalArgumentException("POS order payment method does not match requested gateway")
        }

        return order
    }

    private fun upsertPendingPayment(
        orderId: String,
        method: String,
        amount: BigDecimal,
        transactionId: String?,
        gatewayResponse: String? = null
    ): String {
        val existing = findLatestPayment(orderId, method)
        return if (existing != null && existing[PaymentsTable.status] != "COMPLETED") {
            PaymentsTable.update({ PaymentsTable.id eq existing[PaymentsTable.id] }) {
                it[PaymentsTable.amount] = amount
                it[PaymentsTable.transactionId] = transactionId
                it[PaymentsTable.status] = "PENDING"
                it[PaymentsTable.paymentGatewayResponse] = gatewayResponse
                it[PaymentsTable.paidAt] = null
            }
            existing[PaymentsTable.id]
        } else {
            val paymentId = UUID.randomUUID().toString()
            PaymentsTable.insert {
                it[id] = paymentId
                it[PaymentsTable.orderId] = orderId
                it[PaymentsTable.method] = method
                it[PaymentsTable.amount] = amount
                it[PaymentsTable.transactionId] = transactionId
                it[PaymentsTable.paymentGatewayResponse] = gatewayResponse
                it[status] = "PENDING"
            }
            paymentId
        }
    }

    private fun findLatestPayment(orderId: String, method: String): ResultRow? {
        return PaymentsTable
            .selectAll()
            .where { (PaymentsTable.orderId eq orderId) and (PaymentsTable.method eq method) }
            .orderBy(PaymentsTable.createdAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
    }

    private fun findLatestPaymentByTransactionId(method: String, transactionId: String): ResultRow? {
        return PaymentsTable
            .selectAll()
            .where {
                (PaymentsTable.method eq method) and
                    (PaymentsTable.transactionId eq transactionId)
            }
            .orderBy(PaymentsTable.createdAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
    }

    private fun markOrderPaymentPending(orderId: String, method: String) {
        OrdersTable.update({ OrdersTable.id eq orderId }) {
            it[paymentMethod] = method
            it[paymentStatus] = "PENDING"
        }
    }

    private fun completeSuccessfulOrderPayment(
        order: ResultRow,
        paymentId: String,
        transactionId: String,
        gatewayResponse: String
    ): Boolean {
        val orderId = order[OrdersTable.id]
        val isPosOrder = order[OrdersTable.orderChannel] == "POS"
        val completedAt = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        if (order[OrdersTable.status] == "CANCELLED") {
            return false
        }

        if (isPosOrder) {
            deductInventoryForOrder(orderId)
        }

        PaymentsTable.update({ PaymentsTable.id eq paymentId }) {
            it[status] = "COMPLETED"
            it[PaymentsTable.transactionId] = transactionId
            it[PaymentsTable.paymentGatewayResponse] = gatewayResponse
            it[paidAt] = completedAt
        }

        OrdersTable.update({ OrdersTable.id eq orderId }) {
            it[paymentStatus] = "COMPLETED"
            it[status] = if (isPosOrder) "DELIVERED" else "PROCESSING"
            if (isPosOrder) {
                it[OrdersTable.completedAt] = completedAt
            }
        }

        return true
    }

    private fun deductInventoryForOrder(orderId: String) {
        val items = OrderItemsTable
            .selectAll()
            .where { OrderItemsTable.orderId eq orderId }
            .toList()

        items.forEach { item ->
            val productId = item[OrderItemsTable.productId] ?: return@forEach
            val product = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq productId }
                .singleOrNull()
                ?: throw IllegalArgumentException("Product not found: $productId")

            if (product[ProductsTable.stock] < item[OrderItemsTable.quantity]) {
                throw IllegalArgumentException("Insufficient stock for product: ${product[ProductsTable.name]}")
            }
        }

        items.forEach { item ->
            val productId = item[OrderItemsTable.productId] ?: return@forEach
            ProductsTable.update({ ProductsTable.id eq productId }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it.update(ProductsTable.stock, ProductsTable.stock - item[OrderItemsTable.quantity])
                }
            }
        }
    }

    private fun awardRewardPointsIfNeeded(order: ResultRow) {
        val orderId = order[OrdersTable.id]
        val orderUserId = order[OrdersTable.userId] ?: return
        val existingReward = RewardTransactionsTable
            .selectAll()
            .where {
                (RewardTransactionsTable.orderId eq orderId) and
                (RewardTransactionsTable.type eq "EARN")
            }
            .firstOrNull()

        if (existingReward != null) {
            return
        }

        val pointsEarned = order[OrdersTable.pointsEarned]
        if (pointsEarned <= 0) {
            return
        }

        RewardAccountsTable.update({ RewardAccountsTable.userId eq orderUserId }) {
            with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                it[RewardAccountsTable.totalPoints] = RewardAccountsTable.totalPoints + pointsEarned
            }
        }

        RewardTransactionsTable.insert {
            it[id] = UUID.randomUUID().toString()
            it[userId] = orderUserId
            it[RewardTransactionsTable.orderId] = orderId
            it[type] = "EARN"
            it[points] = pointsEarned
            it[description] = "Thanh toan thanh cong don hang ${order[OrdersTable.orderCode]}"
        }
    }

    private fun normalizePaymentAmount(total: BigDecimal?): Long {
        val amount = total ?: throw IllegalArgumentException("Order total is missing")
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    private fun normalizeGatewayAmount(total: BigDecimal?): Long {
        return normalizePaymentAmount(total) * 100
    }

    private fun generateVNPaySecureHash(params: Map<String, String>): String {
        val hashData = buildVNPayCanonicalQuery(params)
        println("DEBUG: VNPay hash data = $hashData")

        val hmac = Mac.getInstance("HmacSHA512")
        val key = SecretKeySpec(vnpaySecretKey.toByteArray(Charsets.UTF_8), "HmacSHA512")
        hmac.init(key)
        val hash = hmac.doFinal(hashData.toByteArray(StandardCharsets.US_ASCII))
        val hashString = hash.joinToString("") { "%02x".format(it) }
        println("DEBUG: Generated VNPay hash = $hashString")
        return hashString
    }

    private fun generateVNPayQuerySecureHash(
        requestId: String,
        txnRef: String,
        transactionDate: String,
        createDate: String,
        ipAddr: String,
        orderInfo: String
    ): String {
        val rawData = listOf(
            requestId,
            vnpayVersion,
            "querydr",
            vnpayTmnCode,
            txnRef,
            transactionDate,
            createDate,
            ipAddr,
            orderInfo
        ).joinToString("|")
        return hmacSha512(vnpaySecretKey, rawData)
    }

    private fun generateVNPayQueryResponseHash(fields: Map<String, String>): String {
        val rawData = listOf(
            fields["vnp_ResponseId"].orEmpty(),
            fields["vnp_Command"].orEmpty(),
            fields["vnp_ResponseCode"].orEmpty(),
            fields["vnp_Message"].orEmpty(),
            fields["vnp_TmnCode"].orEmpty(),
            fields["vnp_TxnRef"].orEmpty(),
            fields["vnp_Amount"].orEmpty(),
            fields["vnp_BankCode"].orEmpty(),
            fields["vnp_PayDate"].orEmpty(),
            fields["vnp_TransactionNo"].orEmpty(),
            fields["vnp_TransactionType"].orEmpty(),
            fields["vnp_TransactionStatus"].orEmpty(),
            fields["vnp_OrderInfo"].orEmpty(),
            fields["vnp_PromotionCode"].orEmpty(),
            fields["vnp_PromotionAmount"].orEmpty()
        ).joinToString("|")
        return hmacSha512(vnpaySecretKey, rawData)
    }

    private fun generateZaloSignature(data: String, key: String): String {
        val hmac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(key.toByteArray(), "HmacSHA256")
        hmac.init(keySpec)
        val hash = hmac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun generateMoMoCreateSignature(
        amount: Long,
        extraData: String,
        ipnUrl: String,
        orderId: String,
        orderInfo: String,
        redirectUrl: String,
        requestId: String,
        requestType: String
    ): String {
        val rawData = "accessKey=$momoAccessKey&amount=$amount&extraData=$extraData&ipnUrl=$ipnUrl&orderId=$orderId&orderInfo=$orderInfo&partnerCode=$momoPartnerCode&redirectUrl=$redirectUrl&requestId=$requestId&requestType=$requestType"
        val hmac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(momoSecretKey.toByteArray(), "HmacSHA256")
        hmac.init(keySpec)
        val hash = hmac.doFinal(rawData.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun generateMoMoCreateResponseSignature(response: MoMoCreateGatewayResponse): String {
        val rawData = "accessKey=$momoAccessKey&amount=${response.amount}" +
            "&message=${response.message.orEmpty()}" +
            "&orderId=${response.orderId.orEmpty()}" +
            "&partnerCode=${response.partnerCode.orEmpty()}" +
            "&payUrl=${response.payUrl.orEmpty()}" +
            "&requestId=${response.requestId.orEmpty()}" +
            "&responseTime=${response.responseTime}" +
            "&resultCode=${response.resultCode}"
        val hmac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(momoSecretKey.toByteArray(), "HmacSHA256")
        hmac.init(keySpec)
        val hash = hmac.doFinal(rawData.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun generateMoMoQuerySignature(
        orderId: String,
        requestId: String
    ): String {
        val rawData = "accessKey=$momoAccessKey&orderId=$orderId&partnerCode=$momoPartnerCode&requestId=$requestId"
        val hmac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(momoSecretKey.toByteArray(), "HmacSHA256")
        hmac.init(keySpec)
        val hash = hmac.doFinal(rawData.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun generateMoMoCallbackSignature(params: Map<String, String>): String {
        val rawData = buildString {
            append("accessKey=").append(momoAccessKey)
            append("&amount=").append(params["amount"].orEmpty())
            append("&extraData=").append(params["extraData"].orEmpty())
            append("&message=").append(params["message"].orEmpty())
            append("&orderId=").append(params["orderId"].orEmpty())
            append("&orderInfo=").append(params["orderInfo"].orEmpty())
            append("&orderType=").append(params["orderType"].orEmpty())
            append("&partnerCode=").append(params["partnerCode"].orEmpty())
            append("&payType=").append(params["payType"].orEmpty())
            append("&requestId=").append(params["requestId"].orEmpty())
            append("&responseTime=").append(params["responseTime"].orEmpty())
            append("&resultCode=").append(params["resultCode"].orEmpty())
            append("&transId=").append(params["transId"].orEmpty())
        }

        val hmac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(momoSecretKey.toByteArray(), "HmacSHA256")
        hmac.init(keySpec)
        val hash = hmac.doFinal(rawData.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha512(secret: String, data: String): String {
        val hmac = Mac.getInstance("HmacSHA512")
        val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA512")
        hmac.init(key)
        val hash = hmac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun buildVNPayUrl(params: Map<String, String>): String {
        val baseQuery = buildVNPayCanonicalQuery(params)
        val secureHash = params["vnp_SecureHash"]?.takeIf { it.isNotBlank() }
        return if (secureHash != null) {
            "$vnpayUrl?$baseQuery&vnp_SecureHash=${URLEncoder.encode(secureHash, StandardCharsets.US_ASCII.toString())}"
        } else {
            "$vnpayUrl?$baseQuery"
        }
    }

    private fun getFormattedDateTime(): String {
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmss")
        dateFormat.timeZone = JavaTimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        return dateFormat.format(Date())
    }

    private fun formatVNPayDateTime(dateTime: LocalDateTime): String {
        return buildString {
            append(dateTime.year.toString().padStart(4, '0'))
            append(dateTime.monthNumber.toString().padStart(2, '0'))
            append(dateTime.dayOfMonth.toString().padStart(2, '0'))
            append(dateTime.hour.toString().padStart(2, '0'))
            append(dateTime.minute.toString().padStart(2, '0'))
            append(dateTime.second.toString().padStart(2, '0'))
        }
    }

    private fun buildZaloAppTransId(): String {
        val dateFormat = SimpleDateFormat("yyMMdd")
        dateFormat.timeZone = JavaTimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        val suffix = (Math.abs(UUID.randomUUID().hashCode()) % 1_000_000).toString().padStart(6, '0')
        return "${dateFormat.format(Date())}_$suffix"
    }

    private fun buildZaloEmbedData(
        redirectUrl: String,
        orderCode: String
    ): String {
        val columnInfo = json.encodeToString(
            mapOf(
                "branch_id" to zaloPosBranchId,
                "store_id" to zaloPosStoreId,
                "store_name" to zaloPosStoreName,
                "order_code" to orderCode
            )
        )
        return json.encodeToString(
            mapOf(
                "redirecturl" to redirectUrl,
                "columninfo" to columnInfo
            )
        )
    }

    private fun buildFormUrlEncodedBody(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=" +
                URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
        }
    }

    private fun buildVNPayCanonicalQuery(params: Map<String, String>): String {
        return params.entries
            .filter { (key, value) ->
                key != "vnp_SecureHash" && key != "vnp_SecureHashType" && value.isNotBlank()
            }
            .sortedBy { it.key }
            .joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, StandardCharsets.US_ASCII.toString())}=" +
                    URLEncoder.encode(value, StandardCharsets.US_ASCII.toString())
            }
    }

    private fun isVNPaySuccessCode(code: String?): Boolean {
        return code == "00" || code == "0"
    }

    private fun isVNPayQuerySettledSuccess(status: String?): Boolean {
        return status == "00"
    }

    private fun buildPendingVNPayMetadata(
        createDate: String,
        lastQueryAtEpochMs: Long? = null
    ): String {
        return json.encodeToString(
            VNPayPendingMetadata(
                createDate = createDate,
                lastQueryAtEpochMs = lastQueryAtEpochMs
            )
        )
    }

    private fun resolveVNPayPendingMetadata(pendingPayment: PendingPosGatewayPayment): VNPayPendingMetadata {
        pendingPayment.gatewayMetadata
            ?.takeIf { it.isNotBlank() }
            ?.let { metadata ->
                runCatching {
                    json.decodeFromString<VNPayPendingMetadata>(metadata)
                }.getOrNull()?.let { return it }
            }

        return VNPayPendingMetadata(createDate = formatVNPayDateTime(pendingPayment.createdAt))
    }

    private fun updatePendingVNPayMetadata(paymentId: String, metadata: VNPayPendingMetadata) {
        transaction {
            PaymentsTable.update({ PaymentsTable.id eq paymentId }) {
                it[PaymentsTable.paymentGatewayResponse] = json.encodeToString(metadata)
            }
        }
    }

    private fun parseVNPayDateTimeToEpochMs(value: String): Long? {
        return runCatching {
            val dateFormat = SimpleDateFormat("yyyyMMddHHmmss")
            dateFormat.timeZone = JavaTimeZone.getTimeZone("Asia/Ho_Chi_Minh")
            dateFormat.parse(value)?.time
        }.getOrNull()
    }

    private fun resolveVNPayTransactionDate(pendingPayment: PendingPosGatewayPayment): String {
        return resolveVNPayPendingMetadata(pendingPayment).createDate
    }

    private fun buildPendingZaloMetadata(
        lastQueryAtEpochMs: Long? = null,
        redirectSeenAtEpochMs: Long? = null
    ): String {
        return json.encodeToString(
            ZaloPayPendingMetadata(
                lastQueryAtEpochMs = lastQueryAtEpochMs,
                redirectSeenAtEpochMs = redirectSeenAtEpochMs
            )
        )
    }

    private fun resolveZaloPendingMetadata(pendingPayment: PendingPosGatewayPayment): ZaloPayPendingMetadata {
        pendingPayment.gatewayMetadata
            ?.takeIf { it.isNotBlank() }
            ?.let { metadata ->
                runCatching {
                    json.decodeFromString<ZaloPayPendingMetadata>(metadata)
                }.getOrNull()?.let { return it }
            }
        return ZaloPayPendingMetadata()
    }

    private fun updatePendingZaloMetadata(paymentId: String, metadata: ZaloPayPendingMetadata) {
        transaction {
            PaymentsTable.update({ PaymentsTable.id eq paymentId }) {
                it[PaymentsTable.paymentGatewayResponse] = json.encodeToString(metadata)
            }
        }
    }

    private fun queryVNPayPayment(
        orderCode: String,
        transactionDate: String
    ): VNPayQueryResult {
        val requestId = UUID.randomUUID().toString().replace("-", "").take(32)
        val createDate = getFormattedDateTime()
        val ipAddress = "127.0.0.1"
        val orderInfo = "Query POS $orderCode"
        val requestPayload = linkedMapOf(
            "vnp_RequestId" to requestId,
            "vnp_Version" to vnpayVersion,
            "vnp_Command" to "querydr",
            "vnp_TmnCode" to vnpayTmnCode,
            "vnp_TxnRef" to orderCode,
            "vnp_OrderInfo" to orderInfo,
            "vnp_TransactionDate" to transactionDate,
            "vnp_CreateDate" to createDate,
            "vnp_IpAddr" to ipAddress
        )
        requestPayload["vnp_SecureHash"] = generateVNPayQuerySecureHash(
            requestId = requestId,
            txnRef = orderCode,
            transactionDate = transactionDate,
            createDate = createDate,
            ipAddr = ipAddress,
            orderInfo = orderInfo
        )

        val requestBody = json.encodeToString(requestPayload)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(vnpayQueryUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = gatewayHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
        println("DEBUG: VNPay query status=${response.statusCode()}")
        println("DEBUG: VNPay query body=${response.body()}")
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("VNPay query failed with status ${response.statusCode()}")
        }

        val responseFields = json.parseToJsonElement(response.body()).jsonObject.mapValues { (_, value) ->
            value.jsonPrimitive.contentOrNull ?: value.toString()
        }
        validateVNPayQueryResponse(responseFields)

        return VNPayQueryResult(
            fields = responseFields,
            rawBody = response.body()
        )
    }

    private fun validateVNPayQueryResponse(fields: Map<String, String>) {
        val responseCode = fields["vnp_ResponseCode"]
        val secureHash = fields["vnp_SecureHash"]
        if (secureHash.isNullOrBlank()) {
            if (responseCode != "00") {
                return
            }
            throw IllegalStateException("VNPay query response missing secure hash")
        }
        val calculatedHash = generateVNPayQueryResponseHash(fields)
        if (!calculatedHash.equals(secureHash, ignoreCase = true)) {
            throw IllegalStateException("VNPay query response invalid secure hash")
        }
    }

    private fun queryZaloPayPayment(appTransId: String): ZaloPayQueryResponse {
        val requestParams = linkedMapOf(
            "app_id" to zaloAppId,
            "app_trans_id" to appTransId
        )
        val signaturePayload = "${requestParams.getValue("app_id")}|${requestParams.getValue("app_trans_id")}|$zaloKey1"
        requestParams["mac"] = generateZaloSignature(signaturePayload, zaloKey1)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(zaloQueryUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(buildFormUrlEncodedBody(requestParams)))
            .build()

        val response = gatewayHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
        println("DEBUG: ZaloPay query status=${response.statusCode()}")
        println("DEBUG: ZaloPay query body=${response.body()}")
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("ZaloPay query failed with HTTP ${response.statusCode()}: ${response.body().take(500)}")
        }

        val fields = json.parseToJsonElement(response.body()).jsonObject.mapValues { (_, value) ->
            value.jsonPrimitive.contentOrNull ?: value.toString()
        }
        val queryResponse = ZaloPayQueryResponse(
            returnCode = fields["return_code"]?.toIntOrNull() ?: fields["returncode"]?.toIntOrNull(),
            returnMessage = fields["return_message"] ?: fields["returnmessage"],
            subReturnCode = fields["sub_return_code"]?.toIntOrNull() ?: fields["subreturncode"]?.toIntOrNull(),
            subReturnMessage = fields["sub_return_message"] ?: fields["subreturnmessage"],
            isProcessing = fields["is_processing"]?.toBooleanStrictOrNull()
                ?: fields["isprocessing"]?.toBooleanStrictOrNull(),
            amount = fields["amount"]?.toLongOrNull(),
            discountAmount = fields["discount_amount"]?.toLongOrNull() ?: fields["discountamount"]?.toLongOrNull(),
            zpTransId = fields["zp_trans_id"]?.toLongOrNull() ?: fields["zptransid"]?.toLongOrNull()
        )
        println(
            "DEBUG: ZaloPay query result - appTransId=$appTransId, " +
                "returnCode=${queryResponse.returnCode}, isProcessing=${queryResponse.isProcessing}, zpTransId=${queryResponse.zpTransId}"
        )
        return queryResponse
    }

    private fun reconcilePendingMoMoPosPayment(
        orderId: String,
        pendingPayment: PendingPosGatewayPayment
    ) {
        val queryResponse = try {
            queryMoMoPayment(orderCode = pendingPayment.orderCode)
        } catch (e: Exception) {
            println("WARN: MoMo query reconciliation failed for orderId=$orderId - ${rootCauseMessage(e)}")
            return
        }

        if (queryResponse.resultCode != 0) {
            return
        }

        transaction {
            val order = OrdersTable
                .selectAll()
                .where { (OrdersTable.id eq orderId) and (OrdersTable.orderChannel eq "POS") }
                .singleOrNull()
                ?: return@transaction

            if (order[OrdersTable.status] == "CANCELLED") {
                return@transaction
            }

            if (order[OrdersTable.paymentStatus] == "COMPLETED") {
                return@transaction
            }

            val payment = findLatestPayment(orderId, "MOMO")
                ?: return@transaction
            if (payment[PaymentsTable.status] == "COMPLETED") {
                return@transaction
            }

            val gatewayAmount = queryResponse.amount
            if (gatewayAmount != null && gatewayAmount != normalizePaymentAmount(order[OrdersTable.total])) {
                println("WARN: MoMo query amount mismatch for orderId=$orderId - gateway=$gatewayAmount")
                return@transaction
            }

            if (completeSuccessfulOrderPayment(
                order = order,
                paymentId = payment[PaymentsTable.id],
                transactionId = queryResponse.transId?.toString()
                    ?: payment[PaymentsTable.transactionId]
                    ?: pendingPayment.orderCode,
                gatewayResponse = json.encodeToString(queryResponse)
            )) {
                awardRewardPointsIfNeeded(order)
                println("INFO: Reconciled pending MoMo POS payment successfully for orderId=$orderId")
            }
        }
    }

    private fun reconcilePendingVNPayPosPayment(
        orderId: String,
        pendingPayment: PendingPosGatewayPayment
    ) {
        val metadata = resolveVNPayPendingMetadata(pendingPayment)
        val now = System.currentTimeMillis()
        val createdAtEpoch = parseVNPayDateTimeToEpochMs(metadata.createDate)
        if (createdAtEpoch != null && now - createdAtEpoch < 30_000L) {
            println("DEBUG: VNPay query waiting for settlement window - orderCode=${pendingPayment.orderCode}")
            return
        }
        metadata.lastQueryAtEpochMs?.let { lastQueryAt ->
            if (now - lastQueryAt < 5 * 60 * 1000L) {
                return
            }
        }

        updatePendingVNPayMetadata(
            paymentId = pendingPayment.paymentId,
            metadata = metadata.copy(lastQueryAtEpochMs = now)
        )

        val transactionDate = metadata.createDate
        val queryResult = try {
            queryVNPayPayment(
                orderCode = pendingPayment.orderCode,
                transactionDate = transactionDate
            )
        } catch (e: Exception) {
            println("WARN: VNPay query reconciliation failed for orderId=$orderId - ${rootCauseMessage(e)}")
            return
        }

        val fields = queryResult.fields
        println(
            "DEBUG: VNPay query result - orderCode=${pendingPayment.orderCode}, " +
                "responseCode=${fields["vnp_ResponseCode"]}, " +
                "transactionStatus=${fields["vnp_TransactionStatus"]}, " +
                "transactionNo=${fields["vnp_TransactionNo"]}"
        )

        if (fields["vnp_ResponseCode"] == "94") {
            println("DEBUG: VNPay query skipped due to duplicate-window cooldown for orderCode=${pendingPayment.orderCode}")
            return
        }

        if (!isVNPaySuccessCode(fields["vnp_ResponseCode"]) || !isVNPayQuerySettledSuccess(fields["vnp_TransactionStatus"])) {
            return
        }

        transaction {
            val order = OrdersTable
                .selectAll()
                .where { (OrdersTable.id eq orderId) and (OrdersTable.orderChannel eq "POS") }
                .singleOrNull()
                ?: return@transaction

            if (order[OrdersTable.status] == "CANCELLED") {
                return@transaction
            }

            if (order[OrdersTable.paymentStatus] == "COMPLETED") {
                return@transaction
            }

            val payment = findLatestPayment(orderId, "VNPAY")
                ?: return@transaction
            if (payment[PaymentsTable.status] == "COMPLETED") {
                return@transaction
            }

            val gatewayAmount = fields["vnp_Amount"]?.toLongOrNull()
            if (gatewayAmount != null && gatewayAmount != normalizeGatewayAmount(order[OrdersTable.total])) {
                println("WARN: VNPay query amount mismatch for orderId=$orderId - gateway=$gatewayAmount")
                return@transaction
            }

            if (completeSuccessfulOrderPayment(
                order = order,
                paymentId = payment[PaymentsTable.id],
                transactionId = fields["vnp_TransactionNo"]
                    ?: payment[PaymentsTable.transactionId]
                    ?: pendingPayment.orderCode,
                gatewayResponse = queryResult.rawBody
            )) {
                awardRewardPointsIfNeeded(order)
                println("INFO: Reconciled pending VNPay POS payment successfully for orderId=$orderId")
            }
        }
    }

    private fun reconcilePendingZaloPayPosPayment(
        orderId: String,
        pendingPayment: PendingPosGatewayPayment
    ) {
        val appTransId = pendingPayment.transactionId ?: return
        val metadata = resolveZaloPendingMetadata(pendingPayment)
        val now = System.currentTimeMillis()
        metadata.lastQueryAtEpochMs?.let { lastQueryAt ->
            if (now - lastQueryAt < 10_000L) {
                return
            }
        }

        updatePendingZaloMetadata(
            paymentId = pendingPayment.paymentId,
            metadata = metadata.copy(lastQueryAtEpochMs = now)
        )

        val queryResponse = try {
            queryZaloPayPayment(appTransId)
        } catch (e: Exception) {
            println("WARN: ZaloPay query reconciliation failed for orderId=$orderId - ${rootCauseMessage(e)}")
            return
        }

        if (queryResponse.returnCode != 1) {
            return
        }

        transaction {
            val order = OrdersTable
                .selectAll()
                .where { (OrdersTable.id eq orderId) and (OrdersTable.orderChannel eq "POS") }
                .singleOrNull()
                ?: return@transaction

            if (order[OrdersTable.status] == "CANCELLED") {
                return@transaction
            }

            if (order[OrdersTable.paymentStatus] == "COMPLETED") {
                return@transaction
            }

            val payment = findLatestPayment(orderId, "ZALOPAY")
                ?: return@transaction
            if (payment[PaymentsTable.status] == "COMPLETED") {
                return@transaction
            }

            val gatewayAmount = queryResponse.amount
            if (gatewayAmount != null && gatewayAmount != normalizePaymentAmount(order[OrdersTable.total])) {
                println("WARN: ZaloPay query amount mismatch for orderId=$orderId - gateway=$gatewayAmount")
                return@transaction
            }

            if (completeSuccessfulOrderPayment(
                order = order,
                paymentId = payment[PaymentsTable.id],
                transactionId = queryResponse.zpTransId?.toString()
                    ?: payment[PaymentsTable.transactionId]
                    ?: appTransId,
                gatewayResponse = json.encodeToString(queryResponse)
            )) {
                awardRewardPointsIfNeeded(order)
                println("INFO: Reconciled pending ZaloPay POS payment successfully for orderId=$orderId")
            }
        }
    }

    private fun resolveMoMoCreateUrl(configuredUrl: String?): String {
        val trimmed = configuredUrl?.trim()?.takeIf { it.isNotBlank() }
        if (trimmed != null) {
            return if (paymentEnv != "production" && trimmed.contains("uat.momo.vn", ignoreCase = true)) {
                "https://test-payment.momo.vn/v2/gateway/api/create"
            } else {
                trimmed
            }
        }
        return when (paymentEnv) {
            "production" -> "https://payment.momo.vn/v2/gateway/api/create"
            else -> "https://test-payment.momo.vn/v2/gateway/api/create"
        }
    }

    private fun resolveVNPayQueryUrl(configuredUrl: String?): String {
        val trimmed = configuredUrl?.trim()?.takeIf { it.isNotBlank() }
        if (trimmed != null) {
            return trimmed
        }

        return runCatching {
            val baseUri = URI.create(vnpayUrl)
            "${baseUri.scheme}://${baseUri.host}/merchant_webapi/api/transaction"
        }.getOrDefault("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction")
    }

    private fun resolveMoMoQueryUrl(configuredUrl: String?): String {
        val trimmed = configuredUrl?.trim()?.takeIf { it.isNotBlank() }
        if (trimmed != null) {
            return if (paymentEnv != "production" && trimmed.contains("uat.momo.vn", ignoreCase = true)) {
                "https://test-payment.momo.vn/v2/gateway/api/query"
            } else {
                trimmed
            }
        }
        return when (paymentEnv) {
            "production" -> "https://payment.momo.vn/v2/gateway/api/query"
            else -> "https://test-payment.momo.vn/v2/gateway/api/query"
        }
    }

    private fun resolveZaloCreateUrl(configuredUrl: String?): String {
        val trimmed = configuredUrl?.trim()?.takeIf { it.isNotBlank() }
        if (trimmed != null) {
            return when {
                paymentEnv == "production" -> trimmed.replace("sb-openapi.zalopay.vn", "openapi.zalopay.vn")
                trimmed.contains("api.zalopay.vn/v2/create", ignoreCase = true) -> "https://sb-openapi.zalopay.vn/v2/create"
                trimmed.contains("openapi.zalopay.vn/v2/create", ignoreCase = true) && !trimmed.contains("sb-openapi", ignoreCase = true) ->
                    "https://sb-openapi.zalopay.vn/v2/create"
                else -> trimmed
            }
        }
        return when (paymentEnv) {
            "production" -> "https://openapi.zalopay.vn/v2/create"
            else -> "https://sb-openapi.zalopay.vn/v2/create"
        }
    }

    private fun resolveZaloQueryUrl(configuredUrl: String?): String {
        val trimmed = configuredUrl?.trim()?.takeIf { it.isNotBlank() }
        if (trimmed != null) {
            return when {
                paymentEnv == "production" -> trimmed.replace("sb-openapi.zalopay.vn", "openapi.zalopay.vn")
                trimmed.contains("openapi.zalopay.vn/v2/query", ignoreCase = true) && !trimmed.contains("sb-openapi", ignoreCase = true) ->
                    "https://sb-openapi.zalopay.vn/v2/query"
                else -> trimmed
            }
        }
        return when (paymentEnv) {
            "production" -> "https://openapi.zalopay.vn/v2/query"
            else -> "https://sb-openapi.zalopay.vn/v2/query"
        }
    }

    private fun resolveZaloCallbackUrl(redirectUrl: String): String {
        Env.get("ZALO_CALLBACK_URL")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        paymentPublicBaseUrl?.let { return "$it/api/v1/webhooks/zalopay/callback" }
        deriveBaseUrl(redirectUrl)?.let { return "$it/api/v1/webhooks/zalopay/callback" }
        return "http://localhost:8080/api/v1/webhooks/zalopay/callback"
    }

    private fun rootCauseMessage(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val message = root.message?.takeIf { it.isNotBlank() }
        return if (message != null) {
            "${root.javaClass.simpleName}: $message"
        } else {
            root.javaClass.simpleName
        }
    }

    private fun requireEnv(key: String): String {
        return Env.get(key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing required environment variable: $key")
    }
}
