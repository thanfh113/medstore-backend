package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import com.example.nhathuoc.routes.computeCouponDiscount
import com.example.nhathuoc.routes.computeCouponDiscountFromRow
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

// ─────────────────────────────────────────────────────────────
// DTOs
// ─────────────────────────────────────────────────────────────

@Serializable
data class CheckoutRequest(
    val addressId: String,
    val pickupType: String,          // DELIVERY or PICKUP
    val paymentMethod: String,        // MOMO, VNPAY, COD
    val rewardPointsToUse: Int = 0,
    val promoCode: String? = null,
    val notes: String? = null
)

data class CheckoutPreviewDto(
    val subtotal: BigDecimal,
    val discountAmount: BigDecimal,
    val shippingFee: BigDecimal,
    val pointsUsedValue: BigDecimal,
    val tax: BigDecimal,
    val total: BigDecimal,
    val cartItems: List<CartItemDto>,
    val shipAddress: UserAddressDto,
    val estimatedDeliveryDays: Int
)

data class OrderDto(
    val id: String,
    val orderCode: String,
    val userId: String,
    val status: String,
    val pickupType: String,
    val subtotal: BigDecimal?,
    val shippingFee: BigDecimal,
    val discount: BigDecimal,
    val pointsUsed: Int,
    val pointsEarned: Int,
    val total: BigDecimal?,
    val paymentMethod: String?,
    val paymentStatus: String,
    val notes: String?,
    val createdAt: LocalDateTime
)

// ─────────────────────────────────────────────────────────────
// SERVICE
// ─────────────────────────────────────────────────────────────

class CheckoutService {
    private val notificationService = NotificationService()

    /**
     * Validate cart before checkout
     */
    fun validateCart(userId: String): Pair<Boolean, List<String>> {
        return transaction {
            val errors = mutableListOf<String>()

            val cartItems = (CartItemsTable innerJoin ProductsTable)
                .selectAll()
                .where { CartItemsTable.userId eq userId }
                .toList()

            if (cartItems.isEmpty()) {
                errors.add("Giỏ hàng trống")
                return@transaction Pair(false, errors)
            }

            cartItems.forEach { row ->
                val productName = row[ProductsTable.name]
                val requestedQty = row[CartItemsTable.quantity]
                val availableStock = row[ProductsTable.stock]
                val isActive = row[ProductsTable.isActive]

                if (!isActive) {
                    errors.add("Sản phẩm '$productName' không còn có sẵn")
                }

                if (requestedQty > availableStock) {
                    errors.add("Kho không đủ cho '$productName'. Có sẵn: $availableStock, Yêu cầu: $requestedQty")
                }
            }

            Pair(errors.isEmpty(), errors)
        }
    }

    /**
     * Calculate final totals for checkout
     */
    fun calculateTotals(
        userId: String,
        useRewardPoints: Int = 0,
        promoCode: String? = null
    ): CheckoutTotals {
        return transaction {
            // Get cart summary
            val cartItems = (CartItemsTable innerJoin ProductsTable)
                .selectAll()
                .where { CartItemsTable.userId eq userId }
                .map { row ->
                    Pair(
                        row[ProductsTable.price],
                        row[CartItemsTable.quantity]
                    )
                }
                .toList()

            val subtotal = cartItems.sumOf { (price, qty) ->
                price * qty.toBigDecimal()
            }

            val promotion = promoCode
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { code ->
                    resolvePromotionInTx(userId = userId, promoCode = code, orderTotal = subtotal)
                        .getOrElse { throw IllegalArgumentException(it.message ?: "Invalid promotion") }
                }
            val discount = promotion?.discountAmount ?: BigDecimal.ZERO

            // Reward points can only offset merchandise value after discount.
            val maxRewardPointsApplicable = ((subtotal - discount).coerceAtLeast(BigDecimal.ZERO) / BigDecimal(1000))
                .toInt()
                .coerceAtLeast(0)
            val appliedRewardPoints = useRewardPoints.coerceIn(0, maxRewardPointsApplicable)
            val pointsValue = BigDecimal(appliedRewardPoints) * BigDecimal(1000)

            // Get shipping fee
            val shippingFee = if (subtotal >= BigDecimal(500000)) {
                BigDecimal.ZERO  // Free shipping if subtotal >= 500k
            } else {
                BigDecimal(30000)  // 30k shipping fee
            }

            // Calculate tax (10%)
            val taxableAmount = subtotal - discount - pointsValue
            val tax = if (taxableAmount > BigDecimal.ZERO) {
                taxableAmount * BigDecimal(0.10)
            } else {
                BigDecimal.ZERO
            }

            // Final total
            val total = subtotal - discount - pointsValue + shippingFee + tax

            CheckoutTotals(
                subtotal = subtotal,
                discount = discount,
                shippingFee = shippingFee,
                appliedRewardPoints = appliedRewardPoints,
                pointsUsedValue = pointsValue,
                tax = tax,
                total = total,
                promotion = promotion
            )
        }
    }

    /**
     * Get checkout preview before payment
     */
    fun getCheckoutPreview(userId: String, addressId: String, useRewardPoints: Int = 0, promoCode: String? = null): CheckoutPreviewDto {
        return transaction {
            // Get address
            val address = UserAddressesTable
                .selectAll()
                .where {
                    (UserAddressesTable.id eq addressId) and
                    (UserAddressesTable.userId eq userId)
                }
                .singleOrNull()
                ?: throw IllegalArgumentException("Địa chỉ không tìm thấy")

            val addressDto = UserAddressDto(
                id = address[UserAddressesTable.id],
                userId = address[UserAddressesTable.userId],
                label = address[UserAddressesTable.label],
                recipientName = address[UserAddressesTable.recipientName],
                phone = address[UserAddressesTable.phone],
                address = address[UserAddressesTable.address],
                ward = address[UserAddressesTable.ward],
                district = address[UserAddressesTable.district],
                province = address[UserAddressesTable.province],
                isDefault = address[UserAddressesTable.isDefault]
            )

            // Get cart items
            val cartService = CartService()
            val cartSummary = cartService.getCartSummary(userId)

            // Calculate totals
            val totals = calculateTotals(userId, useRewardPoints, promoCode)

            CheckoutPreviewDto(
                subtotal = totals.subtotal,
                discountAmount = totals.discount,
                shippingFee = totals.shippingFee,
                pointsUsedValue = totals.pointsUsedValue,
                tax = totals.tax,
                total = totals.total,
                cartItems = cartSummary.items,
                shipAddress = addressDto,
                estimatedDeliveryDays = 3  // Default 3 days delivery
            )
        }
    }

    /**
     * Create order from cart and clear cart
     */
    fun createOrder(userId: String, request: CheckoutRequest): OrderDto {
        return transaction {
            // Validate cart first
            val (isValid, errors) = validateCart(userId)
            if (!isValid) {
                throw IllegalArgumentException("Lỗi giỏ hàng: ${errors.joinToString(", ")}")
            }

            // Verify address exists
            val address = UserAddressesTable
                .selectAll()
                .where {
                    (UserAddressesTable.id eq request.addressId) and
                    (UserAddressesTable.userId eq userId)
                }
                .singleOrNull()
                ?: throw IllegalArgumentException("Địa chỉ không tìm thấy")

            // Calculate totals
            val totals = calculateTotals(userId, request.rewardPointsToUse, request.promoCode)
            val appliedRewardPoints = totals.appliedRewardPoints

            // Get cart items for order items
            val cartItems = (CartItemsTable innerJoin ProductsTable)
                .selectAll()
                .where { CartItemsTable.userId eq userId }
                .toList()

            if (request.rewardPointsToUse > appliedRewardPoints) {
                throw IllegalArgumentException("Reward points exceed the applicable limit for this order: $appliedRewardPoints")
            }

            // Check available reward points
            if (appliedRewardPoints > 0) {
                val userReward = RewardAccountsTable
                    .selectAll()
                    .where { RewardAccountsTable.userId eq userId }
                    .singleOrNull()

                val availablePoints = (userReward?.get(RewardAccountsTable.totalPoints) ?: 0) -
                                      (userReward?.get(RewardAccountsTable.usedPoints) ?: 0)
                if (request.rewardPointsToUse > availablePoints) {
                    throw IllegalArgumentException("Không đủ điểm thưởng. Có: $availablePoints, Yêu cầu: ${request.rewardPointsToUse}")
                }
            }

            // Create order
            val orderId = UUID.randomUUID().toString()
            val orderCode = "ORD-${System.currentTimeMillis().toString().takeLast(9)}"

            // Determine shop from first cart item
            val firstShopId = cartItems.firstOrNull()?.let { item ->
                ProductsTable.selectAll()
                    .where { ProductsTable.id eq item[CartItemsTable.productId] }
                    .singleOrNull()
                    ?.get(ProductsTable.id)
            } ?: throw IllegalArgumentException("Không thể xác định cửa hàng")

            val pointsEarned = cartItems.sumOf { row ->
                row[ProductsTable.rewardPoints] * row[CartItemsTable.quantity]
            }

            OrdersTable.insert {
                it[OrdersTable.id] = orderId
                it[OrdersTable.orderCode] = orderCode
                it[OrdersTable.userId] = userId
                it[OrdersTable.status] = "PENDING"
                it[OrdersTable.pickupType] = request.pickupType
                it[OrdersTable.addressId] = request.addressId
                it[OrdersTable.subtotal] = totals.subtotal
                it[OrdersTable.shippingFee] = totals.shippingFee
                it[OrdersTable.discount] = totals.discount
                it[OrdersTable.couponId] = totals.promotion?.couponId
                it[OrdersTable.pointsUsed] = appliedRewardPoints
                it[OrdersTable.pointsEarned] = pointsEarned
                it[OrdersTable.total] = totals.total
                it[OrdersTable.paymentMethod] = request.paymentMethod
                it[OrdersTable.paymentStatus] = "UNPAID"
                it[OrdersTable.note] = request.notes
            }

            // Create order items from cart
            cartItems.forEach { cartRow ->
                val orderItemId = UUID.randomUUID().toString()
                val productId = cartRow[CartItemsTable.productId]
                val quantity = cartRow[CartItemsTable.quantity]
                val productPrice = cartRow[ProductsTable.price]
                val productName = cartRow[ProductsTable.name]

                OrderItemsTable.insert {
                    it[OrderItemsTable.id] = orderItemId
                    it[OrderItemsTable.orderId] = orderId
                    it[OrderItemsTable.productId] = productId
                    it[OrderItemsTable.name] = productName
                    it[OrderItemsTable.quantity] = quantity
                    it[OrderItemsTable.price] = productPrice
                    it[OrderItemsTable.unit] = cartRow[CartItemsTable.unit]
                }

                // Batch allocation is handled by OrderFulfillmentService during fulfillment
                // For now, just deduct stock
                ProductsTable.update({ ProductsTable.id eq productId }) {
                    with(SqlExpressionBuilder) {
                        it[ProductsTable.stock] = ProductsTable.stock - quantity
                    }
                }
            }

            totals.promotion?.let { promotion ->
                CouponRedemptionsTable.insert {
                    it[CouponRedemptionsTable.id] = UUID.randomUUID().toString()
                    it[CouponRedemptionsTable.couponId] = promotion.couponId
                    it[CouponRedemptionsTable.orderId] = orderId
                    it[CouponRedemptionsTable.userId] = userId
                    it[CouponRedemptionsTable.appliedDiscountAmount] = promotion.discountAmount
                    it[CouponRedemptionsTable.status] = "APPLIED"
                }

                CouponsTable.update({ CouponsTable.id eq promotion.couponId }) {
                    with(SqlExpressionBuilder) {
                        it.update(CouponsTable.usedCount, CouponsTable.usedCount + 1)
                    }
                }

                if (promotion.rewardRedemptionId != null) {
                    val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    RewardRedemptionsTable.update({ RewardRedemptionsTable.id eq promotion.rewardRedemptionId }) {
                        it[status] = "USED"
                        it[redeemedOrderId] = orderId
                        it[voucherUsedAt] = now
                        it[updatedAt] = now
                    }
                    notificationService.createUserNotification(
                        userId = userId,
                        title = "Voucher đã được sử dụng",
                        body = "Mã ${promotion.code} đã được áp dụng cho đơn $orderCode.",
                        type = "REWARD",
                        refId = promotion.rewardRedemptionId
                    )
                }
            }

            // Clear cart
            CartItemsTable.deleteWhere { CartItemsTable.userId eq userId }

            // Deduct reward points if used
            if (appliedRewardPoints > 0) {
                RewardAccountsTable.update({ RewardAccountsTable.userId eq userId }) {
                    with(SqlExpressionBuilder) {
                        it[RewardAccountsTable.usedPoints] = RewardAccountsTable.usedPoints + appliedRewardPoints
                    }
                }

                RewardTransactionsTable.insert {
                    it[RewardTransactionsTable.id] = UUID.randomUUID().toString()
                    it[RewardTransactionsTable.userId] = userId
                    it[RewardTransactionsTable.orderId] = orderId
                    it[RewardTransactionsTable.refType] = "ORDER"
                    it[RewardTransactionsTable.refId] = orderId
                    it[RewardTransactionsTable.type] = "REDEEM"
                    it[RewardTransactionsTable.points] = -appliedRewardPoints
                    it[RewardTransactionsTable.description] = "Dùng điểm thưởng cho đơn hàng $orderCode"
                }
            }

            // Return order DTO
            val createdOrder = OrdersTable
                .selectAll()
                .where { OrdersTable.id eq orderId }
                .singleOrNull()
                ?: throw IllegalArgumentException("Cannot retrieve created order")

            OrderDto(
                id = orderId,
                orderCode = orderCode,
                userId = userId,
                status = "PENDING",
                pickupType = request.pickupType,
                subtotal = totals.subtotal,
                shippingFee = totals.shippingFee,
                discount = totals.discount,
                pointsUsed = appliedRewardPoints,
                pointsEarned = pointsEarned,
                total = totals.total,
                paymentMethod = request.paymentMethod,
                paymentStatus = "UNPAID",
                notes = request.notes,
                createdAt = createdOrder[OrdersTable.createdAt]
            )
        }
    }

    private fun resolvePromotionInTx(
        userId: String,
        promoCode: String,
        orderTotal: BigDecimal
    ): Result<CheckoutPromotion> {
        val normalizedCode = promoCode.trim().uppercase()

        val rewardVoucher = (RewardRedemptionsTable innerJoin RewardProductsTable innerJoin CouponsTable)
            .selectAll()
            .where {
                (RewardRedemptionsTable.userId eq userId) and
                    (RewardRedemptionsTable.issuedVoucherCode eq normalizedCode) and
                    (RewardRedemptionsTable.status eq "APPROVED") and
                    (RewardRedemptionsTable.redeemedOrderId eq null) and
                    (RewardProductsTable.rewardType eq "VOUCHER")
            }
            .singleOrNull()

        if (rewardVoucher != null) {
            return computeCouponDiscountFromRow(
                coupon = rewardVoucher,
                orderTotal = orderTotal,
                userId = userId,
                enforceGlobalUsageLimit = false,
                enforcePerUserUsageLimit = false
            ).map { computed ->
                CheckoutPromotion(
                    couponId = computed.couponId,
                    code = normalizedCode,
                    discountAmount = computed.discountAmount,
                    rewardRedemptionId = rewardVoucher[RewardRedemptionsTable.id],
                    source = "REWARD_VOUCHER"
                )
            }
        }

        return computeCouponDiscount(
            code = normalizedCode,
            orderTotal = orderTotal,
            userId = userId
        ).map { computed ->
            CheckoutPromotion(
                couponId = computed.couponId,
                code = computed.code,
                discountAmount = computed.discountAmount,
                source = "PUBLIC_COUPON"
            )
        }
    }

    /**
     * Get order details
     */
    fun getOrderDetails(orderId: String, userId: String): OrderDetailDto {
        return transaction {
            val order = OrdersTable
                .selectAll()
                .where {
                    (OrdersTable.id eq orderId) and
                    (OrdersTable.userId eq userId)
                }
                .singleOrNull()
                ?: throw IllegalArgumentException("Đơn hàng không tìm thấy")

            val orderItems = OrderItemsTable
                .selectAll()
                .where { OrderItemsTable.orderId eq orderId }
                .map { row ->
                    OrderItemDetailDto(
                        id = row[OrderItemsTable.id],
                        productId = row[OrderItemsTable.productId],
                        productName = row[OrderItemsTable.name],
                        quantity = row[OrderItemsTable.quantity],
                        price = row[OrderItemsTable.price],
                        unit = row[OrderItemsTable.unit]
                    )
                }

            OrderDetailDto(
                id = order[OrdersTable.id],
                orderCode = order[OrdersTable.orderCode],
                status = order[OrdersTable.status],
                pickupType = order[OrdersTable.pickupType],
                subtotal = order[OrdersTable.subtotal],
                shippingFee = order[OrdersTable.shippingFee],
                discount = order[OrdersTable.discount],
                total = order[OrdersTable.total],
                paymentMethod = order[OrdersTable.paymentMethod],
                paymentStatus = order[OrdersTable.paymentStatus],
                items = orderItems,
                createdAt = order[OrdersTable.createdAt]
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// DATA CLASSES
// ─────────────────────────────────────────────────────────────

data class CheckoutTotals(
    val subtotal: BigDecimal,
    val discount: BigDecimal,
    val shippingFee: BigDecimal,
    val appliedRewardPoints: Int,
    val pointsUsedValue: BigDecimal,
    val tax: BigDecimal,
    val total: BigDecimal,
    val promotion: CheckoutPromotion? = null
)

data class CheckoutPromotion(
    val couponId: String,
    val code: String,
    val discountAmount: BigDecimal,
    val rewardRedemptionId: String? = null,
    val source: String = "PUBLIC_COUPON"
)

data class OrderDetailDto(
    val id: String,
    val orderCode: String,
    val status: String,
    val pickupType: String,
    val subtotal: BigDecimal?,
    val shippingFee: BigDecimal,
    val discount: BigDecimal,
    val total: BigDecimal?,
    val paymentMethod: String?,
    val paymentStatus: String,
    val items: List<OrderItemDetailDto>,
    val createdAt: LocalDateTime
)

data class OrderItemDetailDto(
    val id: String,
    val productId: String?,
    val productName: String,
    val quantity: Int,
    val price: BigDecimal,
    val unit: String
)
