package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.CouponRedemptionsTable
import com.example.nhathuoc.database.tables.CouponsTable
import com.example.nhathuoc.database.tables.OrderItemsTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.PaymentsTable
import com.example.nhathuoc.database.tables.ProductsTable
import com.example.nhathuoc.database.tables.RewardRedemptionsTable
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

class OrderLifecycleService(
    private val notificationService: NotificationService = NotificationService()
) {
    fun restoreStockForOrderItem(orderItemId: String): Int {
        val item = OrderItemsTable.selectAll()
            .where { OrderItemsTable.id eq orderItemId }
            .singleOrNull() ?: return 0
        val productId = item[OrderItemsTable.productId] ?: return 0
        val quantity = item[OrderItemsTable.quantity]
        ProductsTable.update({ ProductsTable.id eq productId }) {
            with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                it.update(ProductsTable.stock, ProductsTable.stock + quantity)
            }
        }
        return quantity
    }

    fun restoreStockOnOnlineCancel(order: ResultRow): Int = restoreStockOnCancel(order)

    fun restoreStockOnCancel(order: ResultRow): Int {
        if (order[OrdersTable.status] == "CANCELLED") return 0

        val orderId = order[OrdersTable.id]
        var restoredQuantity = 0
        val items = OrderItemsTable
            .selectAll()
            .where { OrderItemsTable.orderId eq orderId }
            .toList()

        items.forEach { item ->
            val productId = item[OrderItemsTable.productId] ?: return@forEach
            val quantity = item[OrderItemsTable.quantity]
            ProductsTable.update({ ProductsTable.id eq productId }) {
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it.update(ProductsTable.stock, ProductsTable.stock + quantity)
                }
            }
            restoredQuantity += quantity
        }
        return restoredQuantity
    }

    fun cancelPendingPayments(orderId: String) {
        PaymentsTable.update({
            (PaymentsTable.orderId eq orderId) and (PaymentsTable.status eq "PENDING")
        }) {
            it[status] = "CANCELLED"
        }
    }

    fun revertCouponAndRewardUsage(orderId: String) {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val redemption = CouponRedemptionsTable
            .selectAll()
            .where {
                (CouponRedemptionsTable.orderId eq orderId) and
                    (CouponRedemptionsTable.status eq "APPLIED")
            }
            .singleOrNull()

        if (redemption != null) {
            CouponRedemptionsTable.update({ CouponRedemptionsTable.id eq redemption[CouponRedemptionsTable.id] }) {
                it[status] = "REVERTED"
                it[revertedAt] = now
            }

            CouponsTable
                .selectAll()
                .where { CouponsTable.id eq redemption[CouponRedemptionsTable.couponId] }
                .singleOrNull()
                ?.let { coupon ->
                    CouponsTable.update({ CouponsTable.id eq coupon[CouponsTable.id] }) {
                        it[usedCount] = maxOf(0, coupon[CouponsTable.usedCount] - 1)
                    }
                }
        }

        RewardRedemptionsTable.update({
            (RewardRedemptionsTable.redeemedOrderId eq orderId) and
                (RewardRedemptionsTable.status eq "USED")
        }) {
            it[status] = "APPROVED"
            it[redeemedOrderId] = null
            it[voucherUsedAt] = null
            it[updatedAt] = now
        }
    }

    fun notifyOrderStatusChanged(order: ResultRow, newStatus: String, paymentStatus: String? = null) {
        val userId = order[OrdersTable.userId] ?: return
        val orderCode = order[OrdersTable.orderCode]
        val normalizedStatus = newStatus.uppercase()
        val effectivePaymentStatus = paymentStatus ?: order[OrdersTable.paymentStatus]
        val title = when (normalizedStatus) {
            "PENDING" -> "Đơn $orderCode đã được tạo"
            "PROCESSING" -> if (effectivePaymentStatus == "COMPLETED") {
                "Đã thanh toán, đơn $orderCode đang xử lý"
            } else {
                "Đơn $orderCode đang xử lý"
            }
            "SHIPPING" -> "Đơn $orderCode đang được giao"
            "DELIVERED" -> "Đơn $orderCode đã giao thành công"
            "CANCELLED" -> "Đơn $orderCode đã hủy"
            "RETURNED" -> "Đơn $orderCode đã chuyển hoàn/đổi trả"
            else -> "Cập nhật đơn $orderCode"
        }
        val body = when (normalizedStatus) {
            "PENDING" -> "Đơn hàng đã được ghi nhận và đang chờ xác nhận."
            "PROCESSING" -> if (effectivePaymentStatus == "COMPLETED") {
                "Thanh toán đã hoàn tất. Medstore đang chuẩn bị đơn hàng."
            } else {
                "Medstore đang kiểm tra và chuẩn bị đơn hàng."
            }
            "SHIPPING" -> "Đơn hàng đã bàn giao cho bộ phận giao vận."
            "DELIVERED" -> "Bạn đã nhận hàng. Đánh giá sản phẩm 5 sao để nhận thêm 200 điểm thưởng."
            "CANCELLED" -> "Đơn hàng đã hủy. Tồn kho và voucher/điểm liên quan đã được hoàn lại nếu có."
            "RETURNED" -> "Yêu cầu đổi trả/hoàn hàng đang được xử lý."
            else -> "Trạng thái đơn hàng đã được cập nhật."
        }

        notificationService.createUserNotification(
            userId = userId,
            title = title,
            body = body,
            type = "ORDER_STATUS",
            refId = order[OrdersTable.id]
        )
    }
}
