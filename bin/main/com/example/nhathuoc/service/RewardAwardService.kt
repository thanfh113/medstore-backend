package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.RewardAccountsTable
import com.example.nhathuoc.database.tables.RewardTransactionsTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID

private const val SUCCESSFUL_POS_PAYMENT_REWARD_POINTS = 100

class RewardAwardService(
    private val notificationService: NotificationService = NotificationService()
) {
    fun awardSuccessfulPaymentPointsIfNeeded(order: ResultRow): Int {
        val orderId = order[OrdersTable.id]
        val orderUserId = order[OrdersTable.userId] ?: return 0
        val existingReward = RewardTransactionsTable
            .selectAll()
            .where {
                (RewardTransactionsTable.orderId eq orderId) and
                    (RewardTransactionsTable.type eq "EARN")
            }
            .firstOrNull()

        if (existingReward != null) {
            return 0
        }

        val pointsEarned = SUCCESSFUL_POS_PAYMENT_REWARD_POINTS
        if (pointsEarned <= 0) {
            return 0
        }

        val rewardAccount = RewardAccountsTable
            .selectAll()
            .where { RewardAccountsTable.userId eq orderUserId }
            .singleOrNull()

        if (rewardAccount == null) {
            RewardAccountsTable.insert {
                it[RewardAccountsTable.id] = UUID.randomUUID().toString()
                it[RewardAccountsTable.userId] = orderUserId
                it[RewardAccountsTable.totalPoints] = pointsEarned
                it[RewardAccountsTable.usedPoints] = 0
            }
        } else {
            RewardAccountsTable.update({ RewardAccountsTable.userId eq orderUserId }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[RewardAccountsTable.totalPoints] = RewardAccountsTable.totalPoints + pointsEarned
                }
            }
        }

        val orderCode = order[OrdersTable.orderCode]
        RewardTransactionsTable.insert {
            it[RewardTransactionsTable.id] = UUID.randomUUID().toString()
            it[RewardTransactionsTable.userId] = orderUserId
            it[RewardTransactionsTable.orderId] = orderId
            it[RewardTransactionsTable.refType] = "ORDER"
            it[RewardTransactionsTable.refId] = orderId
            it[RewardTransactionsTable.type] = "EARN"
            it[RewardTransactionsTable.points] = pointsEarned
            it[RewardTransactionsTable.description] = "Thanh toán thành công đơn hàng $orderCode"
        }

        if (order[OrdersTable.orderChannel] == "POS") {
            notificationService.createUserNotification(
                userId = orderUserId,
                title = "Thanh toán tại quầy thành công",
                body = "Đơn $orderCode đã hoàn tất. Bạn được cộng $pointsEarned điểm thưởng.",
                type = "REWARD",
                refId = orderId
            )
        }

        return pointsEarned
    }
}
