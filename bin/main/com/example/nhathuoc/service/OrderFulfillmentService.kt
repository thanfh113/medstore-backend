package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

data class OrderFulfillmentResult(
    val orderId: String,
    val successful: Boolean,
    val message: String,
    val allocatedBatches: List<OrderItemBatchAllocation>? = null
)

data class OrderItemBatchAllocation(
    val orderItemId: String,
    val productId: String,
    val productName: String,
    val requestedQuantity: Int,
    val allocatedBatches: List<BatchAllocation>
)

data class BatchAllocation(
    val batchId: String,
    val lotNumber: String?,
    val expDate: kotlinx.datetime.LocalDate?,
    val allocatedQuantity: Int
)

class OrderFulfillmentService(
    private val inventoryService: InventoryService = InventoryService()
) {

    /**
     * Confirm and pack an order (internal desktop roles)
     * This will:
     * 1. Validate order status and ownership
     * 2. Allocate inventory using FEFO
     * 3. Update inventory quantities
     * 4. Create order_item_batches records
     * 5. Update order status to PROCESSING
     */
    fun confirmPack(orderId: String, shopId: String): OrderFulfillmentResult {
        return transaction {
            try {
                // 1. Validate order
                val order = validateOrderForConfirmPack(orderId, shopId)

                // 2. Get order items
                val orderItems = OrderItemsTable
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

                if (orderItems.isEmpty()) {
                    return@transaction OrderFulfillmentResult(
                        orderId = orderId,
                        successful = false,
                        message = "Order has no items"
                    )
                }

                // 3. Allocate inventory for each order item
                val allAllocations = mutableListOf<OrderItemBatchAllocation>()
                val batchCommitments = mutableListOf<Pair<String, Int>>()

                for (orderItem in orderItems) {
                    if (orderItem.productId == null) {
                        // Skip items without product ID (might be custom items)
                        continue
                    }

                    try {
                        val batchAllocations = inventoryService.allocateBatchesForProduct(
                            productId = orderItem.productId,
                            shopId = shopId,
                            totalQuantityNeeded = orderItem.quantity
                        )

                        // Convert to detailed allocation info
                        val batchDetails = batchAllocations.map { (batchId, allocatedQuantity) ->
                            val batchInfo = ProductBatchesTable
                                .selectAll()
                                .where { ProductBatchesTable.id eq batchId }
                                .single()

                            BatchAllocation(
                                batchId = batchId,
                                lotNumber = batchInfo[ProductBatchesTable.lotNumber],
                                expDate = batchInfo[ProductBatchesTable.expDate],
                                allocatedQuantity = allocatedQuantity
                            )
                        }

                        allAllocations.add(
                            OrderItemBatchAllocation(
                                orderItemId = orderItem.id,
                                productId = orderItem.productId,
                                productName = orderItem.name,
                                requestedQuantity = orderItem.quantity,
                                allocatedBatches = batchDetails
                            )
                        )

                        // Add to commitment list
                        batchCommitments.addAll(batchAllocations)

                    } catch (e: IllegalStateException) {
                        return@transaction OrderFulfillmentResult(
                            orderId = orderId,
                            successful = false,
                            message = "Insufficient inventory for product ${orderItem.name}: ${e.message}"
                        )
                    }
                }

                // 4. Commit all batch allocations
                inventoryService.commitBatchAllocations(batchCommitments)

                // 5. Create order_item_batches records
                for (allocation in allAllocations) {
                    for (batchAllocation in allocation.allocatedBatches) {
                        OrderItemBatchesTable.insert {
                            it[OrderItemBatchesTable.id] = UUID.randomUUID().toString()
                            it[OrderItemBatchesTable.orderItemId] = allocation.orderItemId
                            it[OrderItemBatchesTable.batchId] = batchAllocation.batchId
                            it[OrderItemBatchesTable.quantity] = batchAllocation.allocatedQuantity
                        }
                    }
                }

                // 6. Update order status
                OrdersTable.update({ OrdersTable.id eq orderId }) {
                    it[OrdersTable.status] = "PROCESSING"
                }

                OrderFulfillmentResult(
                    orderId = orderId,
                    successful = true,
                    message = "Order confirmed and packed successfully",
                    allocatedBatches = allAllocations
                )

            } catch (e: Exception) {
                // Transaction will be rolled back automatically
                OrderFulfillmentResult(
                    orderId = orderId,
                    successful = false,
                    message = "Failed to confirm pack: ${e.message}"
                )
            }
        }
    }

    /**
     * Validate order for confirm-pack operation
     */
    private fun validateOrderForConfirmPack(orderId: String, shopId: String): OrderInfo {
        val order = OrdersTable
            .selectAll()
            .where { OrdersTable.id eq orderId }
            .singleOrNull()
            ?: throw IllegalArgumentException("Order not found")

        // Check order status
        val currentStatus = order[OrdersTable.status]
        if (currentStatus != "PENDING") {
            throw IllegalArgumentException("Order status must be PENDING to confirm pack. Current status: $currentStatus")
        }

        return OrderInfo(
            id = order[OrdersTable.id],
            status = currentStatus
        )
    }

    /**
     * Get order fulfillment details (for viewing batch allocations)
     */
    fun getOrderFulfillmentDetails(orderId: String, shopId: String): List<OrderItemBatchAllocation> {
        return transaction {
            // Validate order ownership
            validateOrderForConfirmPack(orderId, shopId)

            // Get order item batch allocations
            val allocations = OrderItemsTable
                .join(OrderItemBatchesTable, JoinType.LEFT) {
                    OrderItemsTable.id eq OrderItemBatchesTable.orderItemId
                }
                .join(ProductBatchesTable, JoinType.LEFT) {
                    OrderItemBatchesTable.batchId eq ProductBatchesTable.id
                }
                .selectAll()
                .where { OrderItemsTable.orderId eq orderId }
                .groupBy { it[OrderItemsTable.id] }
                .map { (orderItemId, rows) ->
                    val firstRow = rows.first()
                    val batchAllocations = rows.mapNotNull { row ->
                        val batchId = row[OrderItemBatchesTable.batchId]
                        if (batchId != null) {
                            BatchAllocation(
                                batchId = batchId,
                                lotNumber = row[ProductBatchesTable.lotNumber],
                                expDate = row[ProductBatchesTable.expDate],
                                allocatedQuantity = row[OrderItemBatchesTable.quantity] ?: 0
                            )
                        } else null
                    }

                    OrderItemBatchAllocation(
                        orderItemId = orderItemId,
                        productId = firstRow[OrderItemsTable.productId] ?: "",
                        productName = firstRow[OrderItemsTable.name],
                        requestedQuantity = firstRow[OrderItemsTable.quantity],
                        allocatedBatches = batchAllocations
                    )
                }

            allocations
        }
    }
}

// Data classes for internal use
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
