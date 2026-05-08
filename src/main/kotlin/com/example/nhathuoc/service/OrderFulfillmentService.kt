package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.OrderItemsTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.ProductsTable
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

data class OrderFulfillmentResult(
    val orderId: String,
    val successful: Boolean,
    val message: String,
    val allocatedStock: List<OrderItemStockAllocation>? = null
)

data class OrderItemStockAllocation(
    val orderItemId: String,
    val productId: String,
    val productName: String,
    val requestedQuantity: Int,
    val allocatedStock: List<StockAllocation>
)

data class StockAllocation(
    val stockId: String,
    val expDate: LocalDate?,
    val allocatedQuantity: Int
)

class OrderFulfillmentService(
    private val inventoryService: InventoryService = InventoryService()
) {

    fun confirmPack(orderId: String, shopId: String): OrderFulfillmentResult {
        return transaction {
            try {
                validateOrderForConfirmPack(orderId)
                val orderItems = loadOrderItems(orderId)

                if (orderItems.isEmpty()) {
                    return@transaction OrderFulfillmentResult(
                        orderId = orderId,
                        successful = false,
                        message = "Order has no items"
                    )
                }

                val allAllocations = mutableListOf<OrderItemStockAllocation>()
                val stockCommitments = mutableListOf<Pair<String, Int>>()

                for (orderItem in orderItems) {
                    val productId = orderItem.productId ?: continue
                    try {
                        val allocations = inventoryService.reserveStockForProduct(
                            productId = productId,
                            shopId = shopId,
                            totalQuantityNeeded = orderItem.quantity
                        )
                        stockCommitments.addAll(allocations)

                        val product = ProductsTable
                            .selectAll()
                            .where { ProductsTable.id eq productId }
                            .single()

                        allAllocations += OrderItemStockAllocation(
                            orderItemId = orderItem.id,
                            productId = productId,
                            productName = orderItem.name,
                            requestedQuantity = orderItem.quantity,
                            allocatedStock = allocations.map { (allocatedProductId, allocatedQuantity) ->
                                StockAllocation(
                                    stockId = allocatedProductId,
                                    expDate = product[ProductsTable.expDate],
                                    allocatedQuantity = allocatedQuantity
                                )
                            }
                        )
                    } catch (e: IllegalStateException) {
                        return@transaction OrderFulfillmentResult(
                            orderId = orderId,
                            successful = false,
                            message = "Insufficient inventory for product ${orderItem.name}: ${e.message}"
                        )
                    }
                }

                inventoryService.commitStockReservations(stockCommitments)

                OrdersTable.update({ OrdersTable.id eq orderId }) {
                    it[OrdersTable.status] = "PROCESSING"
                }

                OrderFulfillmentResult(
                    orderId = orderId,
                    successful = true,
                    message = "Order confirmed and packed successfully",
                    allocatedStock = allAllocations
                )
            } catch (e: Exception) {
                OrderFulfillmentResult(
                    orderId = orderId,
                    successful = false,
                    message = "Failed to confirm pack: ${e.message}"
                )
            }
        }
    }

    private fun validateOrderForConfirmPack(orderId: String): OrderInfo {
        val order = OrdersTable
            .selectAll()
            .where { OrdersTable.id eq orderId }
            .singleOrNull()
            ?: throw IllegalArgumentException("Order not found")

        val currentStatus = order[OrdersTable.status]
        if (currentStatus != "PENDING") {
            throw IllegalArgumentException("Order status must be PENDING to confirm pack. Current status: $currentStatus")
        }

        return OrderInfo(
            id = order[OrdersTable.id],
            status = currentStatus
        )
    }

    fun getOrderFulfillmentDetails(orderId: String, shopId: String): List<OrderItemStockAllocation> {
        return transaction {
            OrdersTable
                .selectAll()
                .where { OrdersTable.id eq orderId }
                .singleOrNull()
                ?: throw IllegalArgumentException("Order not found")

            loadOrderItems(orderId).map { orderItem ->
                val productId = orderItem.productId
                val product = productId?.let {
                    ProductsTable
                        .selectAll()
                        .where { ProductsTable.id eq it }
                        .singleOrNull()
                }

                OrderItemStockAllocation(
                    orderItemId = orderItem.id,
                    productId = productId ?: "",
                    productName = orderItem.name,
                    requestedQuantity = orderItem.quantity,
                    allocatedStock = if (productId != null) {
                        listOf(
                            StockAllocation(
                                stockId = productId,
                                expDate = product?.get(ProductsTable.expDate),
                                allocatedQuantity = orderItem.quantity
                            )
                        )
                    } else {
                        emptyList()
                    }
                )
            }
        }
    }

    private fun loadOrderItems(orderId: String): List<OrderItemInfo> {
        return OrderItemsTable
            .selectAll()
            .where { OrderItemsTable.orderId eq orderId }
            .map { row ->
                OrderItemInfo(
                    id = row[OrderItemsTable.id],
                    productId = row[OrderItemsTable.productId],
                    quantity = row[OrderItemsTable.quantity],
                    name = row[OrderItemsTable.name]
                )
            }
    }
}

private data class OrderInfo(
    val id: String,
    val status: String
)

private data class OrderItemInfo(
    val id: String,
    val productId: String?,
    val quantity: Int,
    val name: String
)
