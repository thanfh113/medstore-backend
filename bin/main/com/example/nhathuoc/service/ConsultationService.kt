package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

// ─────────────────────────────────────────────────────────
// DATA MODELS
// ─────────────────────────────────────────────────────────

@Serializable
data class ConsultationDto(
    val id: String,
    val consultationCode: String,
    val doctorId: String,
    val doctorName: String,
    val userId: String,
    val scheduledAt: String,
    val status: String,  // SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED
    val sessionType: String,  // VIDEO, VOICE, TEXT
    val videoCallUrl: String?,
    val fee: Long,  // in VND
    val paymentStatus: String,  // UNPAID, PAID
    val createdAt: String
)

@Serializable
data class ConsultationBookingRequest(
    val doctorId: String,
    val scheduledAt: String,  // ISO format: 2025-04-15T14:30:00
    val sessionType: String,  // VIDEO, VOICE, TEXT
    val reason: String?  // Optional reason for consultation
)

@Serializable
data class ConsultationMessageDto(
    val id: String,
    val consultationId: String,
    val senderId: String,
    val senderRole: String,  // DOCTOR or PATIENT
    val messageType: String,  // TEXT, IMAGE, PRESCRIPTION
    val message: String,
    val imageUrl: String?,
    val createdAt: String
)

@Serializable
data class ConsultationRatingRequest(
    val rating: Int,  // 1-5
    val comment: String?
)

@Serializable
data class ConsultationRatingDto(
    val id: String,
    val consultationId: String,
    val userId: String,
    val doctorId: String,
    val rating: Int,
    val comment: String?,
    val createdAt: String
)

@Serializable
data class ConsultationStatusUpdateRequest(
    val status: String  // IN_PROGRESS, COMPLETED, CANCELLED
)

@Serializable
data class PaymentStatusUpdateRequest(
    val paymentStatus: String  // UNPAID, PAID
)

// ─────────────────────────────────────────────────────────
// CONSULTATION SERVICE
// ─────────────────────────────────────────────────────────

class ConsultationService {

    private val doctorService = DoctorService()

    /**
     * Book a consultation with a doctor
     * ⚠️ TIER 3 - NOT FULLY IMPLEMENTED
     * Has ORM compatibility issues with Exposed
     */
    fun bookConsultation(userId: String, request: ConsultationBookingRequest): String {
        throw NotImplementedError("Consultation booking not yet implemented. Backend service has ORM type mismatches.")
    }

    /**
     * Get consultation by ID
     */
    fun getConsultationById(consultationId: String): ConsultationDto? = transaction {
        ConsultationsTable
            .selectAll()
            .where { ConsultationsTable.id eq consultationId }
            .map { toDoctorConsultationDto(it) }
            .firstOrNull()
    }

    /**
     * Get consultation by code
     */
    fun getConsultationByCode(code: String): ConsultationDto? = transaction {
        ConsultationsTable
            .selectAll()
            .where { ConsultationsTable.consultationCode eq code }
            .map { toDoctorConsultationDto(it) }
            .firstOrNull()
    }

    /**
     * Get user's consultations
     */
    fun getUserConsultations(
        userId: String,
        status: String? = null,
        limit: Int = 20,
        offset: Int = 0
    ): List<ConsultationDto> = transaction {
        var query = ConsultationsTable.selectAll().where { ConsultationsTable.userId eq userId }

        if (!status.isNullOrEmpty()) {
            query = query.andWhere { ConsultationsTable.status eq status }
        }

        query
            .limit(limit, offset.toLong())
            .orderBy(ConsultationsTable.scheduledAt to SortOrder.DESC)
            .map { toDoctorConsultationDto(it) }
    }

    /**
     * Get doctor's consultations
     */
    fun getDoctorConsultations(
        doctorId: String,
        status: String? = null,
        limit: Int = 20,
        offset: Int = 0
    ): List<ConsultationDto> = transaction {
        var query = ConsultationsTable.selectAll().where { ConsultationsTable.doctorId eq doctorId }

        if (!status.isNullOrEmpty()) {
            query = query.andWhere { ConsultationsTable.status eq status }
        }

        query
            .limit(limit, offset.toLong())
            .orderBy(ConsultationsTable.scheduledAt to SortOrder.DESC)
            .map { toDoctorConsultationDto(it) }
    }

    /**
     * Get upcoming consultations for a user
     * ⚠️ TIER 3 - NOT FULLY IMPLEMENTED
     */
    fun getUpcomingConsultations(userId: String, limit: Int = 10): List<ConsultationDto> {
        throw NotImplementedError("Upcoming consultations not yet implemented. Backend service has ORM type mismatches.")
    }

    /**
     * Cancel consultation
     */
    fun cancelConsultation(consultationId: String, userId: String): Boolean = transaction {
        // Verify user owns this consultation
        val consultation = ConsultationsTable.selectAll().where { ConsultationsTable.id eq consultationId }.singleOrNull()
            ?: throw IllegalArgumentException("Consultation not found")

        if (consultation[ConsultationsTable.userId] != userId) {
            throw IllegalArgumentException("Unauthorized: You don't own this consultation")
        }

        if (consultation[ConsultationsTable.status] == "COMPLETED" || consultation[ConsultationsTable.status] == "CANCELLED") {
            throw IllegalArgumentException("Cannot cancel a ${consultation[ConsultationsTable.status].lowercase()} consultation")
        }

        val updated = ConsultationsTable.update({ ConsultationsTable.id eq consultationId }) {
            it[status] = "CANCELLED"
        }

        updated > 0
    }

    /**
     * Update consultation status (Doctor only)
     */
    fun updateConsultationStatus(consultationId: String, doctorId: String, request: ConsultationStatusUpdateRequest): Boolean = transaction {
        // Verify doctor owns this consultation
        val consultation = ConsultationsTable.selectAll().where { ConsultationsTable.id eq consultationId }.singleOrNull()
            ?: throw IllegalArgumentException("Consultation not found")

        if (consultation[ConsultationsTable.doctorId] != doctorId) {
            throw IllegalArgumentException("Unauthorized: You don't own this consultation")
        }

        val updated = ConsultationsTable.update({ ConsultationsTable.id eq consultationId }) {
            it[status] = request.status
        }

        updated > 0
    }

    /**
     * Update payment status for consultation
     */
    fun updatePaymentStatus(consultationId: String, status: String): Boolean = transaction {
        val updated = ConsultationsTable.update({ ConsultationsTable.id eq consultationId }) {
            it[paymentStatus] = status
        }

        updated > 0
    }

    /**
     * Set video call URL for consultation
     * ⚠️ TIER 3 - NOT FULLY IMPLEMENTED
     */
    fun setVideoCallUrl(consultationId: String, doctorId: String, videoCallUrl: String): Boolean {
        throw NotImplementedError("Video call setup not yet implemented.")
    }

    // ─────────────────────────────────────────────────────────
    // CONSULTATION MESSAGES
    // ─────────────────────────────────────────────────────────

    /**
     * Add message to consultation
     */
    fun addMessage(
        consultationId: String,
        senderId: String,
        messageType: String,
        message: String,
        imageUrl: String? = null
    ): String = transaction {
        // Verify consultation exists
        val consultation = ConsultationsTable.selectAll().where { ConsultationsTable.id eq consultationId }.singleOrNull()
            ?: throw IllegalArgumentException("Consultation not found")

        // Verify sender is either the patient or the doctor
        if (senderId != consultation[ConsultationsTable.userId] && senderId != consultation[ConsultationsTable.doctorId]) {
            throw IllegalArgumentException("Unauthorized: You're not part of this consultation")
        }

        val messageId = UUID.randomUUID().toString()

        ConsultationMessagesTable.insert {
            it[id] = messageId
            it[this.consultationId] = consultationId
            it[this.senderId] = senderId
            it[this.messageType] = messageType
            it[this.message] = message
            it[this.imageUrl] = imageUrl
        }

        messageId
    }

    /**
     * Get consultation messages
     */
    fun getConsultationMessages(
        consultationId: String,
        limit: Int = 50,
        offset: Int = 0
    ): List<ConsultationMessageDto> = transaction {
        val consultationRow = ConsultationsTable
            .selectAll()
            .where { ConsultationsTable.id eq consultationId }
            .singleOrNull()

        val doctorId = consultationRow?.get(ConsultationsTable.doctorId)

        ConsultationMessagesTable
            .selectAll()
            .where { ConsultationMessagesTable.consultationId eq consultationId }
            .limit(limit, offset.toLong())
            .orderBy(ConsultationMessagesTable.createdAt to SortOrder.ASC)
            .map { row ->
                val senderId = row[ConsultationMessagesTable.senderId]
                val senderRole = if (senderId == doctorId) "DOCTOR" else "PATIENT"

                ConsultationMessageDto(
                    id = row[ConsultationMessagesTable.id],
                    consultationId = row[ConsultationMessagesTable.consultationId],
                    senderId = senderId,
                    senderRole = senderRole,
                    messageType = row[ConsultationMessagesTable.messageType],
                    message = row[ConsultationMessagesTable.message],
                    imageUrl = row[ConsultationMessagesTable.imageUrl],
                    createdAt = row[ConsultationMessagesTable.createdAt].toString()
                )
            }
    }

    // ─────────────────────────────────────────────────────────
    // CONSULTATION RATINGS
    // ─────────────────────────────────────────────────────────

    /**
     * Rate a consultation (Patient only)
     */
    fun rateConsultation(consultationId: String, userId: String, request: ConsultationRatingRequest): String = transaction {
        // Verify consultation exists and belongs to user
        val consultation = ConsultationsTable.selectAll().where { ConsultationsTable.id eq consultationId }.singleOrNull()
            ?: throw IllegalArgumentException("Consultation not found")

        if (consultation[ConsultationsTable.userId] != userId) {
            throw IllegalArgumentException("Unauthorized: You can't rate this consultation")
        }

        if (consultation[ConsultationsTable.status] != "COMPLETED") {
            throw IllegalArgumentException("Can only rate completed consultations")
        }

        // Check if already rated
        val existingRating = ConsultationRatingsTable
            .selectAll()
            .where { ConsultationRatingsTable.consultationId eq consultationId }
            .firstOrNull()

        if (existingRating != null) {
            throw IllegalArgumentException("This consultation has already been rated")
        }

        // Validate rating
        if (request.rating < 1 || request.rating > 5) {
            throw IllegalArgumentException("Rating must be between 1 and 5")
        }

        val ratingId = UUID.randomUUID().toString()

        ConsultationRatingsTable.insert {
            it[id] = ratingId
            it[this.consultationId] = consultationId
            it[this.userId] = userId
            it[this.rating] = request.rating
            it[comment] = request.comment
        }

        // Update doctor's average rating
        doctorService.getDoctorRating(consultation[ConsultationsTable.doctorId])

        ratingId
    }

    /**
     * Get ratings for a doctor
     */
    fun getDoctorRatings(
        doctorId: String,
        limit: Int = 20,
        offset: Int = 0
    ): List<ConsultationRatingDto> = transaction {
        ConsultationRatingsTable
            .innerJoin(ConsultationsTable)
            .selectAll()
            .where { ConsultationsTable.doctorId eq doctorId }
            .limit(limit, offset.toLong())
            .orderBy(ConsultationRatingsTable.createdAt to SortOrder.DESC)
            .map { row ->
                ConsultationRatingDto(
                    id = row[ConsultationRatingsTable.id],
                    consultationId = row[ConsultationRatingsTable.consultationId],
                    userId = row[ConsultationRatingsTable.userId],
                    doctorId = doctorId,
                    rating = row[ConsultationRatingsTable.rating],
                    comment = row[ConsultationRatingsTable.comment],
                    createdAt = row[ConsultationRatingsTable.createdAt].toString()
                )
            }
    }

    /**
     * Get rating for a specific consultation
     */
    fun getConsultationRating(consultationId: String): ConsultationRatingDto? = transaction {
        val consultationRow = ConsultationsTable
            .selectAll()
            .where { ConsultationsTable.id eq consultationId }
            .singleOrNull()

        val doctorId = consultationRow?.get(ConsultationsTable.doctorId)

        ConsultationRatingsTable
            .selectAll()
            .where { ConsultationRatingsTable.consultationId eq consultationId }
            .map { row ->
                ConsultationRatingDto(
                    id = row[ConsultationRatingsTable.id],
                    consultationId = row[ConsultationRatingsTable.consultationId],
                    userId = row[ConsultationRatingsTable.userId],
                    doctorId = doctorId ?: "",
                    rating = row[ConsultationRatingsTable.rating],
                    comment = row[ConsultationRatingsTable.comment],
                    createdAt = row[ConsultationRatingsTable.createdAt].toString()
                )
            }
            .firstOrNull()
    }

    // ─────────────────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────────────────

    private fun generateConsultationCode(): String {
        val timestamp = System.currentTimeMillis().toString().takeLast(6)
        val random = (1000..9999).random()
        return "CONS-$timestamp-$random"
    }

    private fun toDoctorConsultationDto(row: ResultRow): ConsultationDto {
        val doctorId = row[ConsultationsTable.doctorId]
        val doctorName = DoctorsTable.selectAll().where { DoctorsTable.id eq doctorId }.singleOrNull()?.get(DoctorsTable.name) ?: ""

        return ConsultationDto(
            id = row[ConsultationsTable.id],
            consultationCode = row[ConsultationsTable.consultationCode],
            doctorId = doctorId,
            doctorName = doctorName,
            userId = row[ConsultationsTable.userId],
            scheduledAt = row[ConsultationsTable.scheduledAt].toString(),
            status = row[ConsultationsTable.status],
            sessionType = row[ConsultationsTable.sessionType],
            videoCallUrl = row[ConsultationsTable.videoCallUrl],
            fee = row[ConsultationsTable.fee].toLong(),
            paymentStatus = row[ConsultationsTable.paymentStatus],
            createdAt = row[ConsultationsTable.createdAt].toString()
        )
    }
}
