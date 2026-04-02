package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.text.SimpleDateFormat
import java.util.TimeZone

// ─────────────────────────────────────────────────────────────
// DTOs
// ─────────────────────────────────────────────────────────────

data class PaymentInitRequest(
    val orderId: String,
    val amount: Long,
    val returnUrl: String
)

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
    val status: String
)

data class PaymentCallbackRequest(
    val vnpAmount: String? = null,
    val vnpBankCode: String? = null,
    val vnpBankTranNo: String? = null,
    val vnpCardType: String? = null,
    val vnpOrderInfo: String? = null,
    val vnpPayDate: String? = null,
    val vnpResponseCode: String? = null,
    val vnpTmnCode: String? = null,
    val vnpTransactionNo: String? = null,
    val vnpTransactionStatus: String? = null,
    val vnpTxnRef: String? = null,
    val vnpSecureHash: String? = null,
    // MoMo fields
    val requestId: String? = null,
    val orderId: String? = null,
    val message: String? = null,
    val resultCode: String? = null,
    val responseTime: String? = null,
    val signature: String? = null
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

// ─────────────────────────────────────────────────────────────
// SERVICE
// ─────────────────────────────────────────────────────────────

class PaymentService {

    private val VNPAY_URL = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html"  // Sandbox
    private val VNPAY_PRODUCTION_URL = "https://api.vnpay.vn/paymentv2/vpcpay.html"
    // In production, set these from environment variables
    private val VNPAY_TMN_CODE = System.getenv("VNPAY_TMN_CODE") ?: "TMNCODE123456"
    private val VNPAY_SECRET_KEY = System.getenv("VNPAY_SECRET_KEY") ?: "SECRETKEY1234567890"
    private val VNPAY_VERSION = "2.1.0"

    private val MOMO_URL = "https://test-payment.momo.vn/v2/gateway/api/create"  // Test
    private val MOMO_PRODUCTION_URL = "https://payment.momo.vn/v2/gateway/api/create"
    private val MOMO_ACCESS_KEY = System.getenv("MOMO_ACCESS_KEY") ?: "F8590EC042C7dada51B0C9FC0AB0FA12"
    private val MOMO_SECRET_KEY = System.getenv("MOMO_SECRET_KEY") ?: "NFMMO123456789NFMMOSECRET"
    private val MOMO_PARTNER_CODE = System.getenv("MOMO_PARTNER_CODE") ?: "MOMOTP1234NFMMOSECRET"

    // ─────────────────────────────────────────────────────────────
    // VNPAY PAYMENT
    // ─────────────────────────────────────────────────────────────

    fun initiateVNPayment(orderId: String, amount: Long, returnUrl: String): VNPayResponse {
        return transaction {
            val order = OrdersTable
                .selectAll()
                .where { OrdersTable.id eq orderId }
                .singleOrNull()
                ?: throw IllegalArgumentException("Đơn hàng không tìm thấy")

            val orderCode = order[OrdersTable.orderCode]

            // Build VNPay URL parameters
            val vnpParams = mutableMapOf(
                "vnp_Version" to VNPAY_VERSION,
                "vnp_Command" to "pay",
                "vnp_TmnCode" to VNPAY_TMN_CODE,
                "vnp_Amount" to (amount * 100).toString(),  // VNPay expects amount in cents
                "vnp_CurrCode" to "VND",
                "vnp_TxnRef" to orderCode,
                "vnp_OrderInfo" to "Thanh toan don hang $orderCode",
                "vnp_OrderType" to "other",
                "vnp_Locale" to "vn",
                "vnp_ReturnUrl" to returnUrl,
                "vnp_IpAddr" to "127.0.0.1",  // Should get from request context
                "vnp_CreateDate" to getFormattedDateTime()
            )

            // Generate secure hash
            val secureHash = generateVNPaySecureHash(vnpParams)
            vnpParams["vnp_SecureHash"] = secureHash

            // Build payment URL
            val paymentUrl = buildVNPayUrl(vnpParams)

            // Record payment attempt
            val paymentId = UUID.randomUUID().toString()
            PaymentsTable.insert {
                it[PaymentsTable.id] = paymentId
                it[PaymentsTable.orderId] = orderId
                it[PaymentsTable.method] = "VNPAY"
                it[PaymentsTable.amount] = BigDecimal(amount)
                it[PaymentsTable.transactionId] = orderCode
                it[PaymentsTable.status] = "PENDING"
            }

            VNPayResponse(
                paymentUrl = paymentUrl,
                transactionRef = orderCode,
                amount = amount,
                status = "PENDING"
            )
        }
    }

    fun verifyVNPayCallback(params: Map<String, String>): Pair<Boolean, String> {
        return try {
            // Extract VNPay parameters
            val vnpSecureHash = params["vnp_SecureHash"] ?: return Pair(false, "Missing secure hash")

            // Build map of parameters for verification (excluding SecureHash)
            val sortedParams = params.toMutableMap()
            sortedParams.remove("vnp_SecureHash")

            // Verify secure hash
            val calculatedHash = generateVNPaySecureHash(sortedParams)
            if (calculatedHash != vnpSecureHash) {
                return Pair(false, "Invalid secure hash")
            }

            // Check response code
            val responseCode = params["vnp_ResponseCode"] ?: "99"
            if (responseCode != "00") {
                return Pair(false, "Payment failed with code: $responseCode")
            }

            // Check transaction status
            val txnStatus = params["vnp_TransactionStatus"] ?: "2"
            if (txnStatus != "0") {
                return Pair(false, "Transaction failed with status: $txnStatus")
            }

            val txnRef = params["vnp_TxnRef"] ?: return Pair(false, "Missing transaction reference")

            transaction {
                // Find order by order code
                val order = OrdersTable
                    .selectAll()
                    .where { OrdersTable.orderCode eq txnRef }
                    .singleOrNull()
                    ?: return@transaction Pair(false, "Order not found for txnRef: $txnRef")

                val orderId = order[OrdersTable.id]

                // Update payment record
                PaymentsTable.update({
                    (PaymentsTable.orderId eq orderId) and
                    (PaymentsTable.method eq "VNPAY")
                }) {
                    it[PaymentsTable.status] = "COMPLETED"
                    it[PaymentsTable.transactionId] = params["vnp_TransactionNo"] ?: txnRef
                }

                // Update order payment status
                OrdersTable.update({ OrdersTable.id eq orderId }) {
                    it[OrdersTable.paymentStatus] = "PAID"
                    it[OrdersTable.status] = "CONFIRMED"
                }

                // Award reward points
                val pointsEarned = order[OrdersTable.pointsEarned]
                RewardAccountsTable.update({ RewardAccountsTable.userId eq order[OrdersTable.userId] }) {
                    with(SqlExpressionBuilder) {
                        it[RewardAccountsTable.totalPoints] = RewardAccountsTable.totalPoints + pointsEarned
                    }
                }

                RewardTransactionsTable.insert {
                    it[RewardTransactionsTable.id] = UUID.randomUUID().toString()
                    it[RewardTransactionsTable.userId] = order[OrdersTable.userId]
                    it[RewardTransactionsTable.orderId] = orderId
                    it[RewardTransactionsTable.type] = "EARN"
                    it[RewardTransactionsTable.points] = pointsEarned
                    it[RewardTransactionsTable.description] = "Thanh toán thành công đơn hàng $txnRef"
                }

                Pair(true, "Thanh toán VNPay thành công")
            }
        } catch (e: Exception) {
            Pair(false, "Error verifying VNPay: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // MOMO PAYMENT
    // ─────────────────────────────────────────────────────────────

    fun initiateMoMoPayment(orderId: String, amount: Long, returnUrl: String): MoMoResponse {
        return transaction {
            val order = OrdersTable
                .selectAll()
                .where { OrdersTable.id eq orderId }
                .singleOrNull()
                ?: throw IllegalArgumentException("Đơn hàng không tìm thấy")

            val orderCode = order[OrdersTable.orderCode]
            val requestId = UUID.randomUUID().toString()

            // Prepare MoMo request (would normally call MoMo API)
            // For now, build the URL for redirect
            val momoPaymentUrl = buildMoMoUrl(
                partnerCode = MOMO_PARTNER_CODE,
                orderId = orderCode,
                amount = amount,
                returnUrl = returnUrl,
                requestId = requestId
            )

            // Record payment attempt
            val paymentId = UUID.randomUUID().toString()
            PaymentsTable.insert {
                it[PaymentsTable.id] = paymentId
                it[PaymentsTable.orderId] = orderId
                it[PaymentsTable.method] = "MOMO"
                it[PaymentsTable.amount] = BigDecimal(amount)
                it[PaymentsTable.transactionId] = requestId
                it[PaymentsTable.status] = "PENDING"
            }

            MoMoResponse(
                paymentUrl = momoPaymentUrl,
                requestId = requestId,
                amount = amount,
                status = "PENDING"
            )
        }
    }

    fun verifyMoMoCallback(params: Map<String, String>): Pair<Boolean, String> {
        return try {
            val resultCode = params["resultCode"] ?: "1"
            if (resultCode != "0") {
                return Pair(false, "MoMo payment failed with code: $resultCode")
            }

            val orderId = params["orderId"] ?: return Pair(false, "Missing orderId")
            val requestId = params["requestId"] ?: return Pair(false, "Missing requestId")

            transaction {
                val order = OrdersTable
                    .selectAll()
                    .where { OrdersTable.orderCode eq orderId }
                    .singleOrNull()
                    ?: return@transaction Pair(false, "Order not found")

                val orderDbId = order[OrdersTable.id]

                // Update payment record
                PaymentsTable.update({
                    (PaymentsTable.orderId eq orderDbId) and
                    (PaymentsTable.method eq "MOMO")
                }) {
                    it[PaymentsTable.status] = "COMPLETED"
                    it[PaymentsTable.transactionId] = requestId
                }

                // Update order payment status
                OrdersTable.update({ OrdersTable.id eq orderDbId }) {
                    it[OrdersTable.paymentStatus] = "PAID"
                    it[OrdersTable.status] = "CONFIRMED"
                }

                // Award reward points
                val pointsEarned = order[OrdersTable.pointsEarned]
                RewardAccountsTable.update({ RewardAccountsTable.userId eq order[OrdersTable.userId] }) {
                    with(SqlExpressionBuilder) {
                        it[RewardAccountsTable.totalPoints] = RewardAccountsTable.totalPoints + pointsEarned
                    }
                }

                RewardTransactionsTable.insert {
                    it[RewardTransactionsTable.id] = UUID.randomUUID().toString()
                    it[RewardTransactionsTable.userId] = order[OrdersTable.userId]
                    it[RewardTransactionsTable.orderId] = orderDbId
                    it[RewardTransactionsTable.type] = "EARN"
                    it[RewardTransactionsTable.points] = pointsEarned
                    it[RewardTransactionsTable.description] = "Thanh toán MoMo thành công đơn hàng $orderId"
                }

                Pair(true, "Thanh toán MoMo thành công")
            }
        } catch (e: Exception) {
            Pair(false, "Error verifying MoMo: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // COD (CASH ON DELIVERY)
    // ─────────────────────────────────────────────────────────────

    fun createCODPayment(orderId: String): String {
        return transaction {
            val order = OrdersTable
                .selectAll()
                .where { OrdersTable.id eq orderId }
                .singleOrNull()
                ?: throw IllegalArgumentException("Đơn hàng không tìm thấy")

            // Create COD payment record
            val paymentId = UUID.randomUUID().toString()
            PaymentsTable.insert {
                it[PaymentsTable.id] = paymentId
                it[PaymentsTable.orderId] = orderId
                it[PaymentsTable.method] = "COD"
                it[PaymentsTable.amount] = order[OrdersTable.total] ?: BigDecimal.ZERO
                it[PaymentsTable.status] = "PENDING"  // Will be marked COMPLETED when driver collects
            }

            // Update order status to CONFIRMED for COD
            OrdersTable.update({ OrdersTable.id eq orderId }) {
                it[OrdersTable.paymentStatus] = "COD"
                it[OrdersTable.status] = "CONFIRMED"
            }

            paymentId
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PAYMENT STATUS & RECORDS
    // ─────────────────────────────────────────────────────────────

    fun getPaymentStatus(orderId: String): PaymentStatusDto {
        return transaction {
            val payment = PaymentsTable
                .selectAll()
                .where { PaymentsTable.orderId eq orderId }
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

    // ─────────────────────────────────────────────────────────────
    // HELPER FUNCTIONS
    // ─────────────────────────────────────────────────────────────

    private fun generateVNPaySecureHash(params: Map<String, String>): String {
        val sortedKeys = params.keys.sorted()
        val hashData = StringBuilder()

        for (key in sortedKeys) {
            val value = params[key] ?: continue
            if (value.isNotEmpty()) {
                if (hashData.isNotEmpty()) {
                    hashData.append("&")
                }
                hashData.append("$key=$value")
            }
        }

        // Generate HMAC SHA512
        val hmac = Mac.getInstance("HmacSHA512")
        val key = SecretKeySpec(VNPAY_SECRET_KEY.toByteArray(), "HmacSHA512")
        hmac.init(key)
        val hash = hmac.doFinal(hashData.toString().toByteArray())

        // Convert to hex string
        return hash.joinToString("") { "%02X".format(it) }
    }

    private fun buildVNPayUrl(params: Map<String, String>): String {
        val queryString = params.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        return "$VNPAY_URL?$queryString"
    }

    private fun buildMoMoUrl(
        partnerCode: String,
        orderId: String,
        amount: Long,
        returnUrl: String,
        requestId: String
    ): String {
        // In real scenario, would call MoMo API directly
        // For now, return a mock redirect URL
        return "https://test-payment.momo.vn/web/index.html?partnerCode=$partnerCode&orderId=$orderId&amount=$amount&returnUrl=${urlEncode(returnUrl)}&requestId=$requestId"
    }

    private fun getFormattedDateTime(): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss")
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun urlEncode(str: String): String {
        return java.net.URLEncoder.encode(str, "UTF-8")
    }
}
