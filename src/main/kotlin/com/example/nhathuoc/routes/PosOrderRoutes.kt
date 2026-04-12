package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.CouponRedemptionsTable
import com.example.nhathuoc.database.tables.CouponsTable
import com.example.nhathuoc.database.tables.OrderItemsTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.ProductsTable
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.requireInternalAccess
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
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

fun Route.posOrderRoutes() {
    authenticate("auth-jwt") {
        route("/internal/pos/orders") {
            post {
                val (principal, shopId) = call.requireInternalAccess()
                val request = call.receive<CreatePosOrderRequest>()
                if (request.items.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "items is required"))
                }
                if (request.items.any { it.quantity <= 0 }) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "quantity must be > 0"))
                }

                val paymentMethod = request.paymentMethod.trim().uppercase()
                if (paymentMethod !in setOf("CASH", "MOMO", "VNPAY", "ZALOPAY", "BANK_TRANSFER", "CARD", "COD", "E_WALLET")) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unsupported paymentMethod"))
                }

                val cashierUserId = principal.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token user"))

                val result = transaction {
                    val itemMap = request.items.groupBy { it.productId }
                    val products = ProductsTable
                        .selectAll()
                        .where { ProductsTable.shopId eq shopId }
                        .filter { row -> itemMap.containsKey(row[ProductsTable.id]) }

                    if (products.size != itemMap.keys.size) {
                        return@transaction Result.failure(IllegalArgumentException("Some products do not belong to this shop"))
                    }

                    var subtotal = BigDecimal.ZERO
                    val orderItems = products.map { productRow ->
                        val reqItem = itemMap[productRow[ProductsTable.id]]!!.first()
                        val lineTotal = productRow[ProductsTable.price].toBigDecimal().multiply(reqItem.quantity.toBigDecimal())
                        subtotal = subtotal.add(lineTotal)
                        Triple(productRow, reqItem.quantity, reqItem.unit?.trim()?.ifBlank { null } ?: productRow[ProductsTable.unit])
                    }

                    val couponComputed = request.couponCode
                        ?.takeIf { it.isNotBlank() }
                        ?.let { code -> computeCouponDiscount(shopId, code, subtotal, request.customerId) }

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
                        it[OrdersTable.userId] = request.customerId?.trim()?.ifBlank { null }
                        it[OrdersTable.shopId] = shopId
                        it[OrdersTable.orderChannel] = "POS"
                        it[OrdersTable.status] = "PENDING"
                        it[OrdersTable.pickupType] = "PICKUP"
                        it[OrdersTable.cashierUserId] = cashierUserId
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
                            it[id] = UUID.randomUUID().toString()
                            it[orderId] = orderId
                            it[productId] = productRow[ProductsTable.id]
                            it[name] = productRow[ProductsTable.name]
                            it[price] = productRow[ProductsTable.price].setScale(2, RoundingMode.HALF_UP)
                            it[OrderItemsTable.quantity] = quantity
                            it[OrderItemsTable.unit] = unit
                        }
                    }

                    if (couponData != null) {
                        CouponRedemptionsTable.insert {
                            it[id] = UUID.randomUUID().toString()
                            it[couponId] = couponData.couponId
                            it[CouponRedemptionsTable.orderId] = orderId
                            it[userId] = request.customerId?.trim()?.ifBlank { null }
                            it[appliedDiscountAmount] = discount
                            it[status] = "APPLIED"
                        }
                        CouponsTable.update({ CouponsTable.id eq couponData.couponId }) {
                            with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                                it.update(CouponsTable.usedCount, CouponsTable.usedCount + 1)
                            }
                        }
                    }

                    Result.success(
                        mapOf(
                            "id" to orderId,
                            "orderCode" to orderCode,
                            "status" to "PENDING",
                            "paymentStatus" to if (paymentMethod == "CASH") "UNPAID" else "PENDING",
                            "subtotal" to subtotal.toDouble(),
                            "discount" to discount.toDouble(),
                            "total" to total.toDouble()
                        )
                    )
                }

                result.onSuccess { data ->
                    call.respond(HttpStatusCode.Created, mapOf("data" to data, "message" to "POS order created successfully"))
                }.onFailure {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Cannot create POS order")))
                }
            }

            post("/{orderId}/confirm-cash") {
                val (_, shopId) = call.requireInternalAccess()
                val orderId = call.parameters["orderId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "orderId is required"))
                val request = call.receive<ConfirmCashRequest>()

                val result = transaction {
                    val order = OrdersTable
                        .selectAll()
                        .where {
                            (OrdersTable.id eq orderId) and
                                (OrdersTable.shopId eq shopId) and
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

                    Result.success(
                        mapOf(
                            "id" to orderId,
                            "status" to "DELIVERED",
                            "paymentStatus" to "COMPLETED",
                            "cashReceived" to cashReceived.toDouble(),
                            "cashChange" to cashChange.toDouble()
                        )
                    )
                }

                result.onSuccess { data ->
                    call.respond(mapOf("data" to data, "message" to "POS cash order confirmed"))
                }.onFailure {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Cannot confirm POS cash order")))
                }
            }
        }
    }
}

