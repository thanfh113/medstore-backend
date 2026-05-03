package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.ComplaintAttachmentsTable
import com.example.nhathuoc.database.tables.ComplaintEventsTable
import com.example.nhathuoc.database.tables.ComplaintMessagesTable
import com.example.nhathuoc.database.tables.OrderComplaintsTable
import com.example.nhathuoc.database.tables.OrderItemsTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.ProductsTable
import com.example.nhathuoc.database.tables.UsersTable
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
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
data class UpdateComplaintRequest(
    val status: String? = null,
    val priority: String? = null,
    val resolution: String? = null,
    val refundAmount: Double? = null,
    val refundStatus: String? = null,
    val refundMethod: String? = null,
    val refundTransactionId: String? = null,
    val handledBy: String? = null
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
    val attachments: List<ComplaintAttachmentDto> = emptyList(),
    val messages: List<ComplaintMessageDto> = emptyList(),
    val events: List<ComplaintEventDto> = emptyList()
)

class ComplaintService {
    private val notificationService = NotificationService()

    private val allowedTypes = setOf(
        "MISSING_ITEM", "WRONG_ITEM", "DAMAGED", "COUNTERFEIT",
        "EXPIRED", "PAYMENT", "REFUND", "OTHER"
    )
    private val allowedStatuses = setOf(
        "OPEN", "IN_REVIEW", "NEED_MORE_INFO", "APPROVED",
        "REJECTED", "RESOLVED", "CANCELLED"
    )
    private val allowedRefundStatuses = setOf("NONE", "REQUESTED", "APPROVED", "REFUNDED", "REJECTED")
    private val allowedRefundMethods = setOf("ORIGINAL_PAYMENT", "BANK_TRANSFER", "CASH", "POINTS", "OTHER")
    private val allowedPriorities = setOf("LOW", "NORMAL", "HIGH", "URGENT")
    private val complaintEligibleOrderStatuses = setOf("PROCESSING", "SHIPPING", "DELIVERED")
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
        require(orderStatus in complaintEligibleOrderStatuses || paymentStatus == "COMPLETED") {
            "Complaint can be created after the order is paid or being processed"
        }

        val productId = resolveComplaintProductId(request.orderId, request.orderItemId, request.productId)
        val orderItemId = request.orderItemId?.trim()?.ifBlank { null }
        if (orderItemId != null) {
            val validOrderItem = OrderItemsTable.selectAll()
                .where { (OrderItemsTable.id eq orderItemId) and (OrderItemsTable.orderId eq request.orderId) }
                .count() > 0
            require(validOrderItem) { "Order item not found" }
        }
        val duplicateOpenComplaint = OrderComplaintsTable.selectAll()
            .where {
                (OrderComplaintsTable.userId eq userId) and
                    (OrderComplaintsTable.orderId eq request.orderId) and
                    OrderComplaintsTable.status.notInList(closedComplaintStatuses.toList())
            }
            .let { query ->
                if (orderItemId != null) {
                    query.andWhere { OrderComplaintsTable.orderItemId eq orderItemId }
                } else {
                    query.andWhere { OrderComplaintsTable.orderItemId.isNull() }
                }
            }
            .count() > 0
        require(!duplicateOpenComplaint) {
            "There is already an open complaint for this order or item"
        }

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val complaintId = UUID.randomUUID().toString()
        val code = "CMP-${System.currentTimeMillis().toString().takeLast(9)}"

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
            it[status] = "OPEN"
            it[priority] = "NORMAL"
            it[createdAt] = now
            it[updatedAt] = now
        }

        request.attachments.filter { it.fileUrl.isNotBlank() }.forEach { attachment ->
            ComplaintAttachmentsTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[ComplaintAttachmentsTable.complaintId] = complaintId
                it[fileUrl] = attachment.fileUrl
                it[fileType] = attachment.fileType.ifBlank { "IMAGE" }
                it[cloudinaryPublicId] = attachment.publicId?.ifBlank { null }
                it[createdAt] = now
            }
        }

        ComplaintMessagesTable.insert {
            it[id] = UUID.randomUUID().toString()
            it[ComplaintMessagesTable.complaintId] = complaintId
            it[senderUserId] = userId
            it[senderRole] = "USER"
            it[message] = request.description.trim()
            it[isInternal] = false
            it[createdAt] = now
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
        val nextRefundTransactionId = if (shouldClearRefund) null else request.refundTransactionId?.trim()?.ifBlank { existing[OrderComplaintsTable.refundTransactionId] }
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

        OrderComplaintsTable.update({ OrderComplaintsTable.id eq complaintId }) {
            it[status] = nextStatus
            it[priority] = nextPriority
            it[resolution] = request.resolution?.trim()?.ifBlank { null } ?: existing[OrderComplaintsTable.resolution]
            it[refundAmount] = nextRefundAmount
            it[refundStatus] = nextRefundStatus
            it[refundMethod] = nextRefundMethod
            it[refundTransactionId] = nextRefundTransactionId
            it[handledBy] = request.handledBy?.trim()?.ifBlank { null } ?: actorUserId
            it[updatedAt] = now
            if (nextRefundStatus == "REFUNDED" && existing[OrderComplaintsTable.refundedAt] == null) {
                it[refundedBy] = actorUserId
                it[refundedAt] = now
            }
            if (nextStatus in setOf("RESOLVED", "REJECTED", "CANCELLED")) {
                it[resolvedAt] = existing[OrderComplaintsTable.resolvedAt] ?: now
                it[closedAt] = existing[OrderComplaintsTable.closedAt] ?: now
            }
        }
        val refundChanged = nextRefundStatus != existing[OrderComplaintsTable.refundStatus] ||
            nextRefundAmount != existing[OrderComplaintsTable.refundAmount] ||
            nextRefundMethod != existing[OrderComplaintsTable.refundMethod] ||
            nextRefundTransactionId != existing[OrderComplaintsTable.refundTransactionId]
        if (nextRefundStatus == "REFUNDED" && existing[OrderComplaintsTable.refundStatus] != "REFUNDED") {
            markOrderPaymentRefunded(existing[OrderComplaintsTable.orderId], nextRefundAmount, now)
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
                description = buildRefundDescription(nextRefundStatus, nextRefundAmount, nextRefundMethod, nextRefundTransactionId),
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
                    notificationService.createUserNotification(
                        userId = complaintUserId,
                        title = if (nextRefundStatus == "REFUNDED") {
                            "Đã hoàn tiền khiếu nại"
                        } else {
                            "Cập nhật hoàn tiền khiếu nại"
                        },
                        body = buildString {
                            append("Khiếu nại $complaintCode: trạng thái hoàn tiền $nextRefundStatus")
                            if (!refundAmountText.isNullOrBlank()) append(", số tiền $refundAmountText đ")
                            append(".")
                        },
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
        ComplaintMessagesTable.insert {
            it[id] = messageId
            it[ComplaintMessagesTable.complaintId] = complaintId
            it[ComplaintMessagesTable.senderUserId] = senderUserId
            it[ComplaintMessagesTable.senderRole] = normalizedRole
            it[message] = request.message.trim()
            it[isInternal] = request.isInternal && !userScoped
            it[createdAt] = now
        }
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
        ComplaintMessagesTable
            .selectAll()
            .where { ComplaintMessagesTable.id eq messageId }
            .single()
            .toMessageDto(senderName = null)
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
        val complaintIds = rows.map { it[OrderComplaintsTable.id] }
        val attachmentsByComplaintId = if (complaintIds.isEmpty()) {
            emptyMap()
        } else {
            ComplaintAttachmentsTable.selectAll()
                .where { ComplaintAttachmentsTable.complaintId inList complaintIds }
                .map { attachment ->
                    attachment[ComplaintAttachmentsTable.complaintId] to ComplaintAttachmentDto(
                        id = attachment[ComplaintAttachmentsTable.id],
                        fileUrl = attachment[ComplaintAttachmentsTable.fileUrl],
                        fileType = attachment[ComplaintAttachmentsTable.fileType],
                        publicId = attachment[ComplaintAttachmentsTable.cloudinaryPublicId],
                        createdAt = attachment[ComplaintAttachmentsTable.createdAt].toString()
                    )
                }
                .groupBy({ it.first }, { it.second })
        }

        val messagesByComplaintId = if (!includeMessages || complaintIds.isEmpty()) {
            emptyMap()
        } else {
            val messageRows = ComplaintMessagesTable.selectAll()
                .where { ComplaintMessagesTable.complaintId inList complaintIds }
                .orderBy(ComplaintMessagesTable.createdAt to SortOrder.ASC)
                .toList()
                .filter { includeInternalMessages || !it[ComplaintMessagesTable.isInternal] }
            val senderIds = messageRows.map { it[ComplaintMessagesTable.senderUserId] }.distinct()
            val senderNames = if (senderIds.isEmpty()) emptyMap() else UsersTable.selectAll()
                .where { UsersTable.id inList senderIds }
                .associate { it[UsersTable.id] to it[UsersTable.fullName] }
            messageRows
                .map { row -> row[ComplaintMessagesTable.complaintId] to row.toMessageDto(senderNames[row[ComplaintMessagesTable.senderUserId]]) }
                .groupBy({ it.first }, { it.second })
        }

        val eventsByComplaintId = if (!includeMessages || complaintIds.isEmpty()) {
            emptyMap()
        } else {
            val eventRows = ComplaintEventsTable.selectAll()
                .where { ComplaintEventsTable.complaintId inList complaintIds }
                .orderBy(ComplaintEventsTable.createdAt to SortOrder.ASC)
                .toList()
                .filter { includeInternalMessages || it[ComplaintEventsTable.eventType] != "INTERNAL_NOTE" }
            val actorIds = eventRows.mapNotNull { it[ComplaintEventsTable.actorUserId] }.distinct()
            val actorNames = if (actorIds.isEmpty()) emptyMap() else UsersTable.selectAll()
                .where { UsersTable.id inList actorIds }
                .associate { it[UsersTable.id] to it[UsersTable.fullName] }
            eventRows
                .map { row -> row[ComplaintEventsTable.complaintId] to row.toEventDto(actorNames[row[ComplaintEventsTable.actorUserId]]) }
                .groupBy({ it.first }, { it.second })
        }

        val userIds = rows.map { it[OrderComplaintsTable.userId] }.distinct()
        val userNames = if (userIds.isEmpty()) emptyMap() else UsersTable.selectAll()
            .where { UsersTable.id inList userIds }
            .associate { it[UsersTable.id] to it[UsersTable.fullName] }

        val productIds = rows.mapNotNull { it[OrderComplaintsTable.productId] }.distinct()
        val productNames = if (productIds.isEmpty()) emptyMap() else ProductsTable.selectAll()
            .where { ProductsTable.id inList productIds }
            .associate { it[ProductsTable.id] to it[ProductsTable.name] }

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
                attachments = attachmentsByComplaintId[id].orEmpty(),
                messages = messagesByComplaintId[id].orEmpty(),
                events = eventsByComplaintId[id].orEmpty()
            )
        }
    }

    private fun markOrderPaymentRefunded(
        orderId: String,
        refundAmount: BigDecimal?,
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
        ComplaintEventsTable.insert {
            it[id] = UUID.randomUUID().toString()
            it[ComplaintEventsTable.complaintId] = complaintId
            it[ComplaintEventsTable.actorUserId] = actorUserId
            it[ComplaintEventsTable.actorRole] = actorRole
            it[ComplaintEventsTable.eventType] = eventType
            it[ComplaintEventsTable.title] = title
            it[ComplaintEventsTable.description] = description?.take(500)
            it[ComplaintEventsTable.fromStatus] = fromStatus
            it[ComplaintEventsTable.toStatus] = toStatus
            it[ComplaintEventsTable.fromPriority] = fromPriority
            it[ComplaintEventsTable.toPriority] = toPriority
            it[ComplaintEventsTable.dueAt] = dueAt
            it[ComplaintEventsTable.createdAt] = createdAt
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

    private fun ResultRow.toMessageDto(senderName: String?): ComplaintMessageDto {
        return ComplaintMessageDto(
            id = this[ComplaintMessagesTable.id],
            senderUserId = this[ComplaintMessagesTable.senderUserId],
            senderName = senderName,
            senderRole = this[ComplaintMessagesTable.senderRole],
            message = this[ComplaintMessagesTable.message],
            isInternal = this[ComplaintMessagesTable.isInternal],
            createdAt = this[ComplaintMessagesTable.createdAt].toString()
        )
    }

    private fun ResultRow.toEventDto(actorName: String?): ComplaintEventDto {
        return ComplaintEventDto(
            id = this[ComplaintEventsTable.id],
            actorUserId = this[ComplaintEventsTable.actorUserId],
            actorName = actorName,
            actorRole = this[ComplaintEventsTable.actorRole],
            eventType = this[ComplaintEventsTable.eventType],
            title = this[ComplaintEventsTable.title],
            description = this[ComplaintEventsTable.description],
            fromStatus = this[ComplaintEventsTable.fromStatus],
            toStatus = this[ComplaintEventsTable.toStatus],
            fromPriority = this[ComplaintEventsTable.fromPriority],
            toPriority = this[ComplaintEventsTable.toPriority],
            dueAt = this[ComplaintEventsTable.dueAt]?.toString(),
            createdAt = this[ComplaintEventsTable.createdAt].toString()
        )
    }
}
