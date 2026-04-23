package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.util.*

// ─────────────────────────────────────────────────────────────
// DTOs
// ─────────────────────────────────────────────────────────────

@Serializable
data class RewardAccountDto(
    val id: String,
    val userId: String,
    val totalPoints: Int,
    val usedPoints: Int,
    val availablePoints: Int
) {
    val availablePointsCalculated: Int get() = totalPoints - usedPoints
}

@Serializable
data class RewardTransactionDto(
    val id: String,
    val userId: String,
    val orderId: String?,
    val type: String, // EARN | REDEEM | EXPIRE | ADJUST
    val points: Int,
    val description: String?,
    val createdAt: LocalDateTime
)

@Serializable
data class RewardProductDto(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val pointCost: Int,
    val priceText: String?,
    val stock: Int,
    val isActive: Boolean
)

@Serializable
data class RewardRedemptionDto(
    val id: String,
    val userId: String,
    val rewardProductId: String,
    val productName: String,
    val quantity: Int,
    val pointsUsed: Int,
    val status: String, // PROCESSING | APPROVED | SHIPPED | DELIVERED | CANCELLED
    val createdAt: LocalDateTime
)

@Serializable
data class RewardSummaryDto(
    val account: RewardAccountDto,
    val recentTransactions: List<RewardTransactionDto>,
    val availableRewards: List<RewardProductDto>
)

@Serializable
data class RedeemRewardRequest(
    val rewardProductId: String,
    val quantity: Int = 1
)

// ─────────────────────────────────────────────────────────────
// SERVICE
// ─────────────────────────────────────────────────────────────

class RewardService {

    /**
     * Get or create reward account for user
     */
    fun getOrCreateRewardAccount(userId: String): RewardAccountDto {
        return transaction {
            val existingAccount = RewardAccountsTable
                .selectAll()
                .where { RewardAccountsTable.userId eq userId }
                .singleOrNull()

            if (existingAccount != null) {
                RewardAccountDto(
                    id = existingAccount[RewardAccountsTable.id],
                    userId = existingAccount[RewardAccountsTable.userId],
                    totalPoints = existingAccount[RewardAccountsTable.totalPoints],
                    usedPoints = existingAccount[RewardAccountsTable.usedPoints],
                    availablePoints = existingAccount[RewardAccountsTable.totalPoints] - existingAccount[RewardAccountsTable.usedPoints]
                )
            } else {
                // Create new reward account
                val accountId = UUID.randomUUID().toString()
                RewardAccountsTable.insert {
                    it[RewardAccountsTable.id] = accountId
                    it[RewardAccountsTable.userId] = userId
                    it[RewardAccountsTable.totalPoints] = 0
                    it[RewardAccountsTable.usedPoints] = 0
                }

                RewardAccountDto(
                    id = accountId,
                    userId = userId,
                    totalPoints = 0,
                    usedPoints = 0,
                    availablePoints = 0
                )
            }
        }
    }

    /**
     * Add points to user account (for completed orders)
     */
    fun earnPoints(userId: String, points: Int, orderId: String?, description: String = "Points earned from order"): String {
        return transaction {
            val account = getOrCreateRewardAccount(userId)

            // Update account
            RewardAccountsTable.update({ RewardAccountsTable.userId eq userId }) {
                it[RewardAccountsTable.totalPoints] = account.totalPoints + points
            }

            // Create transaction record
            val transactionId = UUID.randomUUID().toString()
            RewardTransactionsTable.insert {
                it[RewardTransactionsTable.id] = transactionId
                it[RewardTransactionsTable.userId] = userId
                it[RewardTransactionsTable.orderId] = orderId
                it[RewardTransactionsTable.type] = "EARN"
                it[RewardTransactionsTable.points] = points
                it[RewardTransactionsTable.description] = description
            }

            transactionId
        }
    }

    /**
     * Spend points for reward redemption
     */
    fun spendPoints(userId: String, points: Int, description: String = "Points used for reward redemption"): String {
        return transaction {
            val account = getOrCreateRewardAccount(userId)

            if (account.availablePoints < points) {
                throw IllegalArgumentException("Insufficient points. Available: ${account.availablePoints}, Required: $points")
            }

            // Update account
            RewardAccountsTable.update({ RewardAccountsTable.userId eq userId }) {
                it[RewardAccountsTable.usedPoints] = account.usedPoints + points
            }

            // Create transaction record
            val transactionId = UUID.randomUUID().toString()
            RewardTransactionsTable.insert {
                it[RewardTransactionsTable.id] = transactionId
                it[RewardTransactionsTable.userId] = userId
                it[RewardTransactionsTable.orderId] = null
                it[RewardTransactionsTable.type] = "REDEEM"
                it[RewardTransactionsTable.points] = -points // Negative for spending
                it[RewardTransactionsTable.description] = description
            }

            transactionId
        }
    }

    /**
     * Get user's reward transactions
     */
    fun getRewardTransactions(userId: String, limit: Int = 50): List<RewardTransactionDto> {
        return transaction {
            RewardTransactionsTable
                .selectAll()
                .where { RewardTransactionsTable.userId eq userId }
                .orderBy(RewardTransactionsTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    RewardTransactionDto(
                        id = row[RewardTransactionsTable.id],
                        userId = row[RewardTransactionsTable.userId],
                        orderId = row[RewardTransactionsTable.orderId],
                        type = row[RewardTransactionsTable.type],
                        points = row[RewardTransactionsTable.points],
                        description = row[RewardTransactionsTable.description],
                        createdAt = row[RewardTransactionsTable.createdAt]
                    )
                }
        }
    }

    /**
     * Get all available reward products
     */
    fun getRewardProducts(filterByStock: Boolean = true): List<RewardProductDto> {
        return transaction {
            var query = RewardProductsTable
                .selectAll()
                .where { RewardProductsTable.isActive eq true }

            if (filterByStock) {
                query = query.andWhere { RewardProductsTable.stock greater 0 }
            }

            query
                .orderBy(RewardProductsTable.pointCost to SortOrder.ASC)
                .map { row ->
                    RewardProductDto(
                        id = row[RewardProductsTable.id],
                        name = row[RewardProductsTable.name],
                        imageUrl = row[RewardProductsTable.imageUrl],
                        pointCost = row[RewardProductsTable.pointCost],
                        priceText = row[RewardProductsTable.priceText],
                        stock = row[RewardProductsTable.stock],
                        isActive = row[RewardProductsTable.isActive]
                    )
                }
        }
    }

    /**
     * Redeem reward product
     */
    fun redeemReward(userId: String, request: RedeemRewardRequest): String {
        return transaction {
            // Check reward product exists and is available
            val rewardProduct = RewardProductsTable
                .selectAll()
                .where {
                    (RewardProductsTable.id eq request.rewardProductId) and
                    (RewardProductsTable.isActive eq true)
                }
                .singleOrNull()
                ?: throw IllegalArgumentException("Reward product not found or inactive")

            val pointCost = rewardProduct[RewardProductsTable.pointCost]
            val availableStock = rewardProduct[RewardProductsTable.stock]
            val productName = rewardProduct[RewardProductsTable.name]

            // Check stock
            if (availableStock < request.quantity) {
                throw IllegalArgumentException("Insufficient stock. Available: $availableStock")
            }

            val totalPointsRequired = pointCost * request.quantity

            // Check user has enough points
            val account = getOrCreateRewardAccount(userId)
            if (account.availablePoints < totalPointsRequired) {
                throw IllegalArgumentException("Insufficient points. Available: ${account.availablePoints}, Required: $totalPointsRequired")
            }

            // Create redemption record
            val redemptionId = UUID.randomUUID().toString()
            RewardRedemptionsTable.insert {
                it[RewardRedemptionsTable.id] = redemptionId
                it[RewardRedemptionsTable.userId] = userId
                it[RewardRedemptionsTable.rewardProductId] = request.rewardProductId
                it[RewardRedemptionsTable.quantity] = request.quantity
                it[RewardRedemptionsTable.pointsUsed] = totalPointsRequired
                it[RewardRedemptionsTable.status] = "PROCESSING"
            }

            // Spend points
            spendPoints(userId, totalPointsRequired, "Redeemed $productName x${request.quantity}")

            // Update product stock
            RewardProductsTable.update({ RewardProductsTable.id eq request.rewardProductId }) {
                it[RewardProductsTable.stock] = availableStock - request.quantity
            }

            redemptionId
        }
    }

    /**
     * Get user's reward redemptions
     */
    fun getUserRedemptions(userId: String): List<RewardRedemptionDto> {
        return transaction {
            (RewardRedemptionsTable innerJoin RewardProductsTable)
                .selectAll()
                .where { RewardRedemptionsTable.userId eq userId }
                .orderBy(RewardRedemptionsTable.createdAt to SortOrder.DESC)
                .map { row ->
                    RewardRedemptionDto(
                        id = row[RewardRedemptionsTable.id],
                        userId = row[RewardRedemptionsTable.userId],
                        rewardProductId = row[RewardRedemptionsTable.rewardProductId],
                        productName = row[RewardProductsTable.name],
                        quantity = row[RewardRedemptionsTable.quantity],
                        pointsUsed = row[RewardRedemptionsTable.pointsUsed],
                        status = row[RewardRedemptionsTable.status],
                        createdAt = row[RewardRedemptionsTable.createdAt]
                    )
                }
        }
    }

    /**
     * Get complete reward summary for user
     */
    fun getRewardSummary(userId: String): RewardSummaryDto {
        return RewardSummaryDto(
            account = getOrCreateRewardAccount(userId),
            recentTransactions = getRewardTransactions(userId, 10),
            availableRewards = getRewardProducts().take(20) // Show top 20 rewards
        )
    }

    /**
     * Calculate points earning for order amount (1 point per 1000 VND)
     */
    fun calculatePointsForOrder(orderTotal: BigDecimal): Int {
        return (orderTotal.toLong() / 1000).toInt()
    }

    /**
     * Award points for completed order
     */
    fun awardOrderPoints(userId: String, orderId: String, orderTotal: BigDecimal): String {
        val points = calculatePointsForOrder(orderTotal)
        return earnPoints(
            userId = userId,
            points = points,
            orderId = orderId,
            description = "Points earned from order #$orderId (${points} points = ${orderTotal.toLong().toString().take(3)}k VND)"
        )
    }

    /**
     * Get reward products filtered by point range
     */
    fun getRewardProductsByPointRange(minPoints: Int?, maxPoints: Int?): List<RewardProductDto> {
        return transaction {
            var query = RewardProductsTable
                .selectAll()
                .where { (RewardProductsTable.isActive eq true) and (RewardProductsTable.stock greater 0) }

            if (minPoints != null) {
                query = query.andWhere { RewardProductsTable.pointCost greaterEq minPoints }
            }

            if (maxPoints != null) {
                query = query.andWhere { RewardProductsTable.pointCost lessEq maxPoints }
            }

            query
                .orderBy(RewardProductsTable.pointCost to SortOrder.ASC)
                .map { row ->
                    RewardProductDto(
                        id = row[RewardProductsTable.id],
                        name = row[RewardProductsTable.name],
                        imageUrl = row[RewardProductsTable.imageUrl],
                        pointCost = row[RewardProductsTable.pointCost],
                        priceText = row[RewardProductsTable.priceText],
                        stock = row[RewardProductsTable.stock],
                        isActive = row[RewardProductsTable.isActive]
                    )
                }
        }
    }

    /**
     * Admin: Update redemption status
     */
    fun updateRedemptionStatus(redemptionId: String, status: String): Boolean {
        return transaction {
            val validStatuses = listOf("PROCESSING", "APPROVED", "SHIPPED", "DELIVERED", "CANCELLED")
            if (status !in validStatuses) {
                throw IllegalArgumentException("Invalid status. Must be one of: ${validStatuses.joinToString(", ")}")
            }

            val updatedCount = RewardRedemptionsTable.update({ RewardRedemptionsTable.id eq redemptionId }) {
                it[RewardRedemptionsTable.status] = status
            }

            updatedCount > 0
        }
    }
}
