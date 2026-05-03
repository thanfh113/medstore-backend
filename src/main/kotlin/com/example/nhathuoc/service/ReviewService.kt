package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.OrderItemsTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.ProductsTable
import com.example.nhathuoc.database.tables.ReviewAttachmentsTable
import com.example.nhathuoc.database.tables.ReviewReportsTable
import com.example.nhathuoc.database.tables.ReviewsTable
import com.example.nhathuoc.database.tables.UsersTable
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

@Serializable
data class ReviewAttachmentInput(
    val fileUrl: String,
    val fileType: String = "IMAGE",
    val publicId: String? = null,
    val sortOrder: Int = 0
)

@Serializable
data class CreateReviewRequest(
    val orderId: String? = null,
    val orderItemId: String? = null,
    val rating: Int,
    val title: String? = null,
    val comment: String? = null,
    val attachments: List<ReviewAttachmentInput> = emptyList()
)

@Serializable
data class ReportReviewRequest(
    val reason: String,
    val note: String? = null
)

@Serializable
data class UpdateReviewReportRequest(
    val status: String,
    val reviewStatus: String? = null,
    val hiddenReason: String? = null
)

@Serializable
data class ModerateReviewRequest(
    val status: String,
    val hiddenReason: String? = null
)

@Serializable
data class ReviewAttachmentDto(
    val id: String,
    val fileUrl: String,
    val fileType: String,
    val publicId: String? = null,
    val sortOrder: Int,
    val createdAt: String
)

@Serializable
data class ReviewDto(
    val id: String,
    val productId: String,
    val userId: String,
    val userName: String? = null,
    val orderId: String? = null,
    val orderItemId: String? = null,
    val rating: Int,
    val title: String? = null,
    val comment: String? = null,
    val status: String,
    val isVerifiedPurchase: Boolean,
    val helpfulCount: Int,
    val reportCount: Int,
    val hiddenReason: String? = null,
    val moderatedBy: String? = null,
    val moderatedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val attachments: List<ReviewAttachmentDto> = emptyList()
)

@Serializable
data class ReviewReportDto(
    val id: String,
    val reviewId: String,
    val reporterUserId: String,
    val reporterName: String? = null,
    val reason: String,
    val note: String? = null,
    val status: String,
    val handledBy: String? = null,
    val handledAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val review: ReviewDto? = null
)

@Serializable
data class ProductReviewSummaryDto(
    val productId: String,
    val averageRating: Double,
    val totalReviews: Int,
    val ratingCounts: Map<Int, Int>
)

@Serializable
data class ProductReviewsResponse(
    val summary: ProductReviewSummaryDto,
    val reviews: List<ReviewDto>
)

class ReviewService {
    private val notificationService = NotificationService()

    private val visibleStatuses = setOf("VISIBLE")
    private val moderationStatuses = setOf("VISIBLE", "HIDDEN", "REMOVED")
    private val reportReasons = setOf("SPAM", "OFFENSIVE", "FAKE", "IRRELEVANT", "OTHER")
    private val reportStatuses = setOf("OPEN", "RESOLVED", "REJECTED")

    fun listProductReviews(productId: String, includeHidden: Boolean = false): ProductReviewsResponse = transaction {
        val statusFilter = if (includeHidden) moderationStatuses else visibleStatuses
        val reviews = ReviewsTable
            .selectAll()
            .where {
                (ReviewsTable.productId eq productId) and
                    ReviewsTable.status.inList(statusFilter.toList()) and
                    ReviewsTable.deletedAt.isNull()
            }
            .orderBy(ReviewsTable.createdAt to SortOrder.DESC)
            .toList()

        val dtos = mapReviewRows(reviews)
        val visibleReviews = dtos.filter { it.status == "VISIBLE" }
        val counts = (1..5).associateWith { rating ->
            visibleReviews.count { it.rating == rating }
        }
        val average = if (visibleReviews.isEmpty()) {
            0.0
        } else {
            visibleReviews.map { it.rating }.average()
        }

        ProductReviewsResponse(
            summary = ProductReviewSummaryDto(
                productId = productId,
                averageRating = average,
                totalReviews = visibleReviews.size,
                ratingCounts = counts
            ),
            reviews = dtos
        )
    }

    fun createReview(productId: String, userId: String, request: CreateReviewRequest): ReviewDto = transaction {
        require(request.rating in 1..5) { "Rating must be from 1 to 5" }

        val productExists = ProductsTable.selectAll()
            .where { ProductsTable.id eq productId }
            .count() > 0
        require(productExists) { "Product not found" }

        val verifiedOrderItem = resolvePurchasedOrderItem(productId, userId, request.orderId, request.orderItemId)
            ?: throw IllegalArgumentException("Only paid or delivered purchased products can be reviewed")

        val orderId = verifiedOrderItem[OrderItemsTable.orderId]
        val orderItemId = verifiedOrderItem[OrderItemsTable.id]

        val exists = ReviewsTable.selectAll()
            .where {
                (ReviewsTable.userId eq userId) and
                    (ReviewsTable.orderId eq orderId) and
                    (ReviewsTable.productId eq productId) and
                    ReviewsTable.deletedAt.isNull()
            }
            .count() > 0
        require(!exists) { "This order item has already been reviewed" }

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val reviewId = UUID.randomUUID().toString()
        ReviewsTable.insert {
            it[id] = reviewId
            it[ReviewsTable.productId] = productId
            it[ReviewsTable.userId] = userId
            it[ReviewsTable.orderId] = orderId
            it[ReviewsTable.orderItemId] = orderItemId
            it[rating] = request.rating
            it[title] = request.title?.trim()?.ifBlank { null }
            it[comment] = request.comment?.trim()?.ifBlank { null }
            it[status] = "VISIBLE"
            it[isVerifiedPurchase] = true
            it[createdAt] = now
            it[updatedAt] = now
        }

        request.attachments
            .filter { it.fileUrl.isNotBlank() }
            .forEach { attachment ->
                ReviewAttachmentsTable.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[ReviewAttachmentsTable.reviewId] = reviewId
                    it[fileUrl] = attachment.fileUrl
                    it[fileType] = attachment.fileType.ifBlank { "IMAGE" }
                    it[cloudinaryPublicId] = attachment.publicId?.ifBlank { null }
                    it[sortOrder] = attachment.sortOrder
                    it[createdAt] = now
                }
            }

        mapReviewRows(
            ReviewsTable.selectAll().where { ReviewsTable.id eq reviewId }.toList()
        ).single()
    }

    fun reportReview(reviewId: String, reporterUserId: String, request: ReportReviewRequest): String = transaction {
        val normalizedReason = request.reason.trim().uppercase().ifBlank { "OTHER" }
        require(normalizedReason in reportReasons) {
            "Reason must be one of: ${reportReasons.joinToString(", ")}"
        }

        val reviewExists = ReviewsTable.selectAll()
            .where { (ReviewsTable.id eq reviewId) and ReviewsTable.deletedAt.isNull() }
            .count() > 0
        require(reviewExists) { "Review not found" }

        val alreadyReported = ReviewReportsTable.selectAll()
            .where {
                (ReviewReportsTable.reviewId eq reviewId) and
                    (ReviewReportsTable.reporterUserId eq reporterUserId)
            }
            .count() > 0
        require(!alreadyReported) { "Review already reported by this user" }

        val reportId = UUID.randomUUID().toString()
        ReviewReportsTable.insert {
            it[id] = reportId
            it[ReviewReportsTable.reviewId] = reviewId
            it[ReviewReportsTable.reporterUserId] = reporterUserId
            it[reason] = normalizedReason
            it[note] = request.note?.trim()?.ifBlank { null }
            it[status] = "OPEN"
        }
        ReviewsTable.update({ ReviewsTable.id eq reviewId }) {
            with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                it[ReviewsTable.reportCount] = ReviewsTable.reportCount + 1
            }
        }
        reportId
    }

    fun listInternalReviews(status: String? = null, productId: String? = null, limit: Int = 100): List<ReviewDto> = transaction {
        var query = ReviewsTable.selectAll()
            .where { ReviewsTable.deletedAt.isNull() }

        status?.trim()?.uppercase()?.ifBlank { null }?.let { normalizedStatus ->
            query = query.andWhere { ReviewsTable.status eq normalizedStatus }
        }
        productId?.trim()?.ifBlank { null }?.let { normalizedProductId ->
            query = query.andWhere { ReviewsTable.productId eq normalizedProductId }
        }

        mapReviewRows(
            query.orderBy(ReviewsTable.createdAt to SortOrder.DESC)
                .limit(limit.coerceIn(1, 500))
                .toList()
        )
    }

    fun moderateReview(reviewId: String, moderatorUserId: String, request: ModerateReviewRequest): ReviewDto = transaction {
        val normalizedStatus = request.status.trim().uppercase()
        require(normalizedStatus in moderationStatuses) {
            "Status must be one of: ${moderationStatuses.joinToString(", ")}"
        }

        val existing = ReviewsTable.selectAll()
            .where { (ReviewsTable.id eq reviewId) and ReviewsTable.deletedAt.isNull() }
            .singleOrNull()
            ?: throw IllegalArgumentException("Review not found")

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val updated = ReviewsTable.update({ (ReviewsTable.id eq reviewId) and ReviewsTable.deletedAt.isNull() }) {
            it[status] = normalizedStatus
            it[hiddenReason] = request.hiddenReason?.trim()?.ifBlank { null }
            it[moderatedBy] = moderatorUserId
            it[moderatedAt] = now
            it[updatedAt] = now
        }
        require(updated > 0) { "Review not found" }

        notifyReviewModeratedIfNeeded(
            userId = existing[ReviewsTable.userId],
            productId = existing[ReviewsTable.productId],
            previousStatus = existing[ReviewsTable.status],
            nextStatus = normalizedStatus,
            hiddenReason = request.hiddenReason
        )

        mapReviewRows(
            ReviewsTable.selectAll().where { ReviewsTable.id eq reviewId }.toList()
        ).single()
    }

    fun listInternalReviewReports(
        status: String? = null,
        reviewId: String? = null,
        limit: Int = 100
    ): List<ReviewReportDto> = transaction {
        var query = ReviewReportsTable.selectAll()
        status?.trim()?.uppercase()?.ifBlank { null }?.let { normalizedStatus ->
            query = query.where { ReviewReportsTable.status eq normalizedStatus }
        }
        reviewId?.trim()?.ifBlank { null }?.let { normalizedReviewId ->
            query = query.andWhere { ReviewReportsTable.reviewId eq normalizedReviewId }
        }

        mapReportRows(
            query.orderBy(ReviewReportsTable.createdAt to SortOrder.DESC)
                .limit(limit.coerceIn(1, 500))
                .toList()
        )
    }

    fun updateReviewReport(
        reportId: String,
        moderatorUserId: String,
        request: UpdateReviewReportRequest
    ): ReviewReportDto = transaction {
        val normalizedStatus = request.status.trim().uppercase()
        require(normalizedStatus in reportStatuses) {
            "Report status must be one of: ${reportStatuses.joinToString(", ")}"
        }

        val report = ReviewReportsTable.selectAll()
            .where { ReviewReportsTable.id eq reportId }
            .singleOrNull()
            ?: throw IllegalArgumentException("Review report not found")
        val review = ReviewsTable.selectAll()
            .where { (ReviewsTable.id eq report[ReviewReportsTable.reviewId]) and ReviewsTable.deletedAt.isNull() }
            .singleOrNull()

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        request.reviewStatus?.trim()?.uppercase()?.ifBlank { null }?.let { nextReviewStatus ->
            require(nextReviewStatus in moderationStatuses) {
                "Review status must be one of: ${moderationStatuses.joinToString(", ")}"
            }
            val updatedReview = ReviewsTable.update({
                (ReviewsTable.id eq report[ReviewReportsTable.reviewId]) and ReviewsTable.deletedAt.isNull()
            }) {
                it[status] = nextReviewStatus
                it[hiddenReason] = request.hiddenReason?.trim()?.ifBlank { null }
                it[moderatedBy] = moderatorUserId
                it[moderatedAt] = now
                it[updatedAt] = now
            }
            require(updatedReview > 0) { "Review not found" }
            review?.let {
                notifyReviewModeratedIfNeeded(
                    userId = it[ReviewsTable.userId],
                    productId = it[ReviewsTable.productId],
                    previousStatus = it[ReviewsTable.status],
                    nextStatus = nextReviewStatus,
                    hiddenReason = request.hiddenReason
                )
            }
        }

        ReviewReportsTable.update({ ReviewReportsTable.id eq reportId }) {
            it[status] = normalizedStatus
            it[handledBy] = moderatorUserId
            it[handledAt] = now
            it[updatedAt] = now
        }

        mapReportRows(
            ReviewReportsTable.selectAll().where { ReviewReportsTable.id eq reportId }.toList()
        ).single()
    }

    private fun notifyReviewModeratedIfNeeded(
        userId: String,
        productId: String,
        previousStatus: String,
        nextStatus: String,
        hiddenReason: String?
    ) {
        if (nextStatus !in setOf("HIDDEN", "REMOVED") || previousStatus == nextStatus) return
        notificationService.createUserNotification(
            userId = userId,
            title = if (nextStatus == "REMOVED") "Đánh giá của bạn đã bị gỡ" else "Đánh giá của bạn đã bị ẩn",
            body = hiddenReason?.trim()?.ifBlank { null } ?: "Đánh giá không còn hiển thị công khai.",
            type = "REVIEW",
            refId = productId
        )
    }

    private fun resolvePurchasedOrderItem(
        productId: String,
        userId: String,
        requestedOrderId: String?,
        requestedOrderItemId: String?
    ): ResultRow? {
        var query = (OrderItemsTable innerJoin OrdersTable)
            .selectAll()
            .where {
                (OrderItemsTable.productId eq productId) and
                    (OrdersTable.userId eq userId) and
                    ((OrdersTable.status eq "DELIVERED") or (OrdersTable.paymentStatus eq "COMPLETED"))
            }

        requestedOrderId?.trim()?.ifBlank { null }?.let { orderId ->
            query = query.andWhere { OrdersTable.id eq orderId }
        }
        requestedOrderItemId?.trim()?.ifBlank { null }?.let { orderItemId ->
            query = query.andWhere { OrderItemsTable.id eq orderItemId }
        }

        return query.orderBy(OrdersTable.createdAt to SortOrder.DESC).firstOrNull()
    }

    private fun mapReviewRows(rows: List<ResultRow>): List<ReviewDto> {
        val reviewIds = rows.map { it[ReviewsTable.id] }
        val attachmentsByReviewId = if (reviewIds.isEmpty()) {
            emptyMap()
        } else {
            ReviewAttachmentsTable.selectAll()
                .where { ReviewAttachmentsTable.reviewId inList reviewIds }
                .orderBy(ReviewAttachmentsTable.sortOrder to SortOrder.ASC)
                .map { attachment ->
                    attachment[ReviewAttachmentsTable.reviewId] to ReviewAttachmentDto(
                        id = attachment[ReviewAttachmentsTable.id],
                        fileUrl = attachment[ReviewAttachmentsTable.fileUrl],
                        fileType = attachment[ReviewAttachmentsTable.fileType],
                        publicId = attachment[ReviewAttachmentsTable.cloudinaryPublicId],
                        sortOrder = attachment[ReviewAttachmentsTable.sortOrder],
                        createdAt = attachment[ReviewAttachmentsTable.createdAt].toString()
                    )
                }
                .groupBy({ it.first }, { it.second })
        }

        val userIds = rows.map { it[ReviewsTable.userId] }.distinct()
        val userNames = if (userIds.isEmpty()) {
            emptyMap()
        } else {
            UsersTable.selectAll()
                .where { UsersTable.id inList userIds }
                .associate { it[UsersTable.id] to it[UsersTable.fullName] }
        }

        return rows.map { row ->
            row.toReviewDto(
                userName = userNames[row[ReviewsTable.userId]],
                attachments = attachmentsByReviewId[row[ReviewsTable.id]].orEmpty()
            )
        }
    }

    private fun mapReportRows(rows: List<ResultRow>): List<ReviewReportDto> {
        if (rows.isEmpty()) return emptyList()

        val reviewIds = rows.map { it[ReviewReportsTable.reviewId] }.distinct()
        val reviewsById = mapReviewRows(
            ReviewsTable.selectAll()
                .where { ReviewsTable.id inList reviewIds }
                .toList()
        ).associateBy { it.id }

        val reporterIds = rows.map { it[ReviewReportsTable.reporterUserId] }.distinct()
        val reporterNames = UsersTable.selectAll()
            .where { UsersTable.id inList reporterIds }
            .associate { it[UsersTable.id] to it[UsersTable.fullName] }

        return rows.map { row ->
            ReviewReportDto(
                id = row[ReviewReportsTable.id],
                reviewId = row[ReviewReportsTable.reviewId],
                reporterUserId = row[ReviewReportsTable.reporterUserId],
                reporterName = reporterNames[row[ReviewReportsTable.reporterUserId]],
                reason = row[ReviewReportsTable.reason],
                note = row[ReviewReportsTable.note],
                status = row[ReviewReportsTable.status],
                handledBy = row[ReviewReportsTable.handledBy],
                handledAt = row[ReviewReportsTable.handledAt]?.toString(),
                createdAt = row[ReviewReportsTable.createdAt].toString(),
                updatedAt = row[ReviewReportsTable.updatedAt].toString(),
                review = reviewsById[row[ReviewReportsTable.reviewId]]
            )
        }
    }

    private fun ResultRow.toReviewDto(
        userName: String?,
        attachments: List<ReviewAttachmentDto>
    ): ReviewDto {
        return ReviewDto(
            id = this[ReviewsTable.id],
            productId = this[ReviewsTable.productId],
            userId = this[ReviewsTable.userId],
            userName = userName,
            orderId = this[ReviewsTable.orderId],
            orderItemId = this[ReviewsTable.orderItemId],
            rating = this[ReviewsTable.rating],
            title = this[ReviewsTable.title],
            comment = this[ReviewsTable.comment],
            status = this[ReviewsTable.status],
            isVerifiedPurchase = this[ReviewsTable.isVerifiedPurchase],
            helpfulCount = this[ReviewsTable.helpfulCount],
            reportCount = this[ReviewsTable.reportCount],
            hiddenReason = this[ReviewsTable.hiddenReason],
            moderatedBy = this[ReviewsTable.moderatedBy],
            moderatedAt = this[ReviewsTable.moderatedAt]?.toString(),
            createdAt = this[ReviewsTable.createdAt].toString(),
            updatedAt = this[ReviewsTable.updatedAt].toString(),
            attachments = attachments
        )
    }
}
