package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.CouponRedemptionsTable
import com.example.nhathuoc.database.tables.CouponsTable
import com.example.nhathuoc.database.tables.OrderItemsTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.PaymentsTable
import com.example.nhathuoc.database.tables.ProductsTable
import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.service.PaymentService
import com.example.nhathuoc.service.RewardAwardService
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.requireInternalAccess
import io.ktor.http.HttpStatusCode
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
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

@Serializable
private data class PosOrderItemRequest(
    val productId: String,
    val quantity: Int,
    val unit: String? = null
)

@Serializable
private data class CreatePosOrderRequest(
    val items: List<PosOrderItemRequest>,
    val customerId: String? = null,
    val note: String? = null,
    val paymentMethod: String = "CASH",
    val cashReceived: Double? = null,
    val couponCode: String? = null
)

@Serializable
private data class ConfirmCashRequest(
    val cashReceived: Double? = null
)

@Serializable
private data class InitPosPaymentRequest(
    val paymentMethod: String? = null,
    val returnUrl: String? = null
)

@Serializable
private data class PosOrderCreateData(
    val id: String,
    val orderCode: String,
    val status: String,
    val paymentMethod: String,
    val paymentStatus: String,
    val subtotal: Double,
    val discount: Double,
    val total: Double
)

@Serializable
private data class PosOrderConfirmData(
    val id: String,
    val status: String,
    val paymentMethod: String,
    val paymentStatus: String,
    val cashReceived: Double,
    val cashChange: Double
)

@Serializable
private data class PosPaymentInitData(
    val id: String,
    val orderCode: String,
    val status: String,
    val paymentMethod: String,
    val paymentStatus: String,
    val total: Double,
    val paymentUrl: String,
    val qrContent: String,
    val paymentReference: String,
    val amount: Long
)

@Serializable
private data class PosOrderStatusData(
    val id: String,
    val orderCode: String,
    val status: String,
    val paymentMethod: String? = null,
    val paymentStatus: String,
    val total: Double,
    val cashReceived: Double? = null,
    val cashChange: Double? = null,
    val paymentReference: String? = null,
    val paidAt: String? = null
)

@Serializable
private data class PosEnvelope<T>(
    val data: T,
    val message: String
)

private val supportedPosPaymentMethods = setOf("CASH", "MOMO", "VNPAY", "ZALOPAY")
private val supportedPosGatewayMethods = setOf("MOMO", "VNPAY", "ZALOPAY")

fun Route.posOrderRoutes() {
    val paymentService = PaymentService()
    val rewardAwardService = RewardAwardService()
    authenticate("auth-jwt") {
        route("/internal/pos/orders") {
            post {
                val (principal, _) = call.requireInternalAccess()
                val request = call.receive<CreatePosOrderRequest>()
                if (request.items.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "items is required"))
                }
                if (request.items.any { it.quantity <= 0 }) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "quantity must be > 0"))
                }

                val paymentMethod = request.paymentMethod.trim().uppercase()
                if (paymentMethod !in supportedPosPaymentMethods) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unsupported paymentMethod"))
                }

                val cashierUserId = principal.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token user"))

                val result = transaction {
                    val itemMap = request.items.groupBy { it.productId }
                    val products = ProductsTable
                        .selectAll()
                        .filter { row -> itemMap.containsKey(row[ProductsTable.id]) }

                    if (products.size != itemMap.keys.size) {
                        return@transaction Result.failure(IllegalArgumentException("Some products do not exist"))
                    }

                    var subtotal = BigDecimal.ZERO
                    val orderItems = products.map { productRow ->
                        val reqItem = itemMap[productRow[ProductsTable.id]]!!.first()
                        if (!productRow[ProductsTable.isActive] || productRow[ProductsTable.deletedAt] != null) {
                            return@transaction Result.failure(IllegalArgumentException("Product is not available: ${productRow[ProductsTable.name]}"))
                        }
                        if (productRow[ProductsTable.stock] < reqItem.quantity) {
                            return@transaction Result.failure(IllegalArgumentException("Insufficient stock for product: ${productRow[ProductsTable.name]}"))
                        }
                        val lineTotal = productRow[ProductsTable.price].multiply(reqItem.quantity.toBigDecimal())
                        subtotal = subtotal.add(lineTotal)
                        Triple(productRow, reqItem.quantity, reqItem.unit?.trim()?.ifBlank { null } ?: productRow[ProductsTable.unit])
                    }

                    val customerLookup = request.customerId?.trim()?.ifBlank { null }
                    val resolvedCustomerRow = customerLookup?.let { lookup ->
                        UsersTable
                            .selectAll()
                            .where {
                                ((UsersTable.id eq lookup) or (UsersTable.phone eq lookup)) and
                                    UsersTable.deletedAt.isNull()
                            }
                            .limit(1)
                            .firstOrNull()
                    }
                    val resolvedCustomerId = resolvedCustomerRow?.get(UsersTable.id)
                    // Store customer phone for matching in mobile POS order history
                    val resolvedCustomerPhone = resolvedCustomerRow?.get(UsersTable.phone)
                        ?: customerLookup?.takeIf { it.matches(Regex("\\d{8,12}")) }

                    val couponComputed = request.couponCode
                        ?.takeIf { it.isNotBlank() }
                        ?.let { code -> computeCouponDiscount(code, subtotal, resolvedCustomerId) }

                    if (couponComputed != null && couponComputed.isFailure) {
                        return@transaction Result.failure(couponComputed.exceptionOrNull()!!)
                    }

                    val couponData = couponComputed?.getOrNull()
                    val discount = couponData?.discountAmount ?: BigDecimal.ZERO
                    val total = subtotal.subtract(discount).setScale(2, RoundingMode.HALF_UP)

                    val orderId = UUID.randomUUID().toString()
                    val orderCode = "POS-${System.currentTimeMillis().toString().takeLast(8)}"

                    OrdersTable.insert {
                        it[id] = orderId
                        it[OrdersTable.orderCode] = orderCode
                        it[OrdersTable.userId] = resolvedCustomerId
                        it[OrdersTable.orderChannel] = "POS"
                        it[OrdersTable.status] = "PENDING"
                        it[OrdersTable.pickupType] = "PICKUP"
                        it[OrdersTable.cashierUserId] = cashierUserId
                        it[OrdersTable.shippingPhone] = resolvedCustomerPhone
                        it[OrdersTable.subtotal] = subtotal.setScale(2, RoundingMode.HALF_UP)
                        it[OrdersTable.shippingFee] = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                        it[OrdersTable.discount] = discount
                        it[OrdersTable.couponId] = couponData?.couponId
                        it[OrdersTable.total] = total
                        it[OrdersTable.paymentMethod] = paymentMethod
                        it[OrdersTable.paymentStatus] = if (paymentMethod == "CASH") "UNPAID" else "PENDING"
                        it[OrdersTable.note] = request.note?.trim()?.ifBlank { null }
                    }

                    orderItems.forEach { (productRow, quantity, unit) ->
                        OrderItemsTable.insert {
                            it[OrderItemsTable.id] = UUID.randomUUID().toString()
                            it[OrderItemsTable.orderId] = orderId
                            it[OrderItemsTable.productId] = productRow[ProductsTable.id]
                            it[OrderItemsTable.name] = productRow[ProductsTable.name]
                            it[OrderItemsTable.price] = productRow[ProductsTable.price].setScale(2, RoundingMode.HALF_UP)
                            it[OrderItemsTable.quantity] = quantity
                            it[OrderItemsTable.unit] = unit
                        }
                    }

                    if (couponData != null) {
                        CouponRedemptionsTable.insert {
                            it[CouponRedemptionsTable.id] = UUID.randomUUID().toString()
                            it[CouponRedemptionsTable.couponId] = couponData.couponId
                            it[CouponRedemptionsTable.orderId] = orderId
                            it[CouponRedemptionsTable.userId] = resolvedCustomerId
                            it[CouponRedemptionsTable.appliedDiscountAmount] = discount
                            it[CouponRedemptionsTable.status] = "APPLIED"
                        }
                        CouponsTable.update({ CouponsTable.id eq couponData.couponId }) {
                            with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                                it.update(CouponsTable.usedCount, CouponsTable.usedCount + 1)
                            }
                        }
                    }

                    Result.success(
                        PosOrderCreateData(
                            id = orderId,
                            orderCode = orderCode,
                            status = "PENDING",
                            paymentMethod = paymentMethod,
                            paymentStatus = if (paymentMethod == "CASH") "UNPAID" else "PENDING",
                            subtotal = subtotal.toDouble(),
                            discount = discount.toDouble(),
                            total = total.toDouble()
                        )
                    )
                }

                result.onSuccess { data ->
                    call.respond(
                        HttpStatusCode.Created,
                        PosEnvelope(
                            data = data,
                            message = "POS order created successfully"
                        )
                    )
                }.onFailure {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Cannot create POS order")))
                }
            }

            get("/{orderId}") {
                call.requireInternalAccess()
                val orderId = call.parameters["orderId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "orderId is required"))

                runCatching {
                    paymentService.reconcilePendingPosGatewayPayment(orderId)
                }.onFailure {
                    println("WARN: POS payment reconciliation failed for orderId=$orderId - ${it.message}")
                }

                val result = transaction {
                    val order = OrdersTable
                        .selectAll()
                        .where {
                            (OrdersTable.id eq orderId) and
                                (OrdersTable.orderChannel eq "POS")
                        }
                        .singleOrNull()
                        ?: return@transaction Result.failure(IllegalArgumentException("POS order not found"))

                    val latestPayment = PaymentsTable
                        .selectAll()
                        .where { PaymentsTable.orderId eq orderId }
                        .orderBy(PaymentsTable.createdAt to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()

                    Result.success(
                        PosOrderStatusData(
                            id = order[OrdersTable.id],
                            orderCode = order[OrdersTable.orderCode],
                            status = order[OrdersTable.status],
                            paymentMethod = order[OrdersTable.paymentMethod],
                            paymentStatus = order[OrdersTable.paymentStatus],
                            total = (order[OrdersTable.total] ?: BigDecimal.ZERO).toDouble(),
                            cashReceived = order[OrdersTable.cashReceived]?.toDouble(),
                            cashChange = order[OrdersTable.cashChange]?.toDouble(),
                            paymentReference = latestPayment?.get(PaymentsTable.transactionId),
                            paidAt = latestPayment?.get(PaymentsTable.paidAt)?.toString()
                        )
                    )
                }

                result.onSuccess { data ->
                    call.respond(
                        PosEnvelope(
                            data = data,
                            message = "POS order status retrieved"
                        )
                    )
                }.onFailure {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to (it.message ?: "Cannot get POS order status")))
                }
            }

            post("/{orderId}/init-payment") {
                call.requireInternalAccess()
                val orderId = call.parameters["orderId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "orderId is required"))
                val request = call.receive<InitPosPaymentRequest>()
                println("DEBUG: POS init-payment endpoint called - orderId=$orderId, requestPaymentMethod=${request.paymentMethod}")

                try {
                    val orderPaymentMethod = transaction {
                        OrdersTable
                            .selectAll()
                            .where {
                                (OrdersTable.id eq orderId) and
                                    (OrdersTable.orderChannel eq "POS")
                            }
                            .singleOrNull()
                            ?.get(OrdersTable.paymentMethod)
                    } ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "POS order not found"))

                    val resolvedPaymentMethod = request.paymentMethod
                        ?.trim()
                        ?.uppercase()
                        ?.takeIf { it.isNotBlank() }
                        ?: orderPaymentMethod
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "POS order has no payment method"))

                    if (resolvedPaymentMethod !in supportedPosGatewayMethods) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "POS gateway payment chi ho tro MOMO, VNPAY, ZALOPAY")
                        )
                    }

                    val payment = paymentService.initiatePosGatewayPayment(
                        orderId = orderId,
                        method = resolvedPaymentMethod,
                        returnUrl = request.returnUrl
                    )

                    call.respond(
                        PosEnvelope(
                            data = PosPaymentInitData(
                                id = payment.orderId,
                                orderCode = payment.orderCode,
                                status = "PENDING",
                                paymentMethod = payment.paymentMethod,
                                paymentStatus = payment.status,
                                total = payment.amount.toDouble(),
                                paymentUrl = payment.paymentUrl,
                                qrContent = payment.qrContent,
                                paymentReference = payment.paymentReference,
                                amount = payment.amount
                            ),
                            message = "POS payment initialized"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Cannot init POS payment")))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Cannot init POS payment")))
                }
            }

            post("/{orderId}/confirm-cash") {
                call.requireInternalAccess()
                val orderId = call.parameters["orderId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "orderId is required"))
                val request = call.receive<ConfirmCashRequest>()

                val result = transaction {
                    val order = OrdersTable
                        .selectAll()
                        .where {
                            (OrdersTable.id eq orderId) and
                                (OrdersTable.orderChannel eq "POS")
                        }
                        .singleOrNull()
                        ?: return@transaction Result.failure(IllegalArgumentException("POS order not found"))

                    if (order[OrdersTable.paymentMethod] != "CASH") {
                        return@transaction Result.failure(IllegalArgumentException("Only CASH POS order can be confirmed here"))
                    }

                    if (order[OrdersTable.paymentStatus] == "COMPLETED") {
                        return@transaction Result.failure(IllegalArgumentException("Order has already been completed"))
                    }

                    val items = OrderItemsTable
                        .selectAll()
                        .where { OrderItemsTable.orderId eq orderId }
                        .toList()

                    items.forEach { item ->
                        val productId = item[OrderItemsTable.productId]
                        if (!productId.isNullOrBlank()) {
                            val product = ProductsTable
                                .selectAll()
                                .where { ProductsTable.id eq productId }
                                .singleOrNull()
                                ?: return@transaction Result.failure(IllegalArgumentException("Product not found: $productId"))

                            if (product[ProductsTable.stock] < item[OrderItemsTable.quantity]) {
                                return@transaction Result.failure(IllegalArgumentException("Insufficient stock for product: ${product[ProductsTable.name]}"))
                            }
                        }
                    }

                    items.forEach { item ->
                        val productId = item[OrderItemsTable.productId]
                        if (!productId.isNullOrBlank()) {
                            ProductsTable.update({ ProductsTable.id eq productId }) {
                                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                                    it.update(ProductsTable.stock, ProductsTable.stock - item[OrderItemsTable.quantity])
                                }
                            }
                        }
                    }

                    val total = order[OrdersTable.total] ?: BigDecimal.ZERO
                    val cashReceived = request.cashReceived?.toBigDecimal()?.setScale(2, RoundingMode.HALF_UP) ?: total
                    if (cashReceived < total) {
                        return@transaction Result.failure(IllegalArgumentException("Cash received is less than total amount"))
                    }

                    val cashChange = cashReceived.subtract(total).setScale(2, RoundingMode.HALF_UP)
                    val completedAt = Clock.System.now().toLocalDateTime(TimeZone.UTC)

                    OrdersTable.update({ OrdersTable.id eq orderId }) {
                        it[status] = "DELIVERED"
                        it[paymentStatus] = "COMPLETED"
                        it[OrdersTable.cashReceived] = cashReceived
                        it[OrdersTable.cashChange] = cashChange
                        it[OrdersTable.completedAt] = completedAt
                    }

                    val existingCashPayment = PaymentsTable
                        .selectAll()
                        .where {
                            (PaymentsTable.orderId eq orderId) and
                                (PaymentsTable.method eq "CASH")
                        }
                        .orderBy(PaymentsTable.createdAt to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()

                    if (existingCashPayment != null) {
                        PaymentsTable.update({ PaymentsTable.id eq existingCashPayment[PaymentsTable.id] }) {
                            it[PaymentsTable.amount] = total
                            it[PaymentsTable.status] = "COMPLETED"
                            it[PaymentsTable.paidAt] = completedAt
                            it[PaymentsTable.transactionId] = null
                            it[PaymentsTable.paymentGatewayResponse] = null
                        }
                    } else {
                        PaymentsTable.insert {
                            it[id] = UUID.randomUUID().toString()
                            it[PaymentsTable.orderId] = orderId
                            it[PaymentsTable.method] = "CASH"
                            it[PaymentsTable.amount] = total
                            it[PaymentsTable.status] = "COMPLETED"
                            it[PaymentsTable.paidAt] = completedAt
                            it[PaymentsTable.transactionId] = null
                            it[PaymentsTable.paymentGatewayResponse] = null
                        }
                    }

                    rewardAwardService.awardSuccessfulPaymentPointsIfNeeded(order)

                    Result.success(
                        PosOrderConfirmData(
                            id = orderId,
                            status = "DELIVERED",
                            paymentMethod = "CASH",
                            paymentStatus = "COMPLETED",
                            cashReceived = cashReceived.toDouble(),
                            cashChange = cashChange.toDouble()
                        )
                    )
                }

                result.onSuccess { data ->
                    call.respond(
                        PosEnvelope(
                            data = data,
                            message = "POS cash order confirmed"
                        )
                    )
                }.onFailure {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Cannot confirm POS cash order")))
                }
            }
        }
    }
}
