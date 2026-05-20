package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.OrderComplaintsTable
import com.example.nhathuoc.database.tables.OrderItemsTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.PaymentsTable
import com.example.nhathuoc.database.tables.ProductsTable
import com.example.nhathuoc.database.tables.UsersTable
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.util.UUID

@Serializable
data class ComplaintAttachmentInput(
    val fileUrl: String,
    val fileType: String = "IMAGE",
    val publicId: String? = null
)

@Serializable
data class CreateComplaintRequest(
    val orderId: String,
    val orderItemId: String? = null,
    val productId: String? = null,
    val type: String,
    val title: String,
    val description: String,
    val attachments: List<ComplaintAttachmentInput> = emptyList()
)

@Serializable
data class ComplaintMessageRequest(
    val message: String,
    val isInternal: Boolean = false
)

@Serializable
data class AddComplaintAttachmentsRequest(
    val attachments: List<ComplaintAttachmentInput>
)

@Serializable
data class UpdateComplaintRequest(
    val status: String? = null,
    val priority: String? = null,
    val resolution: String? = null,
    val refundAmount: Double? = null,
    val refundStatus: String? = null,
    val refundMethod: String? = null,
    val refundTransactionId: String? = null,
    val handledBy: String? = null,
    val restoreStock: Boolean = false
)

@Serializable
data class ComplaintAttachmentDto(
    val id: String,
    val fileUrl: String,
    val fileType: String,
    val publicId: String? = null,
    val createdAt: String
)

@Serializable
data class ComplaintMessageDto(
    val id: String,
    val senderUserId: String,
    val senderName: String? = null,
    val senderRole: String,
    val message: String,
    val isInternal: Boolean,
    val createdAt: String
)

@Serializable
data class ComplaintEventDto(
    val id: String,
    val actorUserId: String? = null,
    val actorName: String? = null,
    val actorRole: String? = null,
    val eventType: String,
    val title: String,
    val description: String? = null,
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val fromPriority: String? = null,
    val toPriority: String? = null,
    val dueAt: String? = null,
    val createdAt: String
)

@Serializable
data class ComplaintDto(
    val id: String,
    val complaintCode: String,
    val userId: String,
    val userName: String? = null,
    val orderId: String,
    val orderItemId: String? = null,
    val productId: String? = null,
    val productName: String? = null,
    val type: String,
    val title: String,
    val description: String,
    val status: String,
    val priority: String,
    val resolution: String? = null,
    val refundAmount: Double? = null,
    val refundStatus: String = "NONE",
    val refundMethod: String? = null,
    val refundTransactionId: String? = null,
    val refundedBy: String? = null,
    val refundedAt: String? = null,
    val handledBy: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val resolvedAt: String? = null,
    val closedAt: String? = null,
    val orderTotal: Double? = null,
    val itemTotal: Double? = null,
    val attachments: List<ComplaintAttachmentDto> = emptyList(),
    val messages: List<ComplaintMessageDto> = emptyList(),
    val events: List<ComplaintEventDto> = emptyList()
)

class ComplaintService {
    private val notificationService = NotificationService()
    private val paymentService = PaymentService()
    private val complaintJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val allowedTypes = setOf(
        "MISSING_ITEM", "WRONG_ITEM", "DAMAGED", "COUNTERFEIT",
        "EXPIRED", "PAYMENT", "REFUND", "OTHER"
    )
    private val allowedStatuses = setOf(
        "OPEN", "IN_REVIEW", "NEED_MORE_INFO", "APPROVED",
        "REJECTED", "RESOLVED", "CANCELLED"
    )
    private val allowedRefundStatuses = setOf("NONE", "REQUESTED", "APPROVED", "REFUNDED", "REFUND_PROCESSING", "REJECTED")
    private val allowedRefundMethods = setOf("ORIGINAL_PAYMENT", "BANK_TRANSFER", "CASH", "POINTS", "OTHER")
    private val allowedPriorities = setOf("LOW", "NORMAL", "HIGH", "URGENT")
    private val complaintEligibleOrderStatuses = setOf("DELIVERED")
    private val closedComplaintStatuses = setOf("RESOLVED", "REJECTED", "CANCELLED")

    fun createComplaint(userId: String, request: CreateComplaintRequest): ComplaintDto = transaction {
        val normalizedType = request.type.trim().uppercase().ifBlank { "OTHER" }
        require(normalizedType in allowedTypes) {
            "Complaint type must be one of: ${allowedTypes.joinToString(", ")}"
        }
        require(request.title.isNotBlank()) { "Title is required" }
        require(request.description.isNotBlank()) { "Description is required" }

        val order = OrdersTable.selectAll()
            .where { (OrdersTable.id eq request.orderId) and (OrdersTable.userId eq userId) }
            .singleOrNull()
            ?: throw IllegalArgumentException("Order not found")
        val orderStatus = order[OrdersTable.status].uppercase()
        val paymentStatus = order[OrdersTable.paymentStatus].uppercase()
        require(orderStatus in complaintEligibleOrderStatuses) {
            "Chỉ có thể khiếu nại sau khi đã nhận được hàng"
        }

        val completedAt = order[OrdersTable.completedAt]
        if (completedAt != null) {
            val nowJava = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
            val daysSinceCompleted = java.time.temporal.ChronoUnit.DAYS.between(
                completedAt.toJavaLocalDateTime(),
                nowJava
            )
            require(daysSinceCompleted <= 30) {
                "Đã quá 30 ngày kể từ khi đơn hàng hoàn thành, không thể tạo khiếu nại"
            }
        }

        val productId = resolveComplaintProductId(request.orderId, request.orderItemId, request.productId)
        val orderItemId = request.orderItemId?.trim()?.ifBlank { null }
        if (orderItemId != null) {
            val validOrderItem = OrderItemsTable.selectAll()
                .where { (OrderItemsTable.id eq orderItemId) and (OrderItemsTable.orderId eq request.orderId) }
                .count() > 0
            require(validOrderItem) { "Order item not found" }
        }
        val existingComplaint = if (orderItemId != null) {
            // Item-level: one complaint per order item (regardless of who filed it)
            OrderComplaintsTable.selectAll()
                .where {
                    (OrderComplaintsTable.orderId eq request.orderId) and
                        (OrderComplaintsTable.orderItemId eq orderItemId)
                }
                .count() > 0
        } else {
            // Whole-order: one whole-order complaint per user per order
            OrderComplaintsTable.selectAll()
                .where {
                    (OrderComplaintsTable.userId eq userId) and
                        (OrderComplaintsTable.orderId eq request.orderId) and
                        OrderComplaintsTable.orderItemId.isNull()
                }
                .count() > 0
        }
        require(!existingComplaint) {
            if (orderItemId != null) "Sản phẩm này đã có khiếu nại, không thể tạo thêm"
            else "Đơn hàng này đã có khiếu nại chung, không thể tạo thêm"
        }

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val complaintId = UUID.randomUUID().toString()
        val code = "CMP-${System.currentTimeMillis().toString().takeLast(9)}"
        val attachments = request.attachments
            .filter { it.fileUrl.isNotBlank() }
            .map { attachment ->
                ComplaintAttachmentDto(
                    id = UUID.randomUUID().toString(),
                    fileUrl = attachment.fileUrl.trim(),
                    fileType = attachment.fileType.ifBlank { "IMAGE" },
                    publicId = attachment.publicId?.ifBlank { null },
                    createdAt = now.toString()
                )
            }
        val initialMessage = ComplaintMessageDto(
            id = UUID.randomUUID().toString(),
            senderUserId = userId,
            senderRole = "USER",
            message = request.description.trim(),
            isInternal = false,
            createdAt = now.toString()
        )
        OrderComplaintsTable.insert {
            it[id] = complaintId
            it[complaintCode] = code
            it[OrderComplaintsTable.userId] = userId
            it[orderId] = order[OrdersTable.id]
            it[OrderComplaintsTable.orderItemId] = orderItemId
            it[OrderComplaintsTable.productId] = productId
            it[type] = normalizedType
            it[title] = request.title.trim()
            it[description] = request.description.trim()
            it[attachmentsJson] = encodeList(attachments)
            it[messagesJson] = encodeList(listOf(initialMessage))
            it[eventsJson] = null
            it[status] = "OPEN"
            it[priority] = "NORMAL"
            it[createdAt] = now
            it[updatedAt] = now
        }

        insertComplaintEvent(
            complaintId = complaintId,
            actorUserId = userId,
            actorRole = "USER",
            eventType = "CREATED",
            title = "Khách hàng tạo khiếu nại",
            description = request.description.trim(),
            toStatus = "OPEN",
            toPriority = "NORMAL",
            dueAt = calculateDueAt("OPEN", "NORMAL", now),
            createdAt = now
        )

        getComplaintForUser(complaintId, userId) ?: throw IllegalStateException("Cannot load created complaint")
    }

    fun listUserComplaints(userId: String): List<ComplaintDto> = transaction {
        val rows = OrderComplaintsTable.selectAll()
            .where { OrderComplaintsTable.userId eq userId }
            .orderBy(OrderComplaintsTable.createdAt to SortOrder.DESC)
            .toList()
        mapComplaintRows(rows, includeMessages = false, includeInternalMessages = false)
    }

    fun getComplaintForUser(complaintId: String, userId: String): ComplaintDto? = transaction {
        val row = OrderComplaintsTable.selectAll()
            .where { (OrderComplaintsTable.id eq complaintId) and (OrderComplaintsTable.userId eq userId) }
            .singleOrNull()
            ?: return@transaction null
        mapComplaintRows(listOf(row), includeMessages = true, includeInternalMessages = false).single()
    }

    fun listInternalComplaints(
        status: String? = null,
        priority: String? = null,
        type: String? = null,
        limit: Int = 100
    ): List<ComplaintDto> = transaction {
        var query = OrderComplaintsTable.selectAll()
        status?.trim()?.uppercase()?.ifBlank { null }?.let { normalizedStatus ->
            query = query.where { OrderComplaintsTable.status eq normalizedStatus }
        }
        priority?.trim()?.uppercase()?.ifBlank { null }?.let { normalizedPriority ->
            query = query.andWhere { OrderComplaintsTable.priority eq normalizedPriority }
        }
        type?.trim()?.uppercase()?.ifBlank { null }?.let { normalizedType ->
            query = query.andWhere { OrderComplaintsTable.type eq normalizedType }
        }
        mapComplaintRows(
            query.orderBy(OrderComplaintsTable.createdAt to SortOrder.DESC)
                .limit(limit.coerceIn(1, 500))
                .toList(),
            includeMessages = false,
            includeInternalMessages = true
        )
    }

    fun getInternalComplaint(complaintId: String): ComplaintDto? = transaction {
        val row = OrderComplaintsTable.selectAll()
            .where { OrderComplaintsTable.id eq complaintId }
            .singleOrNull()
            ?: return@transaction null
        mapComplaintRows(listOf(row), includeMessages = true, includeInternalMessages = true).single()
    }

    fun updateComplaint(complaintId: String, actorUserId: String, request: UpdateComplaintRequest): ComplaintDto = transaction {
        val existing = OrderComplaintsTable.selectAll()
            .where { OrderComplaintsTable.id eq complaintId }
            .singleOrNull()
            ?: throw IllegalArgumentException("Complaint not found")

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val nextStatus = request.status?.trim()?.uppercase()?.ifBlank { existing[OrderComplaintsTable.status] }
            ?: existing[OrderComplaintsTable.status]
        val nextPriority = request.priority?.trim()?.uppercase()?.ifBlank { existing[OrderComplaintsTable.priority] }
            ?: existing[OrderComplaintsTable.priority]
        val shouldClearRefund = request.refundStatus?.trim()?.uppercase() == "NONE"
        val nextRefundAmount = if (shouldClearRefund) null else request.refundAmount?.let { amount ->
            require(amount >= 0.0) { "Refund amount must be non-negative" }
            BigDecimal.valueOf(amount)
        } ?: existing[OrderComplaintsTable.refundAmount]
        val nextRefundStatus = request.refundStatus?.trim()?.uppercase()?.ifBlank { existing[OrderComplaintsTable.refundStatus] }
            ?: existing[OrderComplaintsTable.refundStatus]
        val nextRefundMethod = if (shouldClearRefund) null else request.refundMethod?.trim()?.uppercase()?.ifBlank { existing[OrderComplaintsTable.refundMethod] }
            ?: existing[OrderComplaintsTable.refundMethod]
        val requestedRefundTransactionId = if (shouldClearRefund) null else request.refundTransactionId?.trim()?.ifBlank { existing[OrderComplaintsTable.refundTransactionId] }
            ?: existing[OrderComplaintsTable.refundTransactionId]
        require(nextStatus in allowedStatuses) { "Invalid complaint status" }
        require(nextPriority in allowedPriorities) { "Invalid complaint priority" }
        require(nextRefundStatus in allowedRefundStatuses) { "Invalid refund status" }
        nextRefundMethod?.let { require(it in allowedRefundMethods) { "Invalid refund method" } }
        require(existing[OrderComplaintsTable.refundStatus] != "REFUNDED" || nextRefundStatus == "REFUNDED") {
            "Refunded complaint cannot be reverted"
        }
        if (nextRefundStatus == "REFUNDED") {
            require(nextRefundAmount != null && nextRefundAmount > BigDecimal.ZERO) {
                "Refund amount is required when refund status is REFUNDED"
            }
        }

        // Admin gửi REFUNDED + ORIGINAL_PAYMENT → gọi gateway thực sự.
        // Nếu gateway fail, exception lan ra ngoài → transaction rollback → DB KHÔNG bị set REFUNDED nhầm.
        val isRequestingGatewayRefund = nextRefundStatus == "REFUNDED" &&
            existing[OrderComplaintsTable.refundStatus] !in setOf("REFUNDED", "REFUND_PROCESSING") &&
            nextRefundMethod == "ORIGINAL_PAYMENT"
        val gatewayRefundResult: RefundResult? = if (isRequestingGatewayRefund) {
            paymentService.processComplaintRefund(
                orderId = existing[OrderComplaintsTable.orderId],
                refundAmount = nextRefundAmount
            )
        } else null
        // MoMo → "REFUNDED"; ZaloPay → "REFUND_PROCESSING" (async); không có gateway → giữ giá trị admin gửi
        val effectiveRefundStatus = gatewayRefundResult?.status ?: nextRefundStatus
        val finalRefundTransactionId = gatewayRefundResult?.transactionId ?: requestedRefundTransactionId

        OrderComplaintsTable.update({ OrderComplaintsTable.id eq complaintId }) {
            it[status] = nextStatus
            it[priority] = nextPriority
            it[resolution] = request.resolution?.trim()?.ifBlank { null } ?: existing[OrderComplaintsTable.resolution]
            it[refundAmount] = nextRefundAmount
            it[refundStatus] = effectiveRefundStatus
            it[refundMethod] = nextRefundMethod
            it[refundTransactionId] = finalRefundTransactionId
            it[handledBy] = request.handledBy?.trim()?.ifBlank { null } ?: actorUserId
            it[updatedAt] = now
            if (request.restoreStock) it[restoreStock] = true
            if (effectiveRefundStatus == "REFUNDED" && existing[OrderComplaintsTable.refundedAt] == null) {
                it[refundedBy] = actorUserId
                it[refundedAt] = now
            }
            if (nextStatus in setOf("RESOLVED", "REJECTED", "CANCELLED")) {
                it[resolvedAt] = existing[OrderComplaintsTable.resolvedAt] ?: now
                it[closedAt] = existing[OrderComplaintsTable.closedAt] ?: now
            }
        }
        val refundChanged = effectiveRefundStatus != existing[OrderComplaintsTable.refundStatus] ||
            nextRefundAmount != existing[OrderComplaintsTable.refundAmount] ||
            nextRefundMethod != existing[OrderComplaintsTable.refundMethod] ||
            finalRefundTransactionId != existing[OrderComplaintsTable.refundTransactionId]
        val isNewlyRefunded = effectiveRefundStatus == "REFUNDED" && existing[OrderComplaintsTable.refundStatus] != "REFUNDED"
        val isNewlyProcessing = effectiveRefundStatus == "REFUND_PROCESSING" &&
            existing[OrderComplaintsTable.refundStatus] !in setOf("REFUNDED", "REFUND_PROCESSING")
        if (isNewlyProcessing) {
            OrdersTable.update({ OrdersTable.id eq existing[OrderComplaintsTable.orderId] }) {
                it[OrdersTable.paymentStatus] = "REFUND_PROCESSING"
                it[OrdersTable.updatedAt] = now
            }
        }
        if (isNewlyRefunded) {
            markOrderPaymentRefunded(
                orderId = existing[OrderComplaintsTable.orderId],
                refundAmount = nextRefundAmount,
                refundMethod = nextRefundMethod,
                refundTransactionId = finalRefundTransactionId,
                now = now
            )
        }
        if (request.restoreStock && (isNewlyRefunded || (!isNewlyRefunded && nextStatus == "RESOLVED"))) {
            val complaintOrderItemId = existing[OrderComplaintsTable.orderItemId]
            if (complaintOrderItemId != null) {
                // Item-level: only restore that item's stock, order stays DELIVERED
                OrderLifecycleService().restoreStockForOrderItem(complaintOrderItemId)
            } else {
                // Whole-order: restore all items, mark order as RETURNED, revert coupon/reward
                val orderRow = OrdersTable.selectAll()
                    .where { OrdersTable.id eq existing[OrderComplaintsTable.orderId] }
                    .singleOrNull()
                if (orderRow != null && orderRow[OrdersTable.status] !in setOf("CANCELLED", "RETURNED")) {
                    OrderLifecycleService().restoreStockOnCancel(orderRow)
                    OrderLifecycleService().revertCouponAndRewardUsage(existing[OrderComplaintsTable.orderId])
                    OrdersTable.update({ OrdersTable.id eq existing[OrderComplaintsTable.orderId] }) {
                        it[OrdersTable.status] = "RETURNED"
                        it[OrdersTable.updatedAt] = now
                    }
                }
            }
        }
        if (nextStatus != existing[OrderComplaintsTable.status] || nextPriority != existing[OrderComplaintsTable.priority]) {
            insertComplaintEvent(
                complaintId = complaintId,
                actorUserId = actorUserId,
                actorRole = "STAFF",
                eventType = "STATUS_CHANGED",
                title = "Cập nhật trạng thái xử lý",
                description = request.resolution?.trim()?.ifBlank { null },
                fromStatus = existing[OrderComplaintsTable.status],
                toStatus = nextStatus,
                fromPriority = existing[OrderComplaintsTable.priority],
                toPriority = nextPriority,
                dueAt = calculateDueAt(nextStatus, nextPriority, now),
                createdAt = now
            )
        }
        if (refundChanged) {
            insertComplaintEvent(
                complaintId = complaintId,
                actorUserId = actorUserId,
                actorRole = "STAFF",
                eventType = "REFUND_UPDATED",
                title = "Cập nhật hoàn tiền",
                description = buildRefundDescription(effectiveRefundStatus, nextRefundAmount, nextRefundMethod, finalRefundTransactionId),
                fromStatus = existing[OrderComplaintsTable.status],
                toStatus = nextStatus,
                fromPriority = existing[OrderComplaintsTable.priority],
                toPriority = nextPriority,
                dueAt = calculateDueAt(nextStatus, nextPriority, now),
                createdAt = now
            )
        } else if (!request.resolution.isNullOrBlank() && nextStatus == existing[OrderComplaintsTable.status] && nextPriority == existing[OrderComplaintsTable.priority]) {
            insertComplaintEvent(
                complaintId = complaintId,
                actorUserId = actorUserId,
                actorRole = "STAFF",
                eventType = "RESOLUTION_UPDATED",
                title = "Cập nhật hướng xử lý",
                description = request.resolution.trim(),
                fromStatus = existing[OrderComplaintsTable.status],
                toStatus = nextStatus,
                fromPriority = existing[OrderComplaintsTable.priority],
                toPriority = nextPriority,
                dueAt = calculateDueAt(nextStatus, nextPriority, now),
                createdAt = now
            )
        }

        val complaintUserId = existing[OrderComplaintsTable.userId]
        if (actorUserId != complaintUserId) {
            val complaintCode = existing[OrderComplaintsTable.complaintCode]
            when {
                refundChanged -> {
                    val refundAmountText = nextRefundAmount?.stripTrailingZeros()?.toPlainString()
                    val (notifTitle, notifBody) = when (effectiveRefundStatus) {
                        "REFUNDED" -> "Đã hoàn tiền khiếu nại" to buildString {
                            append("Khiếu nại $complaintCode đã được hoàn tiền")
                            if (!refundAmountText.isNullOrBlank()) append(" $refundAmountText đ")
                            append(" thành công.")
                        }
                        "REFUND_PROCESSING" -> "Đang xử lý hoàn tiền" to buildString {
                            append("Khiếu nại $complaintCode: hoàn tiền đang được xử lý qua cổng thanh toán")
                            if (!refundAmountText.isNullOrBlank()) append(", số tiền $refundAmountText đ")
                            append(".")
                        }
                        "REFUND_FAILED" -> "Hoàn tiền tự động thất bại" to "Khiếu nại $complaintCode: hệ thống không thể hoàn tiền tự động. Vui lòng mở khiếu nại để yêu cầu hoàn tiền thủ công hoặc liên hệ hỗ trợ."
                        else -> "Cập nhật hoàn tiền khiếu nại" to buildString {
                            append("Khiếu nại $complaintCode: trạng thái hoàn tiền đã được cập nhật")
                            if (!refundAmountText.isNullOrBlank()) append(", số tiền $refundAmountText đ")
                            append(".")
                        }
                    }
                    notificationService.createUserNotification(
                        userId = complaintUserId,
                        title = notifTitle,
                        body = notifBody,
                        type = "REFUND",
                        refId = complaintId
                    )
                }
                nextStatus != existing[OrderComplaintsTable.status] ||
                    nextPriority != existing[OrderComplaintsTable.priority] ||
                    !request.resolution.isNullOrBlank() -> {
                    notificationService.createUserNotification(
                        userId = complaintUserId,
                        title = "Khiếu nại đã được cập nhật",
                        body = "Khiếu nại $complaintCode hiện ở trạng thái $nextStatus.",
                        type = "COMPLAINT",
                        refId = complaintId
                    )
                }
            }
        }

        getInternalComplaint(complaintId) ?: throw IllegalStateException("Cannot load updated complaint")
    }

    fun addMessage(
        complaintId: String,
        senderUserId: String,
        senderRole: String,
        request: ComplaintMessageRequest,
        userScoped: Boolean
    ): ComplaintMessageDto = transaction {
        require(request.message.isNotBlank()) { "Message is required" }
        val complaint = OrderComplaintsTable.selectAll()
            .where { OrderComplaintsTable.id eq complaintId }
            .singleOrNull()
            ?: throw IllegalArgumentException("Complaint not found")
        if (userScoped) {
            require(complaint[OrderComplaintsTable.userId] == senderUserId) { "Complaint not found" }
        }

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val messageId = UUID.randomUUID().toString()
        val normalizedRole = senderRole.trim().uppercase().ifBlank { "USER" }
        val message = ComplaintMessageDto(
            id = messageId,
            senderUserId = senderUserId,
            senderRole = normalizedRole,
            message = request.message.trim(),
            isInternal = request.isInternal && !userScoped,
            createdAt = now.toString()
        )
        appendComplaintMessage(complaintId, message)
        insertComplaintEvent(
            complaintId = complaintId,
            actorUserId = senderUserId,
            actorRole = normalizedRole,
            eventType = if (request.isInternal && !userScoped) "INTERNAL_NOTE" else "MESSAGE_ADDED",
            title = if (request.isInternal && !userScoped) "Thêm ghi chú nội bộ" else "Thêm phản hồi",
            description = request.message.trim(),
            fromStatus = complaint[OrderComplaintsTable.status],
            toStatus = complaint[OrderComplaintsTable.status],
            fromPriority = complaint[OrderComplaintsTable.priority],
            toPriority = complaint[OrderComplaintsTable.priority],
            dueAt = calculateDueAt(complaint[OrderComplaintsTable.status], complaint[OrderComplaintsTable.priority], now),
            createdAt = now
        )
        if (!userScoped && !request.isInternal && complaint[OrderComplaintsTable.userId] != senderUserId) {
            notificationService.createUserNotification(
                userId = complaint[OrderComplaintsTable.userId],
                title = "CSKH đã phản hồi khiếu nại",
                body = request.message.trim().take(160),
                type = "COMPLAINT",
                refId = complaintId
            )
        }
        message
    }

    fun addAttachments(complaintId: String, userId: String, attachments: List<ComplaintAttachmentInput>): ComplaintDto = transaction {
        require(attachments.isNotEmpty()) { "At least one attachment is required" }
        val complaint = OrderComplaintsTable.selectAll()
            .where { (OrderComplaintsTable.id eq complaintId) and (OrderComplaintsTable.userId eq userId) }
            .singleOrNull()
            ?: throw IllegalArgumentException("Complaint not found")
        require(complaint[OrderComplaintsTable.status] !in closedComplaintStatuses) {
            "Cannot add attachments to a closed complaint"
        }

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val newAttachments = attachments
            .filter { it.fileUrl.isNotBlank() }
            .map { attachment ->
                ComplaintAttachmentDto(
                    id = UUID.randomUUID().toString(),
                    fileUrl = attachment.fileUrl.trim(),
                    fileType = attachment.fileType.ifBlank { "IMAGE" },
                    publicId = attachment.publicId?.ifBlank { null },
                    createdAt = now.toString()
                )
            }
        require(newAttachments.isNotEmpty()) { "No valid attachments provided" }

        val existing = decodeList<ComplaintAttachmentDto>(complaint[OrderComplaintsTable.attachmentsJson])
        OrderComplaintsTable.update({ OrderComplaintsTable.id eq complaintId }) {
            it[attachmentsJson] = encodeList(existing + newAttachments)
            it[updatedAt] = now
        }

        insertComplaintEvent(
            complaintId = complaintId,
            actorUserId = userId,
            actorRole = "USER",
            eventType = "ATTACHMENT_ADDED",
            title = "Thêm bằng chứng",
            description = "Khách hàng bổ sung ${newAttachments.size} tệp đính kèm",
            fromStatus = complaint[OrderComplaintsTable.status],
            toStatus = complaint[OrderComplaintsTable.status],
            fromPriority = complaint[OrderComplaintsTable.priority],
            toPriority = complaint[OrderComplaintsTable.priority],
            dueAt = calculateDueAt(complaint[OrderComplaintsTable.status], complaint[OrderComplaintsTable.priority], now),
            createdAt = now
        )

        getComplaintForUser(complaintId, userId) ?: throw IllegalStateException("Cannot load updated complaint")
    }

    fun requestRefund(complaintId: String, userId: String): ComplaintDto = transaction {
        val complaint = OrderComplaintsTable.selectAll()
            .where { (OrderComplaintsTable.id eq complaintId) and (OrderComplaintsTable.userId eq userId) }
            .singleOrNull()
            ?: throw IllegalArgumentException("Complaint not found")
        require(complaint[OrderComplaintsTable.status] !in closedComplaintStatuses) {
            "Cannot request refund for a closed complaint"
        }
        require(complaint[OrderComplaintsTable.refundStatus] in setOf("NONE", "REFUND_FAILED")) {
            "Refund has already been requested or processed"
        }

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        OrderComplaintsTable.update({ OrderComplaintsTable.id eq complaintId }) {
            it[refundStatus] = "REQUESTED"
            it[updatedAt] = now
        }

        insertComplaintEvent(
            complaintId = complaintId,
            actorUserId = userId,
            actorRole = "USER",
            eventType = "REFUND_UPDATED",
            title = "Khách hàng yêu cầu hoàn tiền",
            description = "Khách hàng đã gửi yêu cầu hoàn tiền",
            fromStatus = complaint[OrderComplaintsTable.status],
            toStatus = complaint[OrderComplaintsTable.status],
            fromPriority = complaint[OrderComplaintsTable.priority],
            toPriority = complaint[OrderComplaintsTable.priority],
            dueAt = calculateDueAt(complaint[OrderComplaintsTable.status], complaint[OrderComplaintsTable.priority], now),
            createdAt = now
        )

        getComplaintForUser(complaintId, userId) ?: throw IllegalStateException("Cannot load updated complaint")
    }

    private fun resolveComplaintProductId(orderId: String, orderItemId: String?, requestedProductId: String?): String? {
        val normalizedOrderItemId = orderItemId?.trim()?.ifBlank { null }
        if (normalizedOrderItemId != null) {
            return OrderItemsTable.selectAll()
                .where { (OrderItemsTable.id eq normalizedOrderItemId) and (OrderItemsTable.orderId eq orderId) }
                .singleOrNull()
                ?.get(OrderItemsTable.productId)
                ?: throw IllegalArgumentException("Order item not found")
        }
        return requestedProductId?.trim()?.ifBlank { null }
    }

    private fun mapComplaintRows(
        rows: List<ResultRow>,
        includeMessages: Boolean,
        includeInternalMessages: Boolean
    ): List<ComplaintDto> {
        val attachmentsByComplaintId = rows.associate { row ->
            row[OrderComplaintsTable.id] to decodeList<ComplaintAttachmentDto>(row[OrderComplaintsTable.attachmentsJson])
        }

        val rawMessagesByComplaintId = if (!includeMessages) {
            emptyMap()
        } else {
            rows.associate { row ->
                row[OrderComplaintsTable.id] to decodeList<ComplaintMessageDto>(row[OrderComplaintsTable.messagesJson])
                    .filter { includeInternalMessages || !it.isInternal }
                    .sortedBy { it.createdAt }
            }
        }
        val senderIds = rawMessagesByComplaintId.values.flatten().map { it.senderUserId }.distinct()
        val senderNames = if (senderIds.isEmpty()) emptyMap() else UsersTable.selectAll()
            .where { UsersTable.id inList senderIds }
            .associate { it[UsersTable.id] to it[UsersTable.fullName] }
        val messagesByComplaintId = rawMessagesByComplaintId.mapValues { (_, messages) ->
            messages.map { it.copy(senderName = senderNames[it.senderUserId]) }
        }

        val rawEventsByComplaintId = if (!includeMessages) {
            emptyMap()
        } else {
            rows.associate { row ->
                row[OrderComplaintsTable.id] to decodeList<ComplaintEventDto>(row[OrderComplaintsTable.eventsJson])
                    .filter { includeInternalMessages || it.eventType != "INTERNAL_NOTE" }
                    .sortedBy { it.createdAt }
            }
        }
        val actorIds = rawEventsByComplaintId.values.flatten().mapNotNull { it.actorUserId }.distinct()
        val actorNames = if (actorIds.isEmpty()) emptyMap() else UsersTable.selectAll()
            .where { UsersTable.id inList actorIds }
            .associate { it[UsersTable.id] to it[UsersTable.fullName] }
        val eventsByComplaintId = rawEventsByComplaintId.mapValues { (_, events) ->
            events.map { event -> event.copy(actorName = event.actorUserId?.let { actorNames[it] }) }
        }

        val userIds = rows.map { it[OrderComplaintsTable.userId] }.distinct()
        val userNames = if (userIds.isEmpty()) emptyMap() else UsersTable.selectAll()
            .where { UsersTable.id inList userIds }
            .associate { it[UsersTable.id] to it[UsersTable.fullName] }

        val productIds = rows.mapNotNull { it[OrderComplaintsTable.productId] }.distinct()
        val productNames = if (productIds.isEmpty()) emptyMap() else ProductsTable.selectAll()
            .where { ProductsTable.id inList productIds }
            .associate { it[ProductsTable.id] to it[ProductsTable.name] }

        val orderIds = rows.map { it[OrderComplaintsTable.orderId] }.distinct()
        val orderTotals = if (orderIds.isEmpty()) emptyMap() else OrdersTable.selectAll()
            .where { OrdersTable.id inList orderIds }
            .associate { it[OrdersTable.id] to it[OrdersTable.total]?.toDouble() }

        val orderItemIds = rows.mapNotNull { it[OrderComplaintsTable.orderItemId] }.distinct()
        val itemTotals = if (orderItemIds.isEmpty()) emptyMap() else OrderItemsTable.selectAll()
            .where { OrderItemsTable.id inList orderItemIds }
            .associate { it[OrderItemsTable.id] to (it[OrderItemsTable.price].toDouble() * it[OrderItemsTable.quantity]) }

        return rows.map { row ->
            val id = row[OrderComplaintsTable.id]
            ComplaintDto(
                id = id,
                complaintCode = row[OrderComplaintsTable.complaintCode],
                userId = row[OrderComplaintsTable.userId],
                userName = userNames[row[OrderComplaintsTable.userId]],
                orderId = row[OrderComplaintsTable.orderId],
                orderItemId = row[OrderComplaintsTable.orderItemId],
                productId = row[OrderComplaintsTable.productId],
                productName = row[OrderComplaintsTable.productId]?.let { productNames[it] },
                type = row[OrderComplaintsTable.type],
                title = row[OrderComplaintsTable.title],
                description = row[OrderComplaintsTable.description],
                status = row[OrderComplaintsTable.status],
                priority = row[OrderComplaintsTable.priority],
                resolution = row[OrderComplaintsTable.resolution],
                refundAmount = row[OrderComplaintsTable.refundAmount]?.toDouble(),
                refundStatus = row[OrderComplaintsTable.refundStatus],
                refundMethod = row[OrderComplaintsTable.refundMethod],
                refundTransactionId = row[OrderComplaintsTable.refundTransactionId],
                refundedBy = row[OrderComplaintsTable.refundedBy],
                refundedAt = row[OrderComplaintsTable.refundedAt]?.toString(),
                handledBy = row[OrderComplaintsTable.handledBy],
                createdAt = row[OrderComplaintsTable.createdAt].toString(),
                updatedAt = row[OrderComplaintsTable.updatedAt].toString(),
                resolvedAt = row[OrderComplaintsTable.resolvedAt]?.toString(),
                closedAt = row[OrderComplaintsTable.closedAt]?.toString(),
                orderTotal = orderTotals[row[OrderComplaintsTable.orderId]],
                itemTotal = row[OrderComplaintsTable.orderItemId]?.let { itemTotals[it] },
                attachments = attachmentsByComplaintId[id].orEmpty(),
                messages = messagesByComplaintId[id].orEmpty(),
                events = eventsByComplaintId[id].orEmpty()
            )
        }
    }

    private fun markOrderPaymentRefunded(
        orderId: String,
        refundAmount: BigDecimal?,
        refundMethod: String?,
        refundTransactionId: String?,
        now: kotlinx.datetime.LocalDateTime
    ) {
        val order = OrdersTable.selectAll()
            .where { OrdersTable.id eq orderId }
            .singleOrNull()
            ?: return
        val total = order[OrdersTable.total] ?: BigDecimal.ZERO
        val nextPaymentStatus = if (total > BigDecimal.ZERO && refundAmount != null && refundAmount >= total) {
            "REFUNDED"
        } else {
            "PARTIALLY_REFUNDED"
        }
        OrdersTable.update({ OrdersTable.id eq orderId }) {
            it[OrdersTable.paymentStatus] = nextPaymentStatus
            it[OrdersTable.updatedAt] = now
        }
        if (refundAmount != null && refundAmount > BigDecimal.ZERO) {
            PaymentsTable.insert {
                it[PaymentsTable.id] = UUID.randomUUID().toString()
                it[PaymentsTable.orderId] = orderId
                it[PaymentsTable.method] = "REFUND"
                it[PaymentsTable.amount] = refundAmount
                it[PaymentsTable.transactionId] = refundTransactionId
                it[PaymentsTable.paymentGatewayResponse] = refundMethod?.let { m -> """{"refundMethod":"$m"}""" }
                it[PaymentsTable.status] = "COMPLETED"
                it[PaymentsTable.paidAt] = now
                it[PaymentsTable.createdAt] = now
            }
        }
    }

    private fun buildRefundDescription(
        refundStatus: String,
        refundAmount: BigDecimal?,
        refundMethod: String?,
        refundTransactionId: String?
    ): String {
        val parts = mutableListOf("Trạng thái: $refundStatus")
        refundAmount?.let { parts += "Số tiền: $it" }
        refundMethod?.let { parts += "Phương thức: $it" }
        refundTransactionId?.let { parts += "Mã giao dịch: $it" }
        return parts.joinToString(" | ")
    }

    private fun insertComplaintEvent(
        complaintId: String,
        actorUserId: String?,
        actorRole: String?,
        eventType: String,
        title: String,
        description: String? = null,
        fromStatus: String? = null,
        toStatus: String? = null,
        fromPriority: String? = null,
        toPriority: String? = null,
        dueAt: kotlinx.datetime.LocalDateTime? = null,
        createdAt: kotlinx.datetime.LocalDateTime
    ) {
        appendComplaintEvent(
            complaintId = complaintId,
            event = ComplaintEventDto(
                id = UUID.randomUUID().toString(),
                actorUserId = actorUserId,
                actorRole = actorRole,
                eventType = eventType,
                title = title,
                description = description?.take(500),
                fromStatus = fromStatus,
                toStatus = toStatus,
                fromPriority = fromPriority,
                toPriority = toPriority,
                dueAt = dueAt?.toString(),
                createdAt = createdAt.toString()
            )
        )
    }

    private fun appendComplaintMessage(complaintId: String, message: ComplaintMessageDto) {
        val existing = OrderComplaintsTable.selectAll()
            .where { OrderComplaintsTable.id eq complaintId }
            .singleOrNull()
            ?: return
        val messages = decodeList<ComplaintMessageDto>(existing[OrderComplaintsTable.messagesJson]) + message
        OrderComplaintsTable.update({ OrderComplaintsTable.id eq complaintId }) {
            it[messagesJson] = encodeList(messages)
        }
    }

    private fun appendComplaintEvent(complaintId: String, event: ComplaintEventDto) {
        val existing = OrderComplaintsTable.selectAll()
            .where { OrderComplaintsTable.id eq complaintId }
            .singleOrNull()
            ?: return
        val events = decodeList<ComplaintEventDto>(existing[OrderComplaintsTable.eventsJson]) + event
        OrderComplaintsTable.update({ OrderComplaintsTable.id eq complaintId }) {
            it[eventsJson] = encodeList(events)
        }
    }

    private inline fun <reified T> encodeList(items: List<T>): String? {
        return items.takeIf { it.isNotEmpty() }?.let { complaintJson.encodeToString(it) }
    }

    private inline fun <reified T> decodeList(raw: String?): List<T> {
        return raw?.takeIf { it.isNotBlank() }
            ?.let { value -> runCatching { complaintJson.decodeFromString<List<T>>(value) }.getOrDefault(emptyList()) }
            .orEmpty()
    }

    fun syncSingleComplaintRefund(complaintId: String): Boolean {
        val row = transaction {
            OrderComplaintsTable.selectAll()
                .where { OrderComplaintsTable.id eq complaintId }
                .singleOrNull()
        } ?: return false
        if (row[OrderComplaintsTable.refundStatus] != "REFUND_PROCESSING") return false
        val mRefundId = row[OrderComplaintsTable.refundTransactionId] ?: return false
        if (!mRefundId.matches(Regex("\\d{6}_\\d+_.+"))) return false
        val returnCode = paymentService.queryZaloPayRefundStatus(mRefundId)
        if (returnCode == 1) {
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val orderId = row[OrderComplaintsTable.orderId]
            val shouldRestoreStock = row[OrderComplaintsTable.restoreStock]
            transaction {
                OrderComplaintsTable.update({ OrderComplaintsTable.id eq complaintId }) {
                    it[refundStatus] = "REFUNDED"
                    it[refundedAt] = now
                    it[updatedAt] = now
                }
                markOrderPaymentRefunded(
                    orderId = orderId,
                    refundAmount = row[OrderComplaintsTable.refundAmount],
                    refundMethod = row[OrderComplaintsTable.refundMethod],
                    refundTransactionId = mRefundId,
                    now = now
                )
                if (shouldRestoreStock) {
                    val complaintOrderItemId = row[OrderComplaintsTable.orderItemId]
                    if (complaintOrderItemId != null) {
                        OrderLifecycleService().restoreStockForOrderItem(complaintOrderItemId)
                    } else {
                        val orderRow = OrdersTable.selectAll().where { OrdersTable.id eq orderId }.singleOrNull()
                        if (orderRow != null && orderRow[OrdersTable.status] !in setOf("CANCELLED", "RETURNED")) {
                            OrderLifecycleService().restoreStockOnCancel(orderRow)
                            OrdersTable.update({ OrdersTable.id eq orderId }) {
                                it[OrdersTable.status] = "RETURNED"
                                it[OrdersTable.updatedAt] = now
                            }
                        }
                    }
                }
            }
            return true
        }
        return false
    }

    fun syncZaloPayRefundStatuses() {
        val paymentService = PaymentService()
        val processingComplaints = transaction {
            OrderComplaintsTable.selectAll()
                .where { OrderComplaintsTable.refundStatus eq "REFUND_PROCESSING" }
                .toList()
        }
        if (processingComplaints.isEmpty()) return
        println("INFO: Syncing ${processingComplaints.size} REFUND_PROCESSING complaint(s) with ZaloPay")
        for (row in processingComplaints) {
            val complaintId = row[OrderComplaintsTable.id]
            val mRefundId = row[OrderComplaintsTable.refundTransactionId] ?: continue
            // Only query if it looks like a ZaloPay m_refund_id (yyMMdd_appId_uid format)
            if (!mRefundId.matches(Regex("\\d{6}_\\d+_.+"))) {
                println("SKIP: complaint $complaintId has refundTransactionId=$mRefundId (not m_refund_id format)")
                continue
            }
            val returnCode = paymentService.queryZaloPayRefundStatus(mRefundId)
            if (returnCode == 1) {
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                val refundAmount = row[OrderComplaintsTable.refundAmount]
                val refundMethod = row[OrderComplaintsTable.refundMethod]
                val orderId = row[OrderComplaintsTable.orderId]
                val shouldRestoreStock = row[OrderComplaintsTable.restoreStock]
                transaction {
                    OrderComplaintsTable.update({ OrderComplaintsTable.id eq complaintId }) {
                        it[refundStatus] = "REFUNDED"
                        it[refundedAt] = now
                        it[updatedAt] = now
                    }
                    markOrderPaymentRefunded(
                        orderId = orderId,
                        refundAmount = refundAmount,
                        refundMethod = refundMethod,
                        refundTransactionId = mRefundId,
                        now = now
                    )
                    if (shouldRestoreStock) {
                        val complaintOrderItemId = row[OrderComplaintsTable.orderItemId]
                        if (complaintOrderItemId != null) {
                            OrderLifecycleService().restoreStockForOrderItem(complaintOrderItemId)
                        } else {
                            val orderRow = OrdersTable.selectAll().where { OrdersTable.id eq orderId }.singleOrNull()
                            if (orderRow != null && orderRow[OrdersTable.status] !in setOf("CANCELLED", "RETURNED")) {
                                OrderLifecycleService().restoreStockOnCancel(orderRow)
                                OrdersTable.update({ OrdersTable.id eq orderId }) {
                                    it[OrdersTable.status] = "RETURNED"
                                    it[OrdersTable.updatedAt] = now
                                }
                            }
                        }
                    }
                }
                println("INFO: Complaint $complaintId updated to REFUNDED via ZaloPay query (m_refund_id=$mRefundId)")
            } else {
                println("INFO: Complaint $complaintId still REFUND_PROCESSING (ZaloPay return_code=$returnCode)")
            }
        }
    }

    private fun calculateDueAt(
        status: String,
        priority: String,
        base: kotlinx.datetime.LocalDateTime
    ): kotlinx.datetime.LocalDateTime? {
        if (status in closedComplaintStatuses) return null
        val hours = when (priority.uppercase()) {
            "URGENT" -> 4
            "HIGH" -> 8
            "LOW" -> 72
            else -> 24
        }
        return base.toJavaLocalDateTime().plusHours(hours.toLong()).let {
            kotlinx.datetime.LocalDateTime(
                year = it.year,
                monthNumber = it.monthValue,
                dayOfMonth = it.dayOfMonth,
                hour = it.hour,
                minute = it.minute,
                second = it.second,
                nanosecond = it.nano
            )
        }
    }
}
