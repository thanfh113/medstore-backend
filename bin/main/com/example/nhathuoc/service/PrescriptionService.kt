package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

// ─────────────────────────────────────────────────────────────
// DTOs
// ─────────────────────────────────────────────────────────────

data class PrescriptionDto(
    val id: String,
    val userId: String,
    val orderId: String?,
    val imageUrl: String,
    val note: String?,
    val status: String, // PENDING | APPROVED | REJECTED | PROCESSED
    val cloudinaryPublicId: String?,
    val createdAt: LocalDateTime,
    // Additional info if linked to order
    val orderCode: String? = null,
    val orderStatus: String? = null
)

data class UploadPrescriptionRequest(
    val imageUrl: String,
    val note: String? = null,
    val cloudinaryPublicId: String? = null
)

data class LinkPrescriptionToOrderRequest(
    val orderId: String
)

data class UpdatePrescriptionStatusRequest(
    val status: String, // PENDING | APPROVED | REJECTED | PROCESSED
    val adminNote: String? = null
)

data class PrescriptionSummaryDto(
    val totalPrescriptions: Int,
    val pendingCount: Int,
    val approvedCount: Int,
    val rejectedCount: Int,
    val processedCount: Int,
    val recentPrescriptions: List<PrescriptionDto>
)

// ─────────────────────────────────────────────────────────────
// SERVICE
// ─────────────────────────────────────────────────────────────

class PrescriptionService {

    /**
     * Upload new prescription
     */
    fun uploadPrescription(userId: String, request: UploadPrescriptionRequest): String {
        return transaction {
            // Validate user exists
            val userExists = UsersTable
                .selectAll()
                .where { UsersTable.id eq userId }
                .singleOrNull()
                ?: throw IllegalArgumentException("User not found")

            if (request.imageUrl.isBlank()) {
                throw IllegalArgumentException("Image URL is required")
            }

            // Basic URL validation
            if (!request.imageUrl.startsWith("http")) {
                throw IllegalArgumentException("Invalid image URL format")
            }

            val prescriptionId = UUID.randomUUID().toString()

            PrescriptionsTable.insert {
                it[PrescriptionsTable.id] = prescriptionId
                it[PrescriptionsTable.userId] = userId
                it[PrescriptionsTable.orderId] = null // Initially not linked to any order
                it[PrescriptionsTable.imageUrl] = request.imageUrl
                it[PrescriptionsTable.note] = request.note
                it[PrescriptionsTable.status] = "PENDING"
                it[PrescriptionsTable.cloudinaryPublicId] = request.cloudinaryPublicId
            }

            prescriptionId
        }
    }

    /**
     * Get user's prescriptions
     */
    fun getUserPrescriptions(userId: String): List<PrescriptionDto> {
        return transaction {
            val query = """
                SELECT p.*, o.order_code, o.status as order_status
                FROM prescriptions p
                LEFT JOIN orders o ON p.order_id = o.id
                WHERE p.user_id = ?
                ORDER BY p.created_at DESC
            """.trimIndent()

            // Using raw SQL for left join with order details
            PrescriptionsTable
                .selectAll()
                .where { PrescriptionsTable.userId eq userId }
                .orderBy(PrescriptionsTable.createdAt to SortOrder.DESC)
                .map { row ->
                    // Get order info if linked
                    val orderId = row[PrescriptionsTable.orderId]
                    var orderCode: String? = null
                    var orderStatus: String? = null

                    if (orderId != null) {
                        val orderInfo = OrdersTable
                            .selectAll()
                            .where { OrdersTable.id eq orderId }
                            .singleOrNull()

                        if (orderInfo != null) {
                            orderCode = orderInfo[OrdersTable.orderCode]
                            orderStatus = orderInfo[OrdersTable.status]
                        }
                    }

                    PrescriptionDto(
                        id = row[PrescriptionsTable.id],
                        userId = row[PrescriptionsTable.userId],
                        orderId = row[PrescriptionsTable.orderId],
                        imageUrl = row[PrescriptionsTable.imageUrl],
                        note = row[PrescriptionsTable.note],
                        status = row[PrescriptionsTable.status],
                        cloudinaryPublicId = row[PrescriptionsTable.cloudinaryPublicId],
                        createdAt = row[PrescriptionsTable.createdAt],
                        orderCode = orderCode,
                        orderStatus = orderStatus
                    )
                }
        }
    }

    /**
     * Get prescription by ID
     */
    fun getPrescriptionById(userId: String, prescriptionId: String): PrescriptionDto? {
        return transaction {
            PrescriptionsTable
                .selectAll()
                .where {
                    (PrescriptionsTable.id eq prescriptionId) and
                    (PrescriptionsTable.userId eq userId)
                }
                .singleOrNull()
                ?.let { row ->
                    // Get order info if linked
                    val orderId = row[PrescriptionsTable.orderId]
                    var orderCode: String? = null
                    var orderStatus: String? = null

                    if (orderId != null) {
                        val orderInfo = OrdersTable
                            .selectAll()
                            .where { OrdersTable.id eq orderId }
                            .singleOrNull()

                        if (orderInfo != null) {
                            orderCode = orderInfo[OrdersTable.orderCode]
                            orderStatus = orderInfo[OrdersTable.status]
                        }
                    }

                    PrescriptionDto(
                        id = row[PrescriptionsTable.id],
                        userId = row[PrescriptionsTable.userId],
                        orderId = row[PrescriptionsTable.orderId],
                        imageUrl = row[PrescriptionsTable.imageUrl],
                        note = row[PrescriptionsTable.note],
                        status = row[PrescriptionsTable.status],
                        cloudinaryPublicId = row[PrescriptionsTable.cloudinaryPublicId],
                        createdAt = row[PrescriptionsTable.createdAt],
                        orderCode = orderCode,
                        orderStatus = orderStatus
                    )
                }
        }
    }

    /**
     * Link prescription to order
     */
    fun linkPrescriptionToOrder(userId: String, prescriptionId: String, request: LinkPrescriptionToOrderRequest) {
        transaction {
            // Verify prescription belongs to user
            val prescription = PrescriptionsTable
                .selectAll()
                .where {
                    (PrescriptionsTable.id eq prescriptionId) and
                    (PrescriptionsTable.userId eq userId)
                }
                .singleOrNull()
                ?: throw IllegalArgumentException("Prescription not found")

            // Verify order exists and belongs to user
            val order = OrdersTable
                .selectAll()
                .where {
                    (OrdersTable.id eq request.orderId) and
                    (OrdersTable.userId eq userId)
                }
                .singleOrNull()
                ?: throw IllegalArgumentException("Order not found")

            // Update prescription with order link
            PrescriptionsTable.update({ PrescriptionsTable.id eq prescriptionId }) {
                it[PrescriptionsTable.orderId] = request.orderId
            }
        }
    }

    /**
     * Update prescription status (admin only)
     */
    fun updatePrescriptionStatus(prescriptionId: String, request: UpdatePrescriptionStatusRequest): Boolean {
        return transaction {
            val validStatuses = listOf("PENDING", "APPROVED", "REJECTED", "PROCESSED")
            if (request.status !in validStatuses) {
                throw IllegalArgumentException("Invalid status. Must be one of: ${validStatuses.joinToString(", ")}")
            }

            // Verify prescription exists
            val exists = PrescriptionsTable
                .selectAll()
                .where { PrescriptionsTable.id eq prescriptionId }
                .singleOrNull()
                ?: throw IllegalArgumentException("Prescription not found")

            val updatedRows = PrescriptionsTable.update({ PrescriptionsTable.id eq prescriptionId }) {
                it[PrescriptionsTable.status] = request.status
                // Note: We could add an admin_note column later if needed
            }

            updatedRows > 0
        }
    }

    /**
     * Get prescriptions by status
     */
    fun getPrescriptionsByStatus(status: String): List<PrescriptionDto> {
        return transaction {
            val validStatuses = listOf("PENDING", "APPROVED", "REJECTED", "PROCESSED")
            if (status !in validStatuses) {
                throw IllegalArgumentException("Invalid status. Must be one of: ${validStatuses.joinToString(", ")}")
            }

            (PrescriptionsTable leftJoin OrdersTable)
                .selectAll()
                .where { PrescriptionsTable.status eq status }
                .orderBy(PrescriptionsTable.createdAt to SortOrder.DESC)
                .map { row ->
                    PrescriptionDto(
                        id = row[PrescriptionsTable.id],
                        userId = row[PrescriptionsTable.userId],
                        orderId = row[PrescriptionsTable.orderId],
                        imageUrl = row[PrescriptionsTable.imageUrl],
                        note = row[PrescriptionsTable.note],
                        status = row[PrescriptionsTable.status],
                        cloudinaryPublicId = row[PrescriptionsTable.cloudinaryPublicId],
                        createdAt = row[PrescriptionsTable.createdAt],
                        orderCode = row.getOrNull(OrdersTable.orderCode),
                        orderStatus = row.getOrNull(OrdersTable.status)
                    )
                }
        }
    }

    /**
     * Delete prescription
     */
    fun deletePrescription(userId: String, prescriptionId: String): Boolean {
        return transaction {
            val deletedRows = PrescriptionsTable.deleteWhere {
                (PrescriptionsTable.id eq prescriptionId) and
                (PrescriptionsTable.userId eq userId) and
                (PrescriptionsTable.status eq "PENDING") // Only allow deletion of pending prescriptions
            }

            deletedRows > 0
        }
    }

    /**
     * Get prescription summary for user
     */
    fun getPrescriptionSummary(userId: String): PrescriptionSummaryDto {
        return transaction {
            val allPrescriptions = PrescriptionsTable
                .selectAll()
                .where { PrescriptionsTable.userId eq userId }
                .toList()

            val totalCount = allPrescriptions.size
            val pendingCount = allPrescriptions.count { it[PrescriptionsTable.status] == "PENDING" }
            val approvedCount = allPrescriptions.count { it[PrescriptionsTable.status] == "APPROVED" }
            val rejectedCount = allPrescriptions.count { it[PrescriptionsTable.status] == "REJECTED" }
            val processedCount = allPrescriptions.count { it[PrescriptionsTable.status] == "PROCESSED" }

            val recentPrescriptions = getUserPrescriptions(userId).take(5)

            PrescriptionSummaryDto(
                totalPrescriptions = totalCount,
                pendingCount = pendingCount,
                approvedCount = approvedCount,
                rejectedCount = rejectedCount,
                processedCount = processedCount,
                recentPrescriptions = recentPrescriptions
            )
        }
    }

    /**
     * Get all prescriptions (admin only)
     */
    fun getAllPrescriptions(
        status: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<PrescriptionDto> {
        return transaction {
            var query = (PrescriptionsTable leftJoin OrdersTable).selectAll()

            if (status != null) {
                val validStatuses = listOf("PENDING", "APPROVED", "REJECTED", "PROCESSED")
                if (status in validStatuses) {
                    query = query.where { PrescriptionsTable.status eq status }
                }
            }

            query
                .orderBy(PrescriptionsTable.createdAt to SortOrder.DESC)
                .limit(limit, offset.toLong())
                .map { row ->
                    PrescriptionDto(
                        id = row[PrescriptionsTable.id],
                        userId = row[PrescriptionsTable.userId],
                        orderId = row[PrescriptionsTable.orderId],
                        imageUrl = row[PrescriptionsTable.imageUrl],
                        note = row[PrescriptionsTable.note],
                        status = row[PrescriptionsTable.status],
                        cloudinaryPublicId = row[PrescriptionsTable.cloudinaryPublicId],
                        createdAt = row[PrescriptionsTable.createdAt],
                        orderCode = row.getOrNull(OrdersTable.orderCode),
                        orderStatus = row.getOrNull(OrdersTable.status)
                    )
                }
        }
    }

    /**
     * Search prescriptions by user phone/email (admin only)
     */
    fun searchPrescriptionsByUser(searchQuery: String): List<PrescriptionDto> {
        return transaction {
            val userIds = UsersTable
                .selectAll()
                .where {
                    (UsersTable.phone like "%$searchQuery%") or
                    (UsersTable.email like "%$searchQuery%") or
                    (UsersTable.fullName like "%$searchQuery%")
                }
                .map { it[UsersTable.id] }

            if (userIds.isEmpty()) {
                emptyList()
            } else {
                (PrescriptionsTable leftJoin OrdersTable)
                    .selectAll()
                    .where { PrescriptionsTable.userId inList userIds }
                    .orderBy(PrescriptionsTable.createdAt to SortOrder.DESC)
                    .limit(50)
                    .map { row ->
                        PrescriptionDto(
                            id = row[PrescriptionsTable.id],
                            userId = row[PrescriptionsTable.userId],
                            orderId = row[PrescriptionsTable.orderId],
                            imageUrl = row[PrescriptionsTable.imageUrl],
                            note = row[PrescriptionsTable.note],
                            status = row[PrescriptionsTable.status],
                            cloudinaryPublicId = row[PrescriptionsTable.cloudinaryPublicId],
                            createdAt = row[PrescriptionsTable.createdAt],
                            orderCode = row.getOrNull(OrdersTable.orderCode),
                            orderStatus = row.getOrNull(OrdersTable.status)
                        )
                    }
            }
        }
    }
}