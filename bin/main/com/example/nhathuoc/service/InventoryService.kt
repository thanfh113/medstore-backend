package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.ProductsTable
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import java.math.BigDecimal

data class CreateStockReceiptRequest(
    val productId: String,
    val mfgDate: LocalDate? = null,
    val expDate: LocalDate? = null,
    val quantity: Int,
    val importPrice: BigDecimal? = null,
    val note: String? = null
)

data class StockEntryDto(
    val id: String,
    val productId: String,
    val productName: String,
    val mfgDate: LocalDate?,
    val expDate: LocalDate?,
    val quantityOnHand: Int,
    val importPrice: BigDecimal?,
    val note: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val daysUntilExpiry: Long?
)

data class ExpiringStockAlert(
    val id: String,
    val productId: String,
    val productName: String,
    val expDate: LocalDate,
    val quantityOnHand: Int,
    val daysUntilExpiry: Long
)

class InventoryService {

    fun receiveStock(shopId: String, request: CreateStockReceiptRequest): String {
        return receiveStock(request)
    }

    fun receiveStock(request: CreateStockReceiptRequest): String {
        require(request.quantity > 0) { "Quantity must be greater than 0" }

        return transaction {
            val product = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq request.productId }
                .singleOrNull()
                ?: throw IllegalArgumentException("Product not found")

            val noteParts = listOfNotNull(request.note?.takeIf { it.isNotBlank() })
            val mergedNote = noteParts
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" | ")
                ?: product[ProductsTable.inventoryNote]

            ProductsTable.update({ ProductsTable.id eq request.productId }) {
                it[ProductsTable.stock] = ProductsTable.stock plus request.quantity
                request.importPrice?.let { value -> it[ProductsTable.importPrice] = value }
                request.mfgDate?.let { value -> it[ProductsTable.mfgDate] = value }
                request.expDate?.let { value -> it[ProductsTable.expDate] = value }
                mergedNote?.let { value -> it[ProductsTable.inventoryNote] = value }
                it[ProductsTable.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            }

            request.productId
        }
    }

    fun getStockEntries(
        shopId: String? = null,
        productId: String? = null,
        expired: Boolean? = null,
        expWithinDays: Int? = null,
        expBefore: LocalDate? = null,
        page: Int = 1,
        limit: Int = 20
    ): List<StockEntryDto> {
        return transaction {
            val today = Clock.System.todayIn(TimeZone.UTC)
            var query = ProductsTable
                .selectAll()
                .where { ProductsTable.deletedAt.isNull() }

            if (productId != null) {
                query = query.andWhere { ProductsTable.id eq productId }
            }

            when (expired) {
                true -> query = query.andWhere {
                    ProductsTable.expDate.isNotNull() and (ProductsTable.expDate lessEq today)
                }
                false -> query = query.andWhere {
                    ProductsTable.expDate.isNull() or (ProductsTable.expDate greater today)
                }
                null -> Unit
            }

            if (expWithinDays != null) {
                val targetDate = today.plus(DatePeriod(days = expWithinDays))
                query = query.andWhere {
                    ProductsTable.expDate.isNotNull() and
                        (ProductsTable.expDate lessEq targetDate) and
                        (ProductsTable.expDate greater today) and
                        (ProductsTable.stock greater 0)
                }
            }

            if (expBefore != null) {
                query = query.andWhere {
                    ProductsTable.expDate.isNotNull() and (ProductsTable.expDate lessEq expBefore)
                }
            }

            if (expired != true) {
                query = query.andWhere { ProductsTable.stock greater 0 }
            }

            val offset = ((page - 1).coerceAtLeast(0)) * limit.coerceAtLeast(1)
            query
                .orderBy(ProductsTable.expDate to SortOrder.ASC_NULLS_LAST, ProductsTable.createdAt to SortOrder.ASC)
                .limit(limit.coerceAtLeast(1), offset.toLong())
                .map { row ->
                    val expDate = row[ProductsTable.expDate]
                    StockEntryDto(
                        id = row[ProductsTable.id],
                        productId = row[ProductsTable.id],
                        productName = row[ProductsTable.name],
                        mfgDate = row[ProductsTable.mfgDate],
                        expDate = expDate,
                        quantityOnHand = row[ProductsTable.stock],
                        importPrice = row[ProductsTable.importPrice],
                        note = row[ProductsTable.inventoryNote],
                        createdAt = row[ProductsTable.createdAt],
                        updatedAt = row[ProductsTable.updatedAt],
                        daysUntilExpiry = expDate?.let { today.daysUntil(it).toLong() }
                    )
                }
        }
    }

    fun getExpiringStockAlerts(shopId: String, days: Int): List<ExpiringStockAlert> {
        return getExpiringStockAlerts(days)
    }

    fun getExpiringStockAlerts(days: Int): List<ExpiringStockAlert> {
        return transaction {
            val today = Clock.System.todayIn(TimeZone.UTC)
            val alertDate = today.plus(DatePeriod(days = days))

            ProductsTable
                .selectAll()
                .where {
                    ProductsTable.deletedAt.isNull() and
                        ProductsTable.expDate.isNotNull() and
                        (ProductsTable.stock greater 0) and
                        (ProductsTable.expDate greaterEq today) and
                        (ProductsTable.expDate lessEq alertDate)
                }
                .orderBy(ProductsTable.expDate to SortOrder.ASC)
                .map { row ->
                    val expDate = row[ProductsTable.expDate]!!
                    ExpiringStockAlert(
                        id = row[ProductsTable.id],
                        productId = row[ProductsTable.id],
                        productName = row[ProductsTable.name],
                        expDate = expDate,
                        quantityOnHand = row[ProductsTable.stock],
                        daysUntilExpiry = today.daysUntil(expDate).toLong()
                    )
                }
        }
    }

    fun reserveStockForProduct(
        productId: String,
        shopId: String,
        totalQuantityNeeded: Int
    ): List<Pair<String, Int>> {
        return reserveStockForProduct(productId, totalQuantityNeeded)
    }

    fun reserveStockForProduct(productId: String, totalQuantityNeeded: Int): List<Pair<String, Int>> {
        require(totalQuantityNeeded > 0) { "Quantity must be greater than 0" }

        return transaction {
            val today = Clock.System.todayIn(TimeZone.UTC)
            val minExpDate = today.plus(DatePeriod(days = 5))
            val product = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq productId }
                .singleOrNull()
                ?: throw IllegalStateException("Product not found")

            val expDate = product[ProductsTable.expDate]
            if (expDate != null && expDate <= minExpDate) {
                throw IllegalStateException("Product is expired or expiring too soon")
            }

            val available = product[ProductsTable.stock]
            if (available < totalQuantityNeeded) {
                throw IllegalStateException("Insufficient inventory. Need $totalQuantityNeeded, available $available")
            }

            listOf(productId to totalQuantityNeeded)
        }
    }

    fun commitStockReservations(allocations: List<Pair<String, Int>>) {
        transaction {
            allocations.forEach { (productId, quantity) ->
                val updatedRows = ProductsTable.update(
                    { (ProductsTable.id eq productId) and (ProductsTable.stock greaterEq quantity) }
                ) {
                    it[ProductsTable.stock] = ProductsTable.stock minus quantity
                    it[ProductsTable.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                }

                if (updatedRows == 0) {
                    throw IllegalStateException("Failed to allocate $quantity from product $productId")
                }
            }
        }
    }
}
