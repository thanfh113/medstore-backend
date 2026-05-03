package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.CategoriesTable
import com.example.nhathuoc.database.tables.CategoryAttributesTable
import com.example.nhathuoc.database.tables.CouponRedemptionsTable
import com.example.nhathuoc.database.tables.CouponsTable
import com.example.nhathuoc.database.tables.OrderItemsTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.PaymentsTable
import com.example.nhathuoc.database.tables.ProductAttributeValuesTable
import com.example.nhathuoc.database.tables.ProductImagesTable
import com.example.nhathuoc.database.tables.ProductsTable
import com.example.nhathuoc.database.tables.RewardRedemptionsTable
import com.example.nhathuoc.database.tables.UserAddressesTable
import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.util.requireInternalAccess
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

@Serializable
private data class InternalOrderStatusUpdateRequest(
    val status: String
)

private val allowedInternalOrderStatuses = setOf(
    "PENDING",
    "PROCESSING",
    "SHIPPING",
    "DELIVERED",
    "CANCELLED",
    "RETURNED"
)

private sealed interface InternalOrderStatusUpdateOutcome {
    data class Success(val status: String) : InternalOrderStatusUpdateOutcome
    data class Failure(val statusCode: HttpStatusCode, val message: String) : InternalOrderStatusUpdateOutcome
}

@Serializable
private data class InternalOrderListItemDto(
    val id: String,
    val orderCode: String,
    val customerId: String,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val orderChannel: String,
    val status: String,
    val paymentMethod: String? = null,
    val paymentStatus: String,
    val total: Double? = null,
    val note: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
private data class InternalOrderListResponseDto(
    val data: List<InternalOrderListItemDto>,
    val message: String
)

@Serializable
private data class InternalOrderDetailItemDto(
    val id: String,
    val productId: String? = null,
    val name: String,
    val price: Double,
    val quantity: Int,
    val unit: String,
    val categoryName: String? = null,
    val description: String? = null,
    val manufacturer: String? = null,
    val origin: String? = null,
    val sku: String? = null,
    val registrationNumber: String? = null,
    val riskClassification: String? = null,
    val imageUrls: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap()
)

@Serializable
private data class InternalOrderDetailDto(
    val id: String,
    val orderCode: String,
    val customerId: String,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val orderChannel: String,
    val cashierUserId: String? = null,
    val cashierName: String? = null,
    val address: String? = null,
    val ward: String? = null,
    val district: String? = null,
    val province: String? = null,
    val status: String,
    val paymentMethod: String? = null,
    val paymentStatus: String,
    val subtotal: Double? = null,
    val shippingFee: Double,
    val discount: Double,
    val total: Double? = null,
    val cashReceived: Double? = null,
    val cashChange: Double? = null,
    val paymentReference: String? = null,
    val paidAt: String? = null,
    val note: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val items: List<InternalOrderDetailItemDto>
)

@Serializable
private data class InternalOrderDetailResponseDto(
    val data: InternalOrderDetailDto,
    val message: String
)

@Serializable
private data class InternalOrderStatusUpdateDataDto(
    val id: String,
    val status: String
)

@Serializable
private data class InternalOrderStatusUpdateResponseDto(
    val data: InternalOrderStatusUpdateDataDto,
    val message: String
)

private fun ResultRow.toOrderItemAttributeText(): String {
    return when (this[CategoryAttributesTable.dataType].lowercase()) {
        "number" -> this[ProductAttributeValuesTable.valueNumber]
            ?.stripTrailingZeros()
            ?.toPlainString()
            .orEmpty()

        "boolean" -> when (this[ProductAttributeValuesTable.valueBool]) {
            true -> "Có"
            false -> "Không"
            null -> ""
        }

        "date" -> this[ProductAttributeValuesTable.valueDate]?.toString().orEmpty()
        "multiselect" -> this[ProductAttributeValuesTable.valueJson]
            ?.replace("[", "")
            ?.replace("]", "")
            ?.replace("\"", "")
            ?.trim()
            .orEmpty()
            .ifBlank { this[ProductAttributeValuesTable.valueText].orEmpty() }

        else -> this[ProductAttributeValuesTable.valueText].orEmpty()
    }
}

private fun loadOrderItemAttributes(productId: String): Map<String, String> {
    return ProductAttributeValuesTable
        .join(CategoryAttributesTable, JoinType.INNER, ProductAttributeValuesTable.attributeId, CategoryAttributesTable.id)
        .selectAll()
        .where { ProductAttributeValuesTable.productId eq productId }
        .orderBy(CategoryAttributesTable.sortOrder to SortOrder.ASC)
        .mapNotNull { row ->
            val label = row[CategoryAttributesTable.label].ifBlank { row[CategoryAttributesTable.attrKey] }
            val value = row.toOrderItemAttributeText().trim()
            if (value.isBlank()) null else label to value
        }
        .toMap()
}

fun Route.internalOrderRoutes() {
    authenticate("auth-jwt") {
        route("/internal/orders") {
            get {
                call.requireInternalAccess()
                val statusFilter = call.request.queryParameters["status"]?.trim()?.uppercase()
                val channelFilter = call.request.queryParameters["channel"]?.trim()?.uppercase()

                val data = transaction {
                    var query = OrdersTable.selectAll()

                    if (!statusFilter.isNullOrBlank()) {
                        query = OrdersTable.selectAll().where { OrdersTable.status eq statusFilter }
                    }
                    if (!channelFilter.isNullOrBlank()) {
                        query = if (!statusFilter.isNullOrBlank()) {
                            OrdersTable.selectAll().where {
                                (OrdersTable.status eq statusFilter) and
                                    (OrdersTable.orderChannel eq channelFilter)
                            }
                        } else {
                            OrdersTable.selectAll().where { OrdersTable.orderChannel eq channelFilter }
                        }
                    }

                    query
                        .orderBy(OrdersTable.createdAt to SortOrder.DESC)
                        .map { row ->
                            val userRow = UsersTable
                                .selectAll()
                                .where { UsersTable.id eq (row[OrdersTable.userId] ?: "") }
                                .singleOrNull()

                            val customerId = row[OrdersTable.userId] ?: "WALK_IN"

                            InternalOrderListItemDto(
                                id = row[OrdersTable.id],
                                orderCode = row[OrdersTable.orderCode],
                                customerId = customerId,
                                customerName = userRow?.get(UsersTable.fullName),
                                customerPhone = userRow?.get(UsersTable.phone),
                                orderChannel = row[OrdersTable.orderChannel],
                                status = row[OrdersTable.status],
                                paymentMethod = row[OrdersTable.paymentMethod],
                                paymentStatus = row[OrdersTable.paymentStatus],
                                total = row[OrdersTable.total]?.toDouble(),
                                note = row[OrdersTable.note],
                                createdAt = row[OrdersTable.createdAt].toString(),
                                updatedAt = row[OrdersTable.updatedAt].toString()
                            )
                        }
                        .toList()
                }

                call.respond(
                    InternalOrderListResponseDto(
                        data = data,
                        message = "Get internal orders successfully"
                    )
                )
            }

            get("/{orderId}") {
                call.requireInternalAccess()
                val orderId = call.parameters["orderId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Order ID is required"))

                val result = transaction {
                    val orderRow = OrdersTable
                        .selectAll()
                        .where { OrdersTable.id eq orderId }
                        .singleOrNull()

                    if (orderRow == null) {
                        null
                    } else {
                        val userRow = UsersTable
                            .selectAll()
                            .where { UsersTable.id eq (orderRow[OrdersTable.userId] ?: "") }
                            .singleOrNull()
                        val cashierRow = orderRow[OrdersTable.cashierUserId]?.let { cashierUserId ->
                            UsersTable
                                .selectAll()
                                .where { UsersTable.id eq cashierUserId }
                                .singleOrNull()
                        }
                        val customerId = orderRow[OrdersTable.userId] ?: "WALK_IN"
                        val addressRow = orderRow[OrdersTable.addressId]?.let { addressId ->
                            UserAddressesTable
                                .selectAll()
                                .where { UserAddressesTable.id eq addressId }
                                .singleOrNull()
                        }
                        val latestPayment = PaymentsTable
                            .selectAll()
                            .where { PaymentsTable.orderId eq orderId }
                            .orderBy(PaymentsTable.createdAt to SortOrder.DESC)
                            .limit(1)
                            .firstOrNull()
                        val items = OrderItemsTable
                            .selectAll()
                            .where { OrderItemsTable.orderId eq orderId }
                            .map { item ->
                                val productId = item[OrderItemsTable.productId]
                                val productRow = productId?.let { id ->
                                    ProductsTable
                                        .selectAll()
                                        .where { ProductsTable.id eq id }
                                        .singleOrNull()
                                }
                                val categoryName = productRow?.get(ProductsTable.categoryId)?.let { categoryId ->
                                    CategoriesTable
                                        .selectAll()
                                        .where { CategoriesTable.id eq categoryId }
                                        .singleOrNull()
                                        ?.get(CategoriesTable.name)
                                }
                                val imageUrls = productId?.let { id ->
                                    ProductImagesTable
                                        .selectAll()
                                        .where { ProductImagesTable.productId eq id }
                                        .orderBy(ProductImagesTable.sortOrder to SortOrder.ASC)
                                        .map { image -> image[ProductImagesTable.url] }
                                }.orEmpty()
                                val attributes = productId?.let(::loadOrderItemAttributes).orEmpty()

                                InternalOrderDetailItemDto(
                                    id = item[OrderItemsTable.id],
                                    productId = productId,
                                    name = item[OrderItemsTable.name],
                                    price = item[OrderItemsTable.price].toDouble(),
                                    quantity = item[OrderItemsTable.quantity],
                                    unit = item[OrderItemsTable.unit],
                                    categoryName = categoryName,
                                    description = productRow?.get(ProductsTable.description),
                                    manufacturer = productRow?.get(ProductsTable.manufacturer),
                                    origin = productRow?.get(ProductsTable.origin),
                                    sku = productRow?.get(ProductsTable.sku),
                                    registrationNumber = productRow?.get(ProductsTable.registrationNumber),
                                    riskClassification = productRow?.get(ProductsTable.riskClassification),
                                    imageUrls = imageUrls,
                                    attributes = attributes
                                )
                            }

                        InternalOrderDetailDto(
                            id = orderRow[OrdersTable.id],
                            orderCode = orderRow[OrdersTable.orderCode],
                            customerId = customerId,
                            customerName = userRow?.get(UsersTable.fullName),
                            customerPhone = userRow?.get(UsersTable.phone),
                            orderChannel = orderRow[OrdersTable.orderChannel],
                            cashierUserId = orderRow[OrdersTable.cashierUserId],
                            cashierName = cashierRow?.get(UsersTable.fullName)
                                ?: cashierRow?.get(UsersTable.email)
                                ?: cashierRow?.get(UsersTable.phone),
                            address = addressRow?.get(UserAddressesTable.address),
                            ward = addressRow?.get(UserAddressesTable.ward),
                            district = addressRow?.get(UserAddressesTable.district),
                            province = addressRow?.get(UserAddressesTable.province),
                            status = orderRow[OrdersTable.status],
                            paymentMethod = orderRow[OrdersTable.paymentMethod],
                            paymentStatus = orderRow[OrdersTable.paymentStatus],
                            subtotal = orderRow[OrdersTable.subtotal]?.toDouble(),
                            shippingFee = orderRow[OrdersTable.shippingFee].toDouble(),
                            discount = orderRow[OrdersTable.discount].toDouble(),
                            total = orderRow[OrdersTable.total]?.toDouble(),
                            cashReceived = orderRow[OrdersTable.cashReceived]?.toDouble(),
                            cashChange = orderRow[OrdersTable.cashChange]?.toDouble(),
                            paymentReference = latestPayment?.get(PaymentsTable.transactionId),
                            paidAt = latestPayment?.get(PaymentsTable.paidAt)?.toString(),
                            note = orderRow[OrdersTable.note],
                            createdAt = orderRow[OrdersTable.createdAt].toString(),
                            updatedAt = orderRow[OrdersTable.updatedAt].toString(),
                            items = items
                        )
                    }
                }

                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Order not found"))
                } else {
                    call.respond(
                        InternalOrderDetailResponseDto(
                            data = result,
                            message = "Get internal order detail successfully"
                        )
                    )
                }
            }

            post("/{orderId}/status") {
                call.requireInternalAccess()
                val orderId = call.parameters["orderId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Order ID is required"))
                val request = call.receive<InternalOrderStatusUpdateRequest>()
                val newStatus = request.status.trim().uppercase()

                if (newStatus !in allowedInternalOrderStatuses) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unsupported order status"))
                }

                val outcome = transaction {
                    val order = OrdersTable
                        .selectAll()
                        .where { OrdersTable.id eq orderId }
                        .singleOrNull()
                        ?: return@transaction InternalOrderStatusUpdateOutcome.Failure(
                            statusCode = HttpStatusCode.NotFound,
                            message = "Order not found"
                        )

                    if (newStatus == "CANCELLED" && order[OrdersTable.paymentStatus] == "COMPLETED") {
                        return@transaction InternalOrderStatusUpdateOutcome.Failure(
                            statusCode = HttpStatusCode.BadRequest,
                            message = "Không thể hủy đơn đã thanh toán"
                        )
                    }

                    OrdersTable.update({ OrdersTable.id eq orderId }) {
                        it[status] = newStatus
                        if (newStatus == "CANCELLED" && order[OrdersTable.paymentStatus] != "COMPLETED") {
                            it[paymentStatus] = "UNPAID"
                        }
                    }

                    if (newStatus == "CANCELLED") {
                        PaymentsTable.update({
                            (PaymentsTable.orderId eq orderId) and
                                (PaymentsTable.status eq "PENDING")
                        }) {
                            it[status] = "CANCELLED"
                        }

                        val redemption = CouponRedemptionsTable
                            .selectAll()
                            .where {
                                (CouponRedemptionsTable.orderId eq orderId) and
                                    (CouponRedemptionsTable.status eq "APPLIED")
                            }
                            .singleOrNull()

                        if (redemption != null) {
                            CouponRedemptionsTable.update({ CouponRedemptionsTable.id eq redemption[CouponRedemptionsTable.id] }) {
                                it[status] = "REVERTED"
                                it[revertedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                            }

                            CouponsTable
                                .selectAll()
                                .where { CouponsTable.id eq redemption[CouponRedemptionsTable.couponId] }
                                .singleOrNull()
                                ?.let { coupon ->
                                    CouponsTable.update({ CouponsTable.id eq coupon[CouponsTable.id] }) {
                                        it[usedCount] = maxOf(0, coupon[CouponsTable.usedCount] - 1)
                                    }
                                }
                        }

                        RewardRedemptionsTable.update({
                            (RewardRedemptionsTable.redeemedOrderId eq orderId) and
                                (RewardRedemptionsTable.status eq "USED")
                        }) {
                            it[status] = "APPROVED"
                            it[redeemedOrderId] = null
                            it[voucherUsedAt] = null
                            it[updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                        }
                    }

                    InternalOrderStatusUpdateOutcome.Success(newStatus)
                }

                when (outcome) {
                    is InternalOrderStatusUpdateOutcome.Failure -> {
                        call.respond(outcome.statusCode, mapOf("error" to outcome.message))
                    }
                    is InternalOrderStatusUpdateOutcome.Success -> {
                        call.respond(
                            InternalOrderStatusUpdateResponseDto(
                                data = InternalOrderStatusUpdateDataDto(
                                    id = orderId,
                                    status = outcome.status
                                ),
                                message = "Order status updated successfully"
                            )
                        )
                    }
                }
            }
        }
    }
}
