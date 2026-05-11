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
import java.text.Normalizer
import java.util.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
    val notes: String? = null,
    val selectedCartItemIds: List<String>? = null,
    val directProductId: String? = null,
    val directQuantity: Int? = null,
    val directUnit: String? = null
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

    private data class CheckoutLine(
        val cartItemId: String? = null,
        val productId: String,
        val productName: String,
        val price: BigDecimal,
        val quantity: Int,
        val unit: String,
        val rewardPoints: Int,
        val stock: Int,
        val isActive: Boolean,
        val riskClassification: String
    )

    private fun CheckoutRequest.isDirectCheckout(): Boolean = !directProductId.isNullOrBlank()

    private fun loadCheckoutLines(
        userId: String,
        selectedCartItemIds: List<String>? = null,
        directProductId: String? = null,
        directQuantity: Int? = null,
        directUnit: String? = null
    ): List<CheckoutLine> {
        val productId = directProductId?.trim().orEmpty()
        if (productId.isNotBlank()) {
            val quantity = (directQuantity ?: 1).coerceAtLeast(1)
            val product = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq productId }
                .singleOrNull()
                ?: throw IllegalArgumentException("Sản phẩm không tồn tại")

            return listOf(
                CheckoutLine(
                    cartItemId = null,
                    productId = product[ProductsTable.id],
                    productName = product[ProductsTable.name],
                    price = product[ProductsTable.price],
                    quantity = quantity,
                    unit = directUnit?.trim()?.takeIf { it.isNotBlank() } ?: product[ProductsTable.unit],
                    rewardPoints = product[ProductsTable.rewardPoints],
                    stock = product[ProductsTable.stock],
                    isActive = product[ProductsTable.isActive],
                    riskClassification = product[ProductsTable.riskClassification].uppercase()
                )
            )
        }

        val selectedIds = normalizeSelectedCartItemIds(selectedCartItemIds)
        val condition = if (selectedIds.isEmpty()) {
            CartItemsTable.userId eq userId
        } else {
            (CartItemsTable.userId eq userId) and (CartItemsTable.id inList selectedIds)
        }

        return (CartItemsTable innerJoin ProductsTable)
            .selectAll()
            .where { condition }
            .map { row ->
                CheckoutLine(
                    cartItemId = row[CartItemsTable.id],
                    productId = row[CartItemsTable.productId],
                    productName = row[ProductsTable.name],
                    price = row[ProductsTable.price],
                    quantity = row[CartItemsTable.quantity],
                    unit = row[CartItemsTable.unit],
                    rewardPoints = row[ProductsTable.rewardPoints],
                    stock = row[ProductsTable.stock],
                    isActive = row[ProductsTable.isActive],
                    riskClassification = row[ProductsTable.riskClassification].uppercase()
                )
            }
    }

    private fun normalizeSelectedCartItemIds(selectedCartItemIds: List<String>?): List<String> =
        selectedCartItemIds.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun validateCheckoutLines(lines: List<CheckoutLine>): List<String> {
        val errors = mutableListOf<String>()
        if (lines.isEmpty()) {
            errors.add("Giỏ hàng trống")
            return errors
        }

        lines.forEach { line ->
            if (!line.isActive) {
                errors.add("Sản phẩm '${line.productName}' không còn có sẵn")
            }
            if (line.riskClassification == "C" || line.riskClassification == "D") {
                errors.add("Sản phẩm '${line.productName}' cần tư vấn/ký kết trực tiếp tại nhà thuốc")
            }
            if (line.quantity > line.stock) {
                errors.add("Kho không đủ cho '${line.productName}'. Có sẵn: ${line.stock}, Yêu cầu: ${line.quantity}")
            }
        }

        return errors
    }

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
        promoCode: String? = null,
        address: ResultRow? = null,
        pickupType: String = "DELIVERY",
        selectedCartItemIds: List<String>? = null,
        directProductId: String? = null,
        directQuantity: Int? = null,
        directUnit: String? = null
    ): CheckoutTotals {
        return transaction {
            val checkoutLines = loadCheckoutLines(
                userId = userId,
                selectedCartItemIds = selectedCartItemIds,
                directProductId = directProductId,
                directQuantity = directQuantity,
                directUnit = directUnit
            )

            val subtotal = checkoutLines.sumOf { line ->
                line.price * line.quantity.toBigDecimal()
            }

            val promotion = promoCode
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { code ->
                    resolvePromotionInTx(userId = userId, promoCode = code, orderTotal = subtotal)
                        .getOrElse { throw IllegalArgumentException(it.message ?: "Invalid promotion") }
                }
            val discount = promotion?.discountAmount ?: BigDecimal.ZERO

            // Reward points can only offset up to 50% merchandise value after discount.
            // 1 point = 1 VND. Earning points is still handled separately by RewardService.
            val merchandiseAfterDiscount = (subtotal - discount).coerceAtLeast(BigDecimal.ZERO)
            val maxRewardPointsApplicable = merchandiseAfterDiscount
                .multiply(BigDecimal("0.5"))
                .setScale(0, RoundingMode.DOWN)
                .toInt()
            val appliedRewardPoints = useRewardPoints.coerceIn(0, maxRewardPointsApplicable)
            val pointsValue = BigDecimal(appliedRewardPoints)

            val shipping = calculateShippingFee(subtotal, address, pickupType)

            // Calculate tax (10%)
            val taxableAmount = subtotal - discount - pointsValue
            val tax = if (taxableAmount > BigDecimal.ZERO) {
                taxableAmount * BigDecimal(0.10)
            } else {
                BigDecimal.ZERO
            }

            // Final total
            val total = subtotal - discount - pointsValue + shipping.fee + tax

            CheckoutTotals(
                subtotal = subtotal,
                discount = discount,
                shippingFee = shipping.fee,
                appliedRewardPoints = appliedRewardPoints,
                pointsUsedValue = pointsValue,
                tax = tax,
                total = total,
                promotion = promotion,
                shippingZone = shipping.zone
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
                isDefault = address[UserAddressesTable.isDefault],
                fullAddress = address[UserAddressesTable.fullAddress],
                wardCode = address[UserAddressesTable.wardCode],
                provinceCode = address[UserAddressesTable.provinceCode],
                latitude = address[UserAddressesTable.latitude]?.toDouble(),
                longitude = address[UserAddressesTable.longitude]?.toDouble(),
                locationSource = address[UserAddressesTable.locationSource]
            )

            // Get cart items
            val cartService = CartService()
            val cartSummary = cartService.getCartSummary(userId)

            // Calculate totals
            val totals = calculateTotals(userId, useRewardPoints, promoCode, address)

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
            if (request.isDirectCheckout()) {
                return@transaction createDirectOrder(userId, request)
            }

            // Validate cart first
            val (isValid, errors) = validateCart(userId)
            if (!isValid && request.selectedCartItemIds.isNullOrEmpty()) {
                throw IllegalArgumentException("Lỗi giỏ hàng: ${errors.joinToString(", ")}")
            }

            val selectedIds = normalizeSelectedCartItemIds(request.selectedCartItemIds)
            run {
                val checkoutLines = loadCheckoutLines(userId = userId, selectedCartItemIds = selectedIds)
                val selectedErrors = validateCheckoutLines(checkoutLines).toMutableList()
                val foundIds = checkoutLines.mapNotNull { it.cartItemId }.toSet()
                val missingIds = selectedIds.filterNot { it in foundIds }
                if (missingIds.isNotEmpty()) {
                    selectedErrors.add("Một số sản phẩm đã chọn không còn trong giỏ hàng")
                }
                if (selectedErrors.isNotEmpty()) {
                    throw IllegalArgumentException("Lỗi giỏ hàng: ${selectedErrors.joinToString(", ")}")
                }
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
            val totals = calculateTotals(
                userId = userId,
                useRewardPoints = request.rewardPointsToUse,
                promoCode = request.promoCode,
                address = address,
                pickupType = request.pickupType,
                selectedCartItemIds = selectedIds
            )
            val appliedRewardPoints = totals.appliedRewardPoints

            // Get cart items for order items
            val cartCondition = if (selectedIds.isEmpty()) {
                CartItemsTable.userId eq userId
            } else {
                (CartItemsTable.userId eq userId) and (CartItemsTable.id inList selectedIds)
            }
            val cartItems = (CartItemsTable innerJoin ProductsTable)
                .selectAll()
                .where { cartCondition }
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
                it[OrdersTable.shippingRecipientName] = address[UserAddressesTable.recipientName]
                it[OrdersTable.shippingPhone] = address[UserAddressesTable.phone]
                it[OrdersTable.shippingAddressText] = buildAddressText(address)
                it[OrdersTable.shippingProvinceCode] = address[UserAddressesTable.provinceCode]
                it[OrdersTable.shippingProvinceName] = address[UserAddressesTable.province]
                it[OrdersTable.shippingWardCode] = address[UserAddressesTable.wardCode]
                it[OrdersTable.shippingWardName] = address[UserAddressesTable.ward]
                it[OrdersTable.shippingLatitude] = address[UserAddressesTable.latitude]
                it[OrdersTable.shippingLongitude] = address[UserAddressesTable.longitude]
                it[OrdersTable.shippingZone] = totals.shippingZone
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

                // Stock allocation is handled by OrderFulfillmentService during fulfillment
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

            // Clear purchased cart items only.
            CartItemsTable.deleteWhere {
                if (selectedIds.isEmpty()) {
                    CartItemsTable.userId eq userId
                } else {
                    (CartItemsTable.userId eq userId) and (CartItemsTable.id inList selectedIds)
                }
            }

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

    private fun createDirectOrder(userId: String, request: CheckoutRequest): OrderDto {
        val checkoutLines = loadCheckoutLines(
            userId = userId,
            directProductId = request.directProductId,
            directQuantity = request.directQuantity,
            directUnit = request.directUnit
        )
        val errors = validateCheckoutLines(checkoutLines)
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException("Lỗi giỏ hàng: ${errors.joinToString(", ")}")
        }

        val address = UserAddressesTable
            .selectAll()
            .where {
                (UserAddressesTable.id eq request.addressId) and
                    (UserAddressesTable.userId eq userId)
            }
            .singleOrNull()
            ?: throw IllegalArgumentException("Địa chỉ không tìm thấy")

        val totals = calculateTotals(
            userId = userId,
            useRewardPoints = request.rewardPointsToUse,
            promoCode = request.promoCode,
            address = address,
            pickupType = request.pickupType,
            directProductId = request.directProductId,
            directQuantity = request.directQuantity,
            directUnit = request.directUnit
        )
        val appliedRewardPoints = totals.appliedRewardPoints

        if (request.rewardPointsToUse > appliedRewardPoints) {
            throw IllegalArgumentException("Reward points exceed the applicable limit for this order: $appliedRewardPoints")
        }

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

        val orderId = UUID.randomUUID().toString()
        val orderCode = "ORD-${System.currentTimeMillis().toString().takeLast(9)}"
        val pointsEarned = checkoutLines.sumOf { line -> line.rewardPoints * line.quantity }

        OrdersTable.insert {
            it[OrdersTable.id] = orderId
            it[OrdersTable.orderCode] = orderCode
            it[OrdersTable.userId] = userId
            it[OrdersTable.status] = "PENDING"
            it[OrdersTable.pickupType] = request.pickupType
            it[OrdersTable.addressId] = request.addressId
            it[OrdersTable.shippingRecipientName] = address[UserAddressesTable.recipientName]
            it[OrdersTable.shippingPhone] = address[UserAddressesTable.phone]
            it[OrdersTable.shippingAddressText] = buildAddressText(address)
            it[OrdersTable.shippingProvinceCode] = address[UserAddressesTable.provinceCode]
            it[OrdersTable.shippingProvinceName] = address[UserAddressesTable.province]
            it[OrdersTable.shippingWardCode] = address[UserAddressesTable.wardCode]
            it[OrdersTable.shippingWardName] = address[UserAddressesTable.ward]
            it[OrdersTable.shippingLatitude] = address[UserAddressesTable.latitude]
            it[OrdersTable.shippingLongitude] = address[UserAddressesTable.longitude]
            it[OrdersTable.shippingZone] = totals.shippingZone
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

        checkoutLines.forEach { line ->
            OrderItemsTable.insert {
                it[OrderItemsTable.id] = UUID.randomUUID().toString()
                it[OrderItemsTable.orderId] = orderId
                it[OrderItemsTable.productId] = line.productId
                it[OrderItemsTable.name] = line.productName
                it[OrderItemsTable.quantity] = line.quantity
                it[OrderItemsTable.price] = line.price
                it[OrderItemsTable.unit] = line.unit
            }

            ProductsTable.update({ ProductsTable.id eq line.productId }) {
                with(SqlExpressionBuilder) {
                    it[ProductsTable.stock] = ProductsTable.stock - line.quantity
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

        val createdOrder = OrdersTable
            .selectAll()
            .where { OrdersTable.id eq orderId }
            .singleOrNull()
            ?: throw IllegalArgumentException("Cannot retrieve created order")

        return OrderDto(
            id = createdOrder[OrdersTable.id],
            orderCode = createdOrder[OrdersTable.orderCode],
            userId = userId,
            status = createdOrder[OrdersTable.status],
            pickupType = createdOrder[OrdersTable.pickupType],
            subtotal = createdOrder[OrdersTable.subtotal],
            shippingFee = createdOrder[OrdersTable.shippingFee],
            discount = createdOrder[OrdersTable.discount],
            pointsUsed = appliedRewardPoints,
            pointsEarned = pointsEarned,
            total = createdOrder[OrdersTable.total],
            paymentMethod = createdOrder[OrdersTable.paymentMethod],
            paymentStatus = createdOrder[OrdersTable.paymentStatus],
            notes = request.notes,
            createdAt = createdOrder[OrdersTable.createdAt]
        )
    }

    private fun calculateShippingFee(
        subtotal: BigDecimal,
        address: ResultRow?,
        pickupType: String = "DELIVERY"
    ): ShippingFeeResult {
        if (pickupType.equals("PICKUP", ignoreCase = true)) {
            return ShippingFeeResult(BigDecimal.ZERO.setScale(2), "PICKUP")
        }

        if (subtotal >= BigDecimal(500000)) {
            return ShippingFeeResult(BigDecimal.ZERO.setScale(2), "FREESHIP")
        }

        val province = normalizeLocationText(address?.get(UserAddressesTable.province))
        val provinceCode = address?.get(UserAddressesTable.provinceCode).orEmpty()
        val lat = address?.get(UserAddressesTable.latitude)?.toDouble()
        val lng = address?.get(UserAddressesTable.longitude)?.toDouble()
        val isHanoi = provinceCode == "01" || province.contains("ha noi")

        if (lat != null && lng != null && (isHanoi || province.isBlank() && provinceCode.isBlank())) {
            val isInnerCity = distanceFromShopKm(lat, lng) <= INNER_CITY_RADIUS_KM
            return if (isInnerCity) {
                ShippingFeeResult(BigDecimal(10000).setScale(2), "INNER_CITY")
            } else {
                ShippingFeeResult(BigDecimal(20000).setScale(2), "OUTER_CITY")
            }
        }

        if (isHanoi) {
            val district = normalizeLocationText(address?.get(UserAddressesTable.district))
            val isInnerCity = district in INNER_HANOI_DISTRICTS
            return if (isInnerCity) {
                ShippingFeeResult(BigDecimal(10000).setScale(2), "INNER_CITY")
            } else {
                ShippingFeeResult(BigDecimal(20000).setScale(2), "OUTER_CITY")
            }
        }

        return ShippingFeeResult(BigDecimal(30000).setScale(2), "OTHER_PROVINCE")
    }

    private fun normalizeLocationText(value: String?): String {
        val normalized = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFD)
        return normalized.replace("\\p{Mn}+".toRegex(), "").lowercase()
    }

    private fun distanceFromShopKm(lat: Double, lng: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat - SHOP_LAT)
        val dLng = Math.toRadians(lng - SHOP_LNG)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(SHOP_LAT)) * cos(Math.toRadians(lat)) * sin(dLng / 2).pow(2.0)
        return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun buildAddressText(address: ResultRow): String {
        address[UserAddressesTable.fullAddress]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return listOf(
            address[UserAddressesTable.address],
            address[UserAddressesTable.ward],
            address[UserAddressesTable.district],
            address[UserAddressesTable.province]
        )
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .joinToString(", ")
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
    val promotion: CheckoutPromotion? = null,
    val shippingZone: String? = null
)

private data class ShippingFeeResult(
    val fee: BigDecimal,
    val zone: String
)

private const val SHOP_LAT = 20.9802
private const val SHOP_LNG = 105.7870
private const val INNER_CITY_RADIUS_KM = 12.0
private val INNER_HANOI_DISTRICTS = setOf(
    "ba dinh",
    "hoan kiem",
    "tay ho",
    "long bien",
    "cau giay",
    "dong da",
    "hai ba trung",
    "hoang mai",
    "thanh xuan",
    "ha dong",
    "nam tu liem",
    "bac tu liem"
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
