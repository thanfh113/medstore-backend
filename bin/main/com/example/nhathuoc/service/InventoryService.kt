package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.daysUntil
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.util.*

data class CreateBatchRequest(
    val productId: String,
    val lotNumber: String? = null,
    val mfgDate: LocalDate? = null,
    val expDate: LocalDate? = null,
    val quantity: Int,
    val importPrice: BigDecimal? = null,
    val note: String? = null
)

data class BatchDto(
    val id: String,
    val productId: String,
    val productName: String,
    val lotNumber: String?,
    val mfgDate: LocalDate?,
    val expDate: LocalDate?,
    val quantityOnHand: Int,
    val importPrice: BigDecimal?,
    val note: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val daysUntilExpiry: Long?
)

data class ExpiringBatchAlert(
    val id: String,
    val productId: String,
    val productName: String,
    val lotNumber: String?,
    val expDate: LocalDate,
    val quantityOnHand: Int,
    val daysUntilExpiry: Long
)

class InventoryService {

    /**
     * Create a new product batch for inventory
     */
    fun createBatch(shopId: String, request: CreateBatchRequest): String {
        return createBatch(request)
    }

    fun createBatch(request: CreateBatchRequest): String {
        return transaction {
            // Validate product exists
            val product = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq request.productId }
                .singleOrNull()
                ?: throw IllegalArgumentException("Product not found")

            val batchId = UUID.randomUUID().toString()

            // Insert product batch (no shopId field in actual DB)
            ProductBatchesTable.insert {
                it[ProductBatchesTable.id] = batchId
                it[ProductBatchesTable.productId] = request.productId
                // shopId removed - not in actual DB schema
                it[ProductBatchesTable.lotNumber] = request.lotNumber
                it[ProductBatchesTable.mfgDate] = request.mfgDate
                it[ProductBatchesTable.expDate] = request.expDate
                it[ProductBatchesTable.quantityOnHand] = request.quantity
                it[ProductBatchesTable.importPrice] = request.importPrice
                it[ProductBatchesTable.note] = request.note
            }

            // Update product stock
            ProductsTable.update({ ProductsTable.id eq request.productId }) {
                it[stock] = stock + request.quantity
            }

            batchId
        }
    }

    /**
     * Get batches with filtering options
     */
    fun getBatches(
        shopId: String? = null,
        productId: String? = null,
        expired: Boolean? = null,
        expWithinDays: Int? = null,
        expBefore: LocalDate? = null,
        page: Int = 1,
        limit: Int = 20
    ): List<BatchDto> {
        return transaction {
            val today = Clock.System.todayIn(TimeZone.UTC)

            var query = ProductBatchesTable
                .join(ProductsTable, JoinType.INNER) { ProductBatchesTable.productId eq ProductsTable.id }
                .selectAll()


            // Filter by product if specified
            if (productId != null) {
                query = query.andWhere { ProductBatchesTable.productId eq productId }
            }

            // Filter by expiration status
            when (expired) {
                true -> query = query.andWhere {
                    ProductBatchesTable.expDate.isNotNull() and
                    (ProductBatchesTable.expDate lessEq today)
                }
                false -> query = query.andWhere {
                    ProductBatchesTable.expDate.isNull() or
                    (ProductBatchesTable.expDate greater today)
                }
                null -> { /* No filtering by expiration status */ }
            }

            // Filter by expiring within days
            if (expWithinDays != null) {
                val targetDate = today.plus(DatePeriod(days = expWithinDays))
                query = query.andWhere {
                    ProductBatchesTable.expDate.isNotNull() and
                    (ProductBatchesTable.expDate lessEq targetDate) and
                    (ProductBatchesTable.expDate greater today) and
                    (ProductBatchesTable.quantityOnHand greater 0)
                }
            }

            // Filter by exp before date
            if (expBefore != null) {
                query = query.andWhere {
                    ProductBatchesTable.expDate.isNotNull() and
                    (ProductBatchesTable.expDate lessEq expBefore)
                }
            }

            // Only get batches with quantity > 0 unless specifically filtering expired
            if (expired != true) {
                query = query.andWhere { ProductBatchesTable.quantityOnHand greater 0 }
            }

            val offset = (page - 1) * limit
            query.orderBy(ProductBatchesTable.expDate to SortOrder.ASC, ProductBatchesTable.createdAt to SortOrder.ASC)
                .limit(limit, offset.toLong())
                .map { row ->
                    val expDate = row[ProductBatchesTable.expDate]
                    val daysUntilExpiry = expDate?.let { exp ->
                        today.daysUntil(exp)
                    }

                    BatchDto(
                        id = row[ProductBatchesTable.id],
                        productId = row[ProductBatchesTable.productId],
                        productName = row[ProductsTable.name],
                        lotNumber = row[ProductBatchesTable.lotNumber],
                        mfgDate = row[ProductBatchesTable.mfgDate],
                        expDate = row[ProductBatchesTable.expDate],
                        quantityOnHand = row[ProductBatchesTable.quantityOnHand],
                        importPrice = row[ProductBatchesTable.importPrice],
                        note = row[ProductBatchesTable.note],
                        createdAt = row[ProductBatchesTable.createdAt],
                        updatedAt = row[ProductBatchesTable.updatedAt],
                        daysUntilExpiry = daysUntilExpiry?.toLong()
                    )
                }
        }
    }

    /**
     * Get expiring batch alerts
     */
    fun getExpiringBatches(shopId: String, days: Int): List<ExpiringBatchAlert> {
        return getExpiringBatches(days)
    }

    fun getExpiringBatches(days: Int): List<ExpiringBatchAlert> {
        return transaction {
            val today = Clock.System.todayIn(TimeZone.UTC)
            val alertDate = today.plus(DatePeriod(days = days))

            var query = ProductBatchesTable
                .join(ProductsTable, JoinType.INNER) { ProductBatchesTable.productId eq ProductsTable.id }
                .selectAll()
                .where {
                    ProductBatchesTable.expDate.isNotNull() and
                    (ProductBatchesTable.quantityOnHand greater 0) and
                    (ProductBatchesTable.expDate greaterEq today) and
                    (ProductBatchesTable.expDate lessEq alertDate)
                }

            query.orderBy(ProductBatchesTable.expDate to SortOrder.ASC)
                .map { row ->
                    val expDate = row[ProductBatchesTable.expDate]!!
                    val daysUntilExpiry = today.daysUntil(expDate)

                    ExpiringBatchAlert(
                        id = row[ProductBatchesTable.id],
                        productId = row[ProductBatchesTable.productId],
                        productName = row[ProductsTable.name],
                        lotNumber = row[ProductBatchesTable.lotNumber],
                        expDate = expDate,
                        quantityOnHand = row[ProductBatchesTable.quantityOnHand],
                        daysUntilExpiry = daysUntilExpiry.toLong()
                    )
                }
        }
    }

    /**
     * Allocate batches for order fulfillment (FEFO - First Expired First Out)
     * Returns list of (batchId, allocatedQuantity) pairs
     */
    fun allocateBatchesForProduct(
        productId: String,
        shopId: String,
        totalQuantityNeeded: Int
    ): List<Pair<String, Int>> {
        return allocateBatchesForProduct(productId, totalQuantityNeeded)
    }

    fun allocateBatchesForProduct(
        productId: String,
        totalQuantityNeeded: Int
    ): List<Pair<String, Int>> {
        return transaction {
            val today = Clock.System.todayIn(TimeZone.UTC)
            val minExpDate = today.plus(DatePeriod(days = 5)) // Block batches expiring within 5 days

            // Get available batches ordered by FEFO logic
            val availableBatches = ProductBatchesTable
                .join(ProductsTable, JoinType.INNER) { ProductBatchesTable.productId eq ProductsTable.id }
                .selectAll()
                .where {
                    (ProductBatchesTable.productId eq productId) and
                    (ProductBatchesTable.quantityOnHand greater 0) and
                    (
                        ProductBatchesTable.expDate.isNull() or
                        (ProductBatchesTable.expDate greater minExpDate)
                    )
                }
                .orderBy(
                    // Order by expiry date (nulls last), then by creation time
                    ProductBatchesTable.expDate to SortOrder.ASC_NULLS_LAST,
                    ProductBatchesTable.createdAt to SortOrder.ASC
                )

            val allocations = mutableListOf<Pair<String, Int>>()
            var remainingQuantity = totalQuantityNeeded

            for (batch in availableBatches) {
                if (remainingQuantity <= 0) break

                val batchId = batch[ProductBatchesTable.id]
                val availableQuantity = batch[ProductBatchesTable.quantityOnHand]

                val allocateQuantity = minOf(remainingQuantity, availableQuantity)
                allocations.add(Pair(batchId, allocateQuantity))
                remainingQuantity -= allocateQuantity
            }

            if (remainingQuantity > 0) {
                throw IllegalStateException("Insufficient inventory. Need $totalQuantityNeeded, can allocate ${totalQuantityNeeded - remainingQuantity}")
            }

            allocations
        }
    }

    /**
     * Commit batch allocations (used in order fulfillment)
     */
    fun commitBatchAllocations(allocations: List<Pair<String, Int>>) {
        transaction {
            for ((batchId, quantity) in allocations) {
                // Update batch quantity
                val updatedRows = ProductBatchesTable.update(
                    {
                        (ProductBatchesTable.id eq batchId) and
                        (ProductBatchesTable.quantityOnHand greaterEq quantity)
                    }
                ) {
                    it[quantityOnHand] = quantityOnHand minus quantity
                }

                if (updatedRows == 0) {
                    throw IllegalStateException("Failed to allocate $quantity from batch $batchId. Insufficient quantity or batch not found.")
                }

                // Get product ID to update product stock
                val productId = ProductBatchesTable
                    .selectAll()
                    .where { ProductBatchesTable.id eq batchId }
                    .single()[ProductBatchesTable.productId]

                // Update product stock
                ProductsTable.update({ ProductsTable.id eq productId }) {
                    it[stock] = stock minus quantity
                }
            }
        }
    }
}
