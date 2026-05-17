package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.util.*

// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// DTOs
// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

data class CartItemDto(
    val id: String,
    val userId: String,
    val productId: String,
    val productName: String,
    val productPrice: BigDecimal,
    val productImageUrl: String?,
    val quantity: Int,
    val unit: String,
    val subtotal: BigDecimal,
    val isAvailable: Boolean,
    val stock: Int,
    val createdAt: LocalDateTime
)

data class CartSummaryDto(
    val items: List<CartItemDto>,
    val totalItems: Int,
    val subtotal: BigDecimal,
    val estimatedShipping: BigDecimal = BigDecimal.ZERO,
    val total: BigDecimal
)

@Serializable
data class AddToCartRequest(
    val productId: String,
    val quantity: Int = 1,
    val unit: String = "Cái"
)

@Serializable
data class UpdateCartItemRequest(
    val quantity: Int,
    val unit: String? = null
)

// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// SERVICE
// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class CartService {

    /**
     * Add product to user's cart
     */
    fun addToCart(userId: String, request: AddToCartRequest): String {
        return transaction {
            // Check if product exists and is active
            val product = ProductsTable
                .selectAll()
                .where { (ProductsTable.id eq request.productId) and (ProductsTable.isActive eq true) }
                .singleOrNull()
                ?: throw IllegalArgumentException("Product not found or inactive")

            val productStock = product[ProductsTable.stock]
            val riskClassification = product[ProductsTable.riskClassification].uppercase()
            if (riskClassification == "C" || riskClassification == "D") {
                throw IllegalArgumentException(
                    "Sản phẩm loại $riskClassification cần tư vấn/ký kết trực tiếp tại Medstore, không hỗ trợ đặt online"
                )
            }

            if (productStock < request.quantity) {
                throw IllegalArgumentException("Insufficient stock. Available: $productStock")
            }

            // Check if item already exists in cart
            val existingItem = CartItemsTable
                .selectAll()
                .where {
                    (CartItemsTable.userId eq userId) and
                    (CartItemsTable.productId eq request.productId)
                }
                .singleOrNull()

            if (existingItem != null) {
                // Update existing item quantity
                val currentQuantity = existingItem[CartItemsTable.quantity]
                val newQuantity = currentQuantity + request.quantity

                if (newQuantity > productStock) {
                    throw IllegalArgumentException("Total quantity exceeds stock. Available: $productStock")
                }

                CartItemsTable.update({ CartItemsTable.id eq existingItem[CartItemsTable.id] }) {
                    it[CartItemsTable.quantity] = newQuantity
                }

                existingItem[CartItemsTable.id]
            } else {
                // Create new cart item
                val cartItemId = UUID.randomUUID().toString()

                CartItemsTable.insert {
                    it[CartItemsTable.id] = cartItemId
                    it[CartItemsTable.userId] = userId
                    it[CartItemsTable.productId] = request.productId
                    it[CartItemsTable.quantity] = request.quantity
                    it[CartItemsTable.unit] = request.unit
                }

                cartItemId
            }
        }
    }

    /**
     * Update cart item quantity
     */
    fun updateCartItem(userId: String, cartItemId: String, request: UpdateCartItemRequest) {
        transaction {
            // Verify cart item belongs to user
            val cartItem = CartItemsTable
                .selectAll()
                .where { (CartItemsTable.id eq cartItemId) and (CartItemsTable.userId eq userId) }
                .singleOrNull()
                ?: throw IllegalArgumentException("Cart item not found")

            if (request.quantity <= 0) {
                throw IllegalArgumentException("Quantity must be greater than 0")
            }

            // Check product stock
            val product = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq cartItem[CartItemsTable.productId] }
                .single()

            val availableStock = product[ProductsTable.stock]
            if (request.quantity > availableStock) {
                throw IllegalArgumentException("Insufficient stock. Available: $availableStock")
            }

            CartItemsTable.update({ CartItemsTable.id eq cartItemId }) {
                it[quantity] = request.quantity
            }
        }
    }

    /**
     * Remove item from cart
     */
    fun removeFromCart(userId: String, cartItemId: String) {
        transaction {
            val deletedCount = CartItemsTable.deleteWhere {
                (CartItemsTable.id eq cartItemId) and (CartItemsTable.userId eq userId)
            }

            if (deletedCount == 0) {
                throw IllegalArgumentException("Cart item not found")
            }
        }
    }

    /**
     * Clear user's entire cart
     */
    fun clearCart(userId: String) {
        transaction {
            CartItemsTable.deleteWhere { CartItemsTable.userId eq userId }
        }
    }

    /**
     * Get user's cart with detailed product information
     */
    fun getCartSummary(userId: String): CartSummaryDto {
        return transaction {
            val cartItems = (CartItemsTable innerJoin ProductsTable)
                .selectAll()
                .where { CartItemsTable.userId eq userId }
                .orderBy(CartItemsTable.createdAt to SortOrder.DESC)
                .map { row ->
                    val quantity = row[CartItemsTable.quantity]
                    val price = row[ProductsTable.price]
                    val subtotal = price * quantity.toBigDecimal()
                    val isAvailable = row[ProductsTable.isActive] && row[ProductsTable.stock] >= quantity

                    // Get first product image
                    val imageUrl = ProductImagesTable
                        .selectAll()
                        .where { ProductImagesTable.productId eq row[ProductsTable.id] }
                        .orderBy(ProductImagesTable.sortOrder to SortOrder.ASC)
                        .limit(1)
                        .singleOrNull()
                        ?.get(ProductImagesTable.url)

                    CartItemDto(
                        id = row[CartItemsTable.id],
                        userId = row[CartItemsTable.userId],
                        productId = row[ProductsTable.id],
                        productName = row[ProductsTable.name],
                        productPrice = price,
                        productImageUrl = imageUrl,
                        quantity = quantity,
                        unit = row[CartItemsTable.unit],
                        subtotal = subtotal,
                        isAvailable = isAvailable,
                        stock = row[ProductsTable.stock],
                        createdAt = row[CartItemsTable.createdAt]
                    )
                }

            val totalItems = cartItems.sumOf { it.quantity }
            val subtotal = cartItems
                .filter { it.isAvailable }
                .sumOf { it.subtotal }

            val estimatedShipping = if (subtotal >= BigDecimal(500000)) BigDecimal.ZERO else BigDecimal(30000)
            val total = subtotal + estimatedShipping

            CartSummaryDto(
                items = cartItems,
                totalItems = totalItems,
                subtotal = subtotal,
                estimatedShipping = estimatedShipping,
                total = total
            )
        }
    }

    /**
     * Get cart item count for badge display
     */
    fun getCartItemCount(userId: String): Int {
        return transaction {
            CartItemsTable
                .selectAll()
                .where { CartItemsTable.userId eq userId }
                .sumOf { it[CartItemsTable.quantity] }
        }
    }

    /**
     * Validate cart before checkout
     */
    fun validateCartForCheckout(userId: String): List<String> {
        return transaction {
            val errors = mutableListOf<String>()

            val cartItems = (CartItemsTable innerJoin ProductsTable)
                .selectAll()
                .where { CartItemsTable.userId eq userId }
                .toList()

            if (cartItems.isEmpty()) {
                errors.add("Cart is empty")
                return@transaction errors
            }

            cartItems.forEach { row ->
                val productName = row[ProductsTable.name]
                val requestedQty = row[CartItemsTable.quantity]
                val availableStock = row[ProductsTable.stock]
                val isActive = row[ProductsTable.isActive]
                val riskClassification = row[ProductsTable.riskClassification].uppercase()

                if (!isActive) {
                    errors.add("Product '$productName' is no longer available")
                }

                if (riskClassification == "C" || riskClassification == "D") {
                    errors.add("Product '$productName' requires direct consultation at pharmacy")
                }

                if (requestedQty > availableStock) {
                    errors.add("Insufficient stock for '$productName'. Available: $availableStock, Requested: $requestedQty")
                }
            }

            errors
        }
    }

    /**
     * Remove unavailable items from cart
     */
    fun cleanupCart(userId: String): Int {
        return transaction {
            val unavailableItems = (CartItemsTable innerJoin ProductsTable)
                .selectAll()
                .where {
                    (CartItemsTable.userId eq userId) and
                    ((ProductsTable.isActive eq false) or
                     (CartItemsTable.quantity greater ProductsTable.stock))
                }
                .map { it[CartItemsTable.id] }

            if (unavailableItems.isNotEmpty()) {
                CartItemsTable.deleteWhere {
                    CartItemsTable.id inList unavailableItems
                }
            }

            unavailableItems.size
        }
    }
}
