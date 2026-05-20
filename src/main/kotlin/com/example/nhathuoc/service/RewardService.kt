package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.CouponsTable
import com.example.nhathuoc.database.tables.RewardAccountsTable
import com.example.nhathuoc.database.tables.RewardProductsTable
import com.example.nhathuoc.database.tables.RewardRedemptionsTable
import com.example.nhathuoc.database.tables.RewardTransactionsTable
import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.routes.computeCouponDiscountFromRow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.deleteWhere
import java.math.BigDecimal
import java.util.UUID

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
    val refType: String? = null,
    val refId: String? = null,
    val type: String,
    val points: Int,
    val description: String?,
    val createdBy: String? = null,
    val metadata: String? = null,
    val createdAt: LocalDateTime
)

@Serializable
data class RewardProductDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String?,
    val pointCost: Int,
    val priceText: String?,
    val category: String? = null,
    val rewardType: String = "ITEM",
    val terms: String? = null,
    val stock: Int,
    val isActive: Boolean,
    val sortOrder: Int = 0,
    val updatedAt: LocalDateTime? = null,
    val usagePerUserLimit: Int? = null,
    val userRedemptionCount: Int = 0
)

@Serializable
data class AdminRewardProductDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val pointCost: Int,
    val stock: Int,
    val isActive: Boolean,
    val rewardType: String,
    val category: String? = null,
    val couponId: String? = null,
    val couponCode: String? = null,
    val couponName: String? = null,
    val terms: String? = null,
    val priceText: String? = null,
    val sortOrder: Int = 0,
    val usagePerUserLimit: Int? = null,
    val updatedAt: LocalDateTime? = null
)

@Serializable
data class RewardRedemptionDto(
    val id: String,
    val userId: String,
    val userName: String? = null,
    val rewardProductId: String,
    val productName: String,
    val productImageUrl: String? = null,
    val rewardType: String = "ITEM",
    val quantity: Int,
    val pointsUsed: Int,
    val status: String,
    val issuedVoucherCode: String? = null,
    val voucherIssuedAt: LocalDateTime? = null,
    val voucherUsedAt: LocalDateTime? = null,
    val redeemedOrderId: String? = null,
    val assignedTo: String? = null,
    val handledBy: String? = null,
    val handledAt: LocalDateTime? = null,
    val note: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime? = null
)

@Serializable
data class RewardVoucherDto(
    val redemptionId: String,
    val rewardProductId: String,
    val couponId: String,
    val code: String,
    val name: String,
    val description: String? = null,
    val terms: String? = null,
    val discountType: String,
    val discountValue: Double,
    val minOrderTotal: Double? = null,
    val maxDiscountAmount: Double? = null,
    val expiresAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
    val status: String
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

@Serializable
data class RedeemRewardResultDto(
    val redemptionId: String,
    val rewardType: String,
    val status: String,
    val issuedVoucherCode: String? = null
)

@Serializable
data class AdjustRewardPointsRequest(
    val userId: String,
    val points: Int,
    val description: String,
    val refType: String? = "ADMIN_ADJUSTMENT",
    val refId: String? = null,
    val metadata: String? = null
)

@Serializable
data class UpdateRewardRedemptionRequest(
    val status: String,
    val assignedTo: String? = null,
    val note: String? = null
)

@Serializable
data class AdminRewardProductUpsertRequest(
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val pointCost: Int,
    val stock: Int,
    val rewardType: String = "ITEM",
    val category: String? = null,
    val couponCode: String? = null,
    val terms: String? = null,
    val priceText: String? = null,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
    val usagePerUserLimit: Int? = null
)

class RewardService {
    private val validRedemptionStatuses = setOf("PROCESSING", "APPROVED", "SHIPPED", "DELIVERED", "USED", "CANCELLED")
    private val notificationService = NotificationService()

    fun getOrCreateRewardAccount(userId: String): RewardAccountDto = transaction {
        getOrCreateAccountInTx(userId)
    }

    fun earnPoints(
        userId: String,
        points: Int,
        orderId: String?,
        description: String = "Points earned from order",
        createdBy: String? = null,
        refType: String? = "ORDER",
        refId: String? = orderId,
        metadata: String? = null
    ): String = transaction {
        require(points > 0) { "Points must be greater than zero" }

        val account = getOrCreateAccountInTx(userId)
        RewardAccountsTable.update({ RewardAccountsTable.userId eq userId }) {
            it[totalPoints] = account.totalPoints + points
        }

        insertTransactionInTx(
            userId = userId,
            orderId = orderId,
            refType = refType,
            refId = refId,
            type = "EARN",
            points = points,
            description = description,
            createdBy = createdBy,
            metadata = metadata
        )
    }

    fun spendPoints(
        userId: String,
        points: Int,
        description: String = "Points used for reward redemption",
        refType: String? = null,
        refId: String? = null,
        createdBy: String? = null,
        metadata: String? = null
    ): String = transaction {
        require(points > 0) { "Points must be greater than zero" }

        val account = getOrCreateAccountInTx(userId)
        if (account.availablePoints < points) {
            throw IllegalArgumentException("Insufficient points. Available: ${account.availablePoints}, Required: $points")
        }

        RewardAccountsTable.update({ RewardAccountsTable.userId eq userId }) {
            it[usedPoints] = account.usedPoints + points
        }

        insertTransactionInTx(
            userId = userId,
            orderId = null,
            refType = refType,
            refId = refId,
            type = "REDEEM",
            points = -points,
            description = description,
            createdBy = createdBy,
            metadata = metadata
        )
    }

    fun getRewardTransactions(userId: String, limit: Int = 50): List<RewardTransactionDto> = transaction {
        RewardTransactionsTable
            .selectAll()
            .where { RewardTransactionsTable.userId eq userId }
            .orderBy(RewardTransactionsTable.createdAt to SortOrder.DESC)
            .limit(limit.coerceIn(1, 200))
            .map(::toTransactionDto)
    }

    fun getRewardProducts(filterByStock: Boolean = true): List<RewardProductDto> = transaction {
        var query = RewardProductsTable
            .leftJoin(CouponsTable, { RewardProductsTable.couponId }, { CouponsTable.id })
            .selectAll()
            .where { RewardProductsTable.isActive eq true }

        if (filterByStock) {
            query = query.andWhere { RewardProductsTable.stock greater 0 }
        }

        query
            .orderBy(RewardProductsTable.sortOrder to SortOrder.ASC, RewardProductsTable.pointCost to SortOrder.ASC)
            .map { toProductDto(it) }
    }

    fun getRewardProductsForUser(userId: String, filterByStock: Boolean = true): List<RewardProductDto> = transaction {
        var query = RewardProductsTable
            .leftJoin(CouponsTable, { RewardProductsTable.couponId }, { CouponsTable.id })
            .selectAll()
            .where { RewardProductsTable.isActive eq true }

        if (filterByStock) {
            query = query.andWhere { RewardProductsTable.stock greater 0 }
        }

        val rows = query
            .orderBy(RewardProductsTable.sortOrder to SortOrder.ASC, RewardProductsTable.pointCost to SortOrder.ASC)
            .toList()

        val productIds = rows.map { it[RewardProductsTable.id] }
        val redemptionCounts = if (productIds.isNotEmpty()) {
            RewardRedemptionsTable
                .selectAll()
                .where {
                    (RewardRedemptionsTable.userId eq userId) and
                    (RewardRedemptionsTable.rewardProductId inList productIds) and
                    not(RewardRedemptionsTable.status eq "CANCELLED")
                }
                .toList()
                .groupBy { it[RewardRedemptionsTable.rewardProductId] }
                .mapValues { it.value.size }
        } else emptyMap()

        rows.map { toProductDto(it, redemptionCounts[it[RewardProductsTable.id]] ?: 0) }
    }

    fun redeemReward(userId: String, request: RedeemRewardRequest): RedeemRewardResultDto = transaction {
        require(request.quantity > 0) { "Quantity must be greater than zero" }

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
        val rewardType = rewardProduct[RewardProductsTable.rewardType].uppercase()
        val couponId = rewardProduct[RewardProductsTable.couponId]
        if (rewardType == "VOUCHER" && couponId == null) {
            throw IllegalArgumentException("Reward voucher is not linked to a coupon template")
        }
        if (availableStock < request.quantity) {
            throw IllegalArgumentException("Insufficient stock. Available: $availableStock")
        }

        val totalPointsRequired = pointCost * request.quantity
        val account = getOrCreateAccountInTx(userId)
        if (account.availablePoints < totalPointsRequired) {
            throw IllegalArgumentException("Insufficient points. Available: ${account.availablePoints}, Required: $totalPointsRequired")
        }

        // Enforce per-user redemption limit (ITEM: from product table; VOUCHER: from linked coupon)
        val perUserLimit: Int? = if (rewardType == "ITEM") {
            rewardProduct[RewardProductsTable.usagePerUserLimit]
        } else if (rewardType == "VOUCHER" && couponId != null) {
            CouponsTable
                .select(CouponsTable.usagePerUserLimit)
                .where { CouponsTable.id eq couponId }
                .singleOrNull()
                ?.get(CouponsTable.usagePerUserLimit)
        } else null

        if (perUserLimit != null && perUserLimit > 0) {
            val existingCount = RewardRedemptionsTable
                .selectAll()
                .where {
                    (RewardRedemptionsTable.userId eq userId) and
                    (RewardRedemptionsTable.rewardProductId eq request.rewardProductId) and
                    not(RewardRedemptionsTable.status eq "CANCELLED")
                }
                .count()

            if (existingCount >= perUserLimit) {
                throw IllegalArgumentException(
                    "Bạn đã đổi quà này rồi. Mỗi người chỉ được đổi $perUserLimit lần"
                )
            }
        }

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val initialStatus = if (rewardType == "VOUCHER") "APPROVED" else "PROCESSING"
        val issuedVoucherCode = if (rewardType == "VOUCHER") {
            generateIssuedVoucherCodeInTx(couponId ?: error("Reward voucher is not linked to a coupon template"))
        } else {
            null
        }
        val redemptionId = UUID.randomUUID().toString()
        RewardRedemptionsTable.insert {
            it[id] = redemptionId
            it[RewardRedemptionsTable.userId] = userId
            it[rewardProductId] = request.rewardProductId
            it[quantity] = request.quantity
            it[pointsUsed] = totalPointsRequired
            it[status] = initialStatus
            if (rewardType == "VOUCHER") {
                it[RewardRedemptionsTable.issuedVoucherCode] = issuedVoucherCode
                it[RewardRedemptionsTable.voucherIssuedAt] = now
                it[handledAt] = now
                it[note] = "Voucher tự động phát mã sau khi đổi điểm"
            }
        }

        RewardAccountsTable.update({ RewardAccountsTable.userId eq userId }) {
            it[usedPoints] = account.usedPoints + totalPointsRequired
        }
        insertTransactionInTx(
            userId = userId,
            orderId = null,
            refType = "REDEMPTION",
            refId = redemptionId,
            type = "REDEEM",
            points = -totalPointsRequired,
            description = if (rewardType == "VOUCHER") {
                "Redeemed voucher $productName x${request.quantity}"
            } else {
                "Redeemed $productName x${request.quantity}"
            }
        )

        RewardProductsTable.update({ RewardProductsTable.id eq request.rewardProductId }) {
            it[stock] = availableStock - request.quantity
        }

        if (rewardType == "VOUCHER") {
            notificationService.createUserNotification(
                userId = userId,
                title = "Voucher đổi điểm đã sẵn sàng",
                body = if (!issuedVoucherCode.isNullOrBlank()) {
                    "Mã $issuedVoucherCode có thể dùng ngay khi thanh toán."
                } else {
                    "Voucher đổi điểm của bạn có thể dùng ngay khi thanh toán."
                },
                type = "REWARD",
                refId = redemptionId
            )
        }

        RedeemRewardResultDto(
            redemptionId = redemptionId,
            rewardType = rewardType,
            status = initialStatus,
            issuedVoucherCode = issuedVoucherCode
        )
    }

    fun getUserRedemptions(userId: String): List<RewardRedemptionDto> = transaction {
        (RewardRedemptionsTable innerJoin RewardProductsTable)
            .selectAll()
            .where { RewardRedemptionsTable.userId eq userId }
            .orderBy(RewardRedemptionsTable.createdAt to SortOrder.DESC)
            .map(::toRedemptionDto)
    }

    fun getRewardSummary(userId: String): RewardSummaryDto {
        return RewardSummaryDto(
            account = getOrCreateRewardAccount(userId),
            recentTransactions = getRewardTransactions(userId, 10),
            availableRewards = getRewardProducts().take(20)
        )
    }

    fun calculatePointsForOrder(orderTotal: BigDecimal): Int {
        return (orderTotal.toLong() / 1000).toInt()
    }

    fun awardOrderPoints(userId: String, orderId: String, orderTotal: BigDecimal): String {
        val points = calculatePointsForOrder(orderTotal)
        return earnPoints(
            userId = userId,
            points = points,
            orderId = orderId,
            description = "Points earned from order #$orderId",
            refType = "ORDER",
            refId = orderId
        )
    }

    fun getRewardProductsByPointRange(minPoints: Int?, maxPoints: Int?): List<RewardProductDto> = transaction {
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
            .orderBy(RewardProductsTable.sortOrder to SortOrder.ASC, RewardProductsTable.pointCost to SortOrder.ASC)
            .map(::toProductDto)
    }

    fun getAdminRewardProducts(rewardType: String? = null): List<AdminRewardProductDto> = transaction {
        var query = (RewardProductsTable leftJoin CouponsTable).selectAll()
        rewardType?.trim()?.uppercase()?.ifBlank { null }?.let { normalizedType ->
            query = query.andWhere { RewardProductsTable.rewardType eq normalizedType }
        }

        query
            .orderBy(RewardProductsTable.sortOrder to SortOrder.ASC, RewardProductsTable.pointCost to SortOrder.ASC)
            .map(::toAdminProductDto)
    }

    fun createRewardProduct(request: AdminRewardProductUpsertRequest): AdminRewardProductDto = transaction {
        val normalizedType = request.rewardType.trim().uppercase().ifBlank { "ITEM" }
        require(request.name.isNotBlank()) { "Reward product name is required" }
        require(request.pointCost > 0) { "Point cost must be greater than zero" }
        require(request.stock >= 0) { "Stock must not be negative" }
        require(normalizedType in setOf("ITEM", "VOUCHER")) { "rewardType must be ITEM or VOUCHER" }

        val coupon = if (normalizedType == "VOUCHER") {
            findCouponByCodeInTx(request.couponCode)
                ?: throw IllegalArgumentException("Reward voucher requires a valid coupon template")
        } else {
            null
        }

        val rewardProductId = UUID.randomUUID().toString()
        RewardProductsTable.insert {
            it[id] = rewardProductId
            it[name] = request.name.trim()
            it[description] = request.description?.trim()?.ifBlank { null }
            it[imageUrl] = request.imageUrl?.trim()?.ifBlank { null }
            it[pointCost] = request.pointCost
            it[priceText] = request.priceText?.trim()?.ifBlank { null }
            it[category] = request.category?.trim()?.ifBlank { if (normalizedType == "VOUCHER") "VOUCHER" else null }
            it[rewardType] = normalizedType
            it[couponId] = coupon?.get(CouponsTable.id)
            it[terms] = request.terms?.trim()?.ifBlank { null }
            it[stock] = request.stock
            it[isActive] = request.isActive
            it[sortOrder] = request.sortOrder
            it[usagePerUserLimit] = if (normalizedType == "ITEM") request.usagePerUserLimit else null
        }

        getAdminRewardProductInTx(rewardProductId)
            ?: throw IllegalStateException("Cannot load created reward product")
    }

    fun updateRewardProduct(rewardProductId: String, request: AdminRewardProductUpsertRequest): AdminRewardProductDto = transaction {
        val existing = RewardProductsTable.selectAll()
            .where { RewardProductsTable.id eq rewardProductId }
            .singleOrNull()
            ?: throw IllegalArgumentException("Reward product not found")

        val normalizedType = request.rewardType.trim().uppercase().ifBlank { existing[RewardProductsTable.rewardType] }
        require(request.name.isNotBlank()) { "Reward product name is required" }
        require(request.pointCost > 0) { "Point cost must be greater than zero" }
        require(request.stock >= 0) { "Stock must not be negative" }
        require(normalizedType in setOf("ITEM", "VOUCHER")) { "rewardType must be ITEM or VOUCHER" }

        val coupon = if (normalizedType == "VOUCHER") {
            findCouponByCodeInTx(request.couponCode)
                ?: throw IllegalArgumentException("Reward voucher requires a valid coupon template")
        } else {
            null
        }

        RewardProductsTable.update({ RewardProductsTable.id eq rewardProductId }) {
            it[name] = request.name.trim()
            it[description] = request.description?.trim()?.ifBlank { null }
            it[imageUrl] = request.imageUrl?.trim()?.ifBlank { null }
            it[pointCost] = request.pointCost
            it[priceText] = request.priceText?.trim()?.ifBlank { null }
            it[category] = request.category?.trim()?.ifBlank { if (normalizedType == "VOUCHER") "VOUCHER" else null }
            it[rewardType] = normalizedType
            it[couponId] = coupon?.get(CouponsTable.id)
            it[terms] = request.terms?.trim()?.ifBlank { null }
            it[stock] = request.stock
            it[isActive] = request.isActive
            it[sortOrder] = request.sortOrder
            it[usagePerUserLimit] = if (normalizedType == "ITEM") request.usagePerUserLimit else null
            it[updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        }

        getAdminRewardProductInTx(rewardProductId)
            ?: throw IllegalStateException("Cannot load updated reward product")
    }

    fun deleteRewardProduct(rewardProductId: String) = transaction {
        RewardProductsTable.selectAll()
            .where { RewardProductsTable.id eq rewardProductId }
            .singleOrNull()
            ?: throw IllegalArgumentException("Reward product not found")

        val hasActiveRedemptions = RewardRedemptionsTable.selectAll()
            .where {
                (RewardRedemptionsTable.rewardProductId eq rewardProductId) and
                    (RewardRedemptionsTable.status notInList listOf("CANCELLED", "DELIVERED"))
            }
            .any()
        if (hasActiveRedemptions) {
            throw IllegalStateException("Không thể xóa vì còn đơn đổi quà đang xử lý")
        }

        RewardProductsTable.deleteWhere { RewardProductsTable.id eq rewardProductId }
    }

    fun getAvailableVouchers(userId: String): List<RewardVoucherDto> = transaction {
        (RewardRedemptionsTable innerJoin RewardProductsTable innerJoin CouponsTable)
            .selectAll()
            .where {
                (RewardRedemptionsTable.userId eq userId) and
                    (RewardProductsTable.rewardType eq "VOUCHER") and
                    (RewardRedemptionsTable.status eq "APPROVED")
            }
            .orderBy(RewardRedemptionsTable.createdAt to SortOrder.DESC)
            .mapNotNull { row ->
                val code = row[RewardRedemptionsTable.issuedVoucherCode]
                if (code.isNullOrBlank() || row[RewardRedemptionsTable.redeemedOrderId] != null) {
                    null
                } else {
                    toRewardVoucherDto(row)
                }
            }
    }

    fun listRedemptions(status: String? = null, limit: Int = 100): List<RewardRedemptionDto> = transaction {
        var query = RewardRedemptionsTable
            .join(
                RewardProductsTable,
                JoinType.INNER,
                additionalConstraint = { RewardRedemptionsTable.rewardProductId eq RewardProductsTable.id }
            )
            .join(
                UsersTable,
                JoinType.INNER,
                additionalConstraint = { RewardRedemptionsTable.userId eq UsersTable.id }
            )
            .selectAll()
        status?.trim()?.uppercase()?.ifBlank { null }?.let { normalizedStatus ->
            require(normalizedStatus in validRedemptionStatuses) {
                "Invalid status. Must be one of: ${validRedemptionStatuses.joinToString(", ")}"
            }
            query = query.andWhere { RewardRedemptionsTable.status eq normalizedStatus }
        }

        query
            .orderBy(RewardRedemptionsTable.createdAt to SortOrder.DESC)
            .limit(limit.coerceIn(1, 500))
            .map { row -> toRedemptionDto(row, row[UsersTable.fullName]) }
    }

    fun updateRedemptionStatus(
        redemptionId: String,
        status: String,
        actorUserId: String? = null,
        assignedTo: String? = null,
        note: String? = null
    ): RewardRedemptionDto = transaction {
        val normalizedStatus = status.trim().uppercase()
        require(normalizedStatus in validRedemptionStatuses) {
            "Invalid status. Must be one of: ${validRedemptionStatuses.joinToString(", ")}"
        }

        val existing = RewardRedemptionsTable.selectAll()
            .where { RewardRedemptionsTable.id eq redemptionId }
            .singleOrNull()
            ?: throw IllegalArgumentException("Reward redemption not found")

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val currentStatus = existing[RewardRedemptionsTable.status]
        val pointsUsed = existing[RewardRedemptionsTable.pointsUsed]
        val userId = existing[RewardRedemptionsTable.userId]
        val rewardProductId = existing[RewardRedemptionsTable.rewardProductId]
        val quantity = existing[RewardRedemptionsTable.quantity]
        val rewardProduct = RewardProductsTable.selectAll()
            .where { RewardProductsTable.id eq rewardProductId }
            .singleOrNull()
            ?: throw IllegalArgumentException("Reward product not found")
        val rewardType = rewardProduct[RewardProductsTable.rewardType].uppercase()
        val couponId = rewardProduct[RewardProductsTable.couponId]
        val issuedVoucherCode = existing[RewardRedemptionsTable.issuedVoucherCode]

        RewardRedemptionsTable.update({ RewardRedemptionsTable.id eq redemptionId }) {
            it[RewardRedemptionsTable.status] = normalizedStatus
            if (normalizedStatus == "APPROVED" && rewardType == "VOUCHER") {
                require(!couponId.isNullOrBlank()) { "Voucher reward must be linked to a coupon template" }
                it[RewardRedemptionsTable.issuedVoucherCode] = issuedVoucherCode
                    ?: generateIssuedVoucherCodeInTx(couponId)
                it[voucherIssuedAt] = existing[RewardRedemptionsTable.voucherIssuedAt] ?: now
            }
            if (normalizedStatus == "USED") {
                it[voucherUsedAt] = now
            }
            if (assignedTo != null) it[RewardRedemptionsTable.assignedTo] = assignedTo.ifBlank { null }
            if (actorUserId != null) it[handledBy] = actorUserId
            it[handledAt] = now
            if (note != null) it[RewardRedemptionsTable.note] = note.ifBlank { null }
            it[updatedAt] = now
        }

        if (normalizedStatus == "CANCELLED" && currentStatus != "CANCELLED") {
            refundRedemptionInTx(userId, redemptionId, rewardProductId, quantity, pointsUsed, actorUserId)
        }

        val updatedRedemption = getRedemptionInTx(redemptionId)
            ?: throw IllegalStateException("Cannot load updated redemption")
        if (normalizedStatus != currentStatus) {
            when {
                normalizedStatus == "APPROVED" && rewardType == "VOUCHER" -> {
                    val code = updatedRedemption.issuedVoucherCode.orEmpty()
                    notificationService.createUserNotification(
                        userId = userId,
                        title = "Voucher đổi điểm đã được duyệt",
                        body = if (code.isNotBlank()) {
                            "Mã $code đã sẵn sàng để dùng khi thanh toán."
                        } else {
                            "Voucher đổi điểm của bạn đã được duyệt."
                        },
                        type = "REWARD",
                        refId = redemptionId
                    )
                }
                normalizedStatus == "USED" && rewardType == "VOUCHER" -> {
                    val code = updatedRedemption.issuedVoucherCode.orEmpty()
                    notificationService.createUserNotification(
                        userId = userId,
                        title = "Voucher đã được sử dụng",
                        body = if (code.isNotBlank()) {
                            "Voucher $code đã được áp dụng cho đơn hàng."
                        } else {
                            "Voucher đổi điểm đã được áp dụng cho đơn hàng."
                        },
                        type = "REWARD",
                        refId = redemptionId
                    )
                }
            }
        }
        updatedRedemption
    }

    fun adjustUserPoints(actorUserId: String, request: AdjustRewardPointsRequest): RewardTransactionDto = transaction {
        require(request.userId.isNotBlank()) { "Vui lòng nhập SĐT hoặc User ID" }
        require(request.points != 0) { "Số điểm điều chỉnh không được bằng 0" }
        require(request.description.isNotBlank()) { "Vui lòng nhập lý do điều chỉnh" }

        val resolvedUserId = if (request.userId.matches(Regex("^(0|\\+84)\\d{8,10}$"))) {
            UsersTable.selectAll()
                .where { UsersTable.phone eq request.userId }
                .singleOrNull()
                ?.get(UsersTable.id)
                ?: throw IllegalArgumentException("Không tìm thấy tài khoản với SĐT ${request.userId}")
        } else {
            UsersTable.selectAll()
                .where { UsersTable.id eq request.userId }
                .singleOrNull()
                ?.get(UsersTable.id)
                ?: throw IllegalArgumentException("Không tìm thấy tài khoản với ID ${request.userId}")
        }

        val account = getOrCreateAccountInTx(resolvedUserId)
        val nextTotal = account.totalPoints + request.points
        require(nextTotal >= account.usedPoints) {
            "Số điểm sau điều chỉnh (${nextTotal}) thấp hơn điểm đã dùng (${account.usedPoints})"
        }

        RewardAccountsTable.update({ RewardAccountsTable.userId eq resolvedUserId }) {
            it[totalPoints] = nextTotal
        }

        val transactionId = insertTransactionInTx(
            userId = resolvedUserId,
            orderId = null,
            refType = request.refType?.ifBlank { "ADMIN_ADJUSTMENT" },
            refId = request.refId?.ifBlank { null },
            type = "ADJUST",
            points = request.points,
            description = request.description.trim(),
            createdBy = actorUserId,
            metadata = request.metadata
        )

        val absPoints = kotlin.math.abs(request.points)
        notificationService.createUserNotification(
            userId = resolvedUserId,
            title = if (request.points > 0) "Bạn được cộng $absPoints điểm thưởng"
                    else "Điểm thưởng của bạn đã bị trừ $absPoints điểm",
            body = request.description.trim(),
            type = "REWARD",
            refId = transactionId
        )

        RewardTransactionsTable.selectAll()
            .where { RewardTransactionsTable.id eq transactionId }
            .single()
            .let(::toTransactionDto)
    }

    private fun refundRedemptionInTx(
        userId: String,
        redemptionId: String,
        rewardProductId: String,
        quantity: Int,
        pointsUsed: Int,
        actorUserId: String?
    ) {
        val account = getOrCreateAccountInTx(userId)
        RewardAccountsTable.update({ RewardAccountsTable.userId eq userId }) {
            it[usedPoints] = (account.usedPoints - pointsUsed).coerceAtLeast(0)
        }

        val product = RewardProductsTable.selectAll()
            .where { RewardProductsTable.id eq rewardProductId }
            .singleOrNull()
        if (product != null) {
            RewardProductsTable.update({ RewardProductsTable.id eq rewardProductId }) {
                it[stock] = product[RewardProductsTable.stock] + quantity
            }
        }

        insertTransactionInTx(
            userId = userId,
            orderId = null,
            refType = "REDEMPTION",
            refId = redemptionId,
            type = "ADJUST",
            points = pointsUsed,
            description = "Refund points for cancelled reward redemption",
            createdBy = actorUserId
        )
    }

    private fun getOrCreateAccountInTx(userId: String): RewardAccountDto {
        val existingAccount = RewardAccountsTable
            .selectAll()
            .where { RewardAccountsTable.userId eq userId }
            .singleOrNull()

        if (existingAccount != null) {
            return RewardAccountDto(
                id = existingAccount[RewardAccountsTable.id],
                userId = existingAccount[RewardAccountsTable.userId],
                totalPoints = existingAccount[RewardAccountsTable.totalPoints],
                usedPoints = existingAccount[RewardAccountsTable.usedPoints],
                availablePoints = existingAccount[RewardAccountsTable.totalPoints] - existingAccount[RewardAccountsTable.usedPoints]
            )
        }

        val accountId = UUID.randomUUID().toString()
        RewardAccountsTable.insert {
            it[id] = accountId
            it[RewardAccountsTable.userId] = userId
            it[totalPoints] = 0
            it[usedPoints] = 0
        }
        return RewardAccountDto(
            id = accountId,
            userId = userId,
            totalPoints = 0,
            usedPoints = 0,
            availablePoints = 0
        )
    }

    private fun insertTransactionInTx(
        userId: String,
        orderId: String?,
        refType: String?,
        refId: String?,
        type: String,
        points: Int,
        description: String?,
        createdBy: String? = null,
        metadata: String? = null
    ): String {
        val transactionId = UUID.randomUUID().toString()
        RewardTransactionsTable.insert {
            it[id] = transactionId
            it[RewardTransactionsTable.userId] = userId
            it[RewardTransactionsTable.orderId] = orderId
            it[RewardTransactionsTable.refType] = refType
            it[RewardTransactionsTable.refId] = refId
            it[RewardTransactionsTable.type] = type
            it[RewardTransactionsTable.points] = points
            it[RewardTransactionsTable.description] = description
            it[RewardTransactionsTable.createdBy] = createdBy
            it[RewardTransactionsTable.metadata] = metadata
        }
        return transactionId
    }

    private fun getRedemptionInTx(redemptionId: String): RewardRedemptionDto? {
        return (RewardRedemptionsTable innerJoin RewardProductsTable)
            .selectAll()
            .where { RewardRedemptionsTable.id eq redemptionId }
            .singleOrNull()
            ?.let(::toRedemptionDto)
    }

    private fun toTransactionDto(row: ResultRow): RewardTransactionDto {
        return RewardTransactionDto(
            id = row[RewardTransactionsTable.id],
            userId = row[RewardTransactionsTable.userId],
            orderId = row[RewardTransactionsTable.orderId],
            refType = row[RewardTransactionsTable.refType],
            refId = row[RewardTransactionsTable.refId],
            type = row[RewardTransactionsTable.type],
            points = row[RewardTransactionsTable.points],
            description = row[RewardTransactionsTable.description],
            createdBy = row[RewardTransactionsTable.createdBy],
            metadata = row[RewardTransactionsTable.metadata],
            createdAt = row[RewardTransactionsTable.createdAt]
        )
    }

    private fun toProductDto(row: ResultRow, userRedemptionCount: Int = 0): RewardProductDto {
        return RewardProductDto(
            id = row[RewardProductsTable.id],
            name = row[RewardProductsTable.name],
            description = row[RewardProductsTable.description],
            imageUrl = row[RewardProductsTable.imageUrl],
            pointCost = row[RewardProductsTable.pointCost],
            priceText = row[RewardProductsTable.priceText],
            category = row[RewardProductsTable.category],
            rewardType = row[RewardProductsTable.rewardType],
            terms = row[RewardProductsTable.terms],
            stock = row[RewardProductsTable.stock],
            isActive = row[RewardProductsTable.isActive],
            sortOrder = row[RewardProductsTable.sortOrder],
            updatedAt = row[RewardProductsTable.updatedAt],
            usagePerUserLimit = row[RewardProductsTable.usagePerUserLimit]
                ?: row.getOrNull(CouponsTable.usagePerUserLimit),
            userRedemptionCount = userRedemptionCount
        )
    }

    private fun toAdminProductDto(row: ResultRow): AdminRewardProductDto {
        return AdminRewardProductDto(
            id = row[RewardProductsTable.id],
            name = row[RewardProductsTable.name],
            description = row[RewardProductsTable.description],
            imageUrl = row[RewardProductsTable.imageUrl],
            pointCost = row[RewardProductsTable.pointCost],
            stock = row[RewardProductsTable.stock],
            isActive = row[RewardProductsTable.isActive],
            rewardType = row[RewardProductsTable.rewardType],
            category = row[RewardProductsTable.category],
            couponId = row[RewardProductsTable.couponId],
            couponCode = row.getOrNull(CouponsTable.code),
            couponName = row.getOrNull(CouponsTable.name),
            terms = row[RewardProductsTable.terms],
            priceText = row[RewardProductsTable.priceText],
            sortOrder = row[RewardProductsTable.sortOrder],
            usagePerUserLimit = row[RewardProductsTable.usagePerUserLimit],
            updatedAt = row[RewardProductsTable.updatedAt]
        )
    }

    private fun toRedemptionDto(row: ResultRow, userName: String? = null): RewardRedemptionDto {
        return RewardRedemptionDto(
            id = row[RewardRedemptionsTable.id],
            userId = row[RewardRedemptionsTable.userId],
            userName = userName,
            rewardProductId = row[RewardRedemptionsTable.rewardProductId],
            productName = row[RewardProductsTable.name],
            productImageUrl = row[RewardProductsTable.imageUrl],
            rewardType = row[RewardProductsTable.rewardType],
            quantity = row[RewardRedemptionsTable.quantity],
            pointsUsed = row[RewardRedemptionsTable.pointsUsed],
            status = row[RewardRedemptionsTable.status],
            issuedVoucherCode = row[RewardRedemptionsTable.issuedVoucherCode],
            voucherIssuedAt = row[RewardRedemptionsTable.voucherIssuedAt],
            voucherUsedAt = row[RewardRedemptionsTable.voucherUsedAt],
            redeemedOrderId = row[RewardRedemptionsTable.redeemedOrderId],
            assignedTo = row[RewardRedemptionsTable.assignedTo],
            handledBy = row[RewardRedemptionsTable.handledBy],
            handledAt = row[RewardRedemptionsTable.handledAt],
            note = row[RewardRedemptionsTable.note],
            createdAt = row[RewardRedemptionsTable.createdAt],
            updatedAt = row[RewardRedemptionsTable.updatedAt]
        )
    }

    private fun toRewardVoucherDto(row: ResultRow): RewardVoucherDto {
        return RewardVoucherDto(
            redemptionId = row[RewardRedemptionsTable.id],
            rewardProductId = row[RewardRedemptionsTable.rewardProductId],
            couponId = row[CouponsTable.id],
            code = row[RewardRedemptionsTable.issuedVoucherCode].orEmpty(),
            name = row[RewardProductsTable.name],
            description = row[RewardProductsTable.description],
            terms = row[RewardProductsTable.terms],
            discountType = row[CouponsTable.discountType],
            discountValue = row[CouponsTable.discountValue].toDouble(),
            minOrderTotal = row[CouponsTable.minOrderTotal]?.toDouble(),
            maxDiscountAmount = row[CouponsTable.maxDiscountAmount]?.toDouble(),
            expiresAt = row[CouponsTable.endsAt],
            createdAt = row[RewardRedemptionsTable.createdAt],
            status = row[RewardRedemptionsTable.status]
        )
    }

    private fun getAdminRewardProductInTx(rewardProductId: String): AdminRewardProductDto? {
        return (RewardProductsTable leftJoin CouponsTable)
            .selectAll()
            .where { RewardProductsTable.id eq rewardProductId }
            .singleOrNull()
            ?.let(::toAdminProductDto)
    }

    private fun findCouponByCodeInTx(couponCode: String?): ResultRow? {
        val normalizedCode = couponCode?.trim()?.uppercase()?.ifBlank { null } ?: return null
        return CouponsTable
            .selectAll()
            .where { CouponsTable.code eq normalizedCode }
            .singleOrNull()
            ?.takeIf { it[CouponsTable.isRewardVoucherTemplate] }
    }

    private fun generateIssuedVoucherCodeInTx(couponId: String): String {
        val couponCode = CouponsTable
            .selectAll()
            .where { CouponsTable.id eq couponId }
            .single()[CouponsTable.code]

        repeat(10) {
            val generated = "$couponCode-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"
            val exists = RewardRedemptionsTable
                .selectAll()
                .where { RewardRedemptionsTable.issuedVoucherCode eq generated }
                .singleOrNull()
            if (exists == null) {
                return generated
            }
        }

        throw IllegalStateException("Cannot generate unique issued voucher code")
    }
}
