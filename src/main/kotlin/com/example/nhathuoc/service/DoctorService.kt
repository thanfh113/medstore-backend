package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

// ─────────────────────────────────────────────────────────
// DATA MODELS
// ─────────────────────────────────────────────────────────

@Serializable
data class DoctorDto(
    val id: String,
    val name: String,
    val specialization: String,
    val yearsOfExperience: Int,
    val bio: String?,
    val avatarUrl: String?,
    val consultationFee: Long,  // in VND
    val averageRating: Double,
    val isActive: Boolean,
    val totalConsultations: Int = 0,
    val totalRatings: Int = 0
)

@Serializable
data class DoctorAvailabilitySlot(
    val doctorId: String,
    val date: String,  // YYYY-MM-DD
    val time: String,  // HH:mm
    val isAvailable: Boolean
)

@Serializable
data class DoctorCreateRequest(
    val name: String,
    val specialization: String,
    val yearsOfExperience: Int,
    val bio: String?,
    val avatarUrl: String?,
    val consultationFee: Long  // in VND
)

@Serializable
data class DoctorUpdateRequest(
    val name: String?,
    val specialization: String?,
    val yearsOfExperience: Int?,
    val bio: String?,
    val avatarUrl: String?,
    val consultationFee: Long?,
    val isActive: Boolean?
)

// ─────────────────────────────────────────────────────────
// DOCTOR SERVICE
// ─────────────────────────────────────────────────────────

class DoctorService {

    /**
     * Get all active doctors
     */
    fun getAllDoctors(
        specialization: String? = null,
        limit: Int = 20,
        offset: Int = 0
    ): List<DoctorDto> = transaction {
        var query: Query = DoctorsTable.selectAll().where { DoctorsTable.isActive eq true }

        if (!specialization.isNullOrEmpty()) {
            query = query.andWhere { DoctorsTable.specialization like "%${specialization}%" }
        }

        query
            .limit(limit, offset.toLong())
            .orderBy(DoctorsTable.averageRating to SortOrder.DESC)
            .map { toDoctorDto(it) }
    }

    /**
     * Get doctor by ID
     */
    fun getDoctorById(doctorId: String): DoctorDto? = transaction {
        DoctorsTable
            .selectAll()
            .where { DoctorsTable.id eq doctorId }
            .map { toDoctorDto(it) }
            .firstOrNull()
    }

    /**
     * Search doctors by name or specialization
     */
    fun searchDoctors(query: String, limit: Int = 20): List<DoctorDto> = transaction {
        DoctorsTable
            .selectAll()
            .where {
                (DoctorsTable.isActive eq true) and
                (
                    (DoctorsTable.name like "%${query}%") or
                    (DoctorsTable.specialization like "%${query}%")
                )
            }
            .limit(limit)
            .orderBy(DoctorsTable.averageRating to SortOrder.DESC)
            .map { toDoctorDto(it) }
    }

    /**
     * Get doctors by specialization
     */
    fun getDoctorsBySpecialization(specialization: String, limit: Int = 20): List<DoctorDto> = transaction {
        DoctorsTable
            .selectAll()
            .where { (DoctorsTable.isActive eq true) and (DoctorsTable.specialization eq specialization) }
            .limit(limit)
            .orderBy(DoctorsTable.averageRating to SortOrder.DESC)
            .map { toDoctorDto(it) }
    }

    /**
     * Get top-rated doctors
     */
    fun getTopRatedDoctors(limit: Int = 10): List<DoctorDto> = transaction {
        DoctorsTable
            .selectAll()
            .where { DoctorsTable.isActive eq true }
            .orderBy(DoctorsTable.averageRating to SortOrder.DESC)
            .limit(limit)
            .map { toDoctorDto(it) }
    }

    /**
     * Create new doctor (Admin only)
     */
    fun createDoctor(request: DoctorCreateRequest): String = transaction {
        val doctorId = UUID.randomUUID().toString()

        DoctorsTable.insert {
            it[id] = doctorId
            it[name] = request.name
            it[specialization] = request.specialization
            it[yearsOfExperience] = request.yearsOfExperience
            it[bio] = request.bio
            it[avatarUrl] = request.avatarUrl
            it[consultationFee] = request.consultationFee.toBigDecimal()
            it[averageRating] = 0.toBigDecimal()
            it[isActive] = true
        }

        doctorId
    }

    /**
     * Update doctor info (Admin only)
     */
    fun updateDoctor(doctorId: String, request: DoctorUpdateRequest): Boolean = transaction {
        val doctor = DoctorsTable.selectAll().where { DoctorsTable.id eq doctorId }.singleOrNull()
            ?: throw IllegalArgumentException("Doctor not found")

        val updated = DoctorsTable.update({ DoctorsTable.id eq doctorId }) {
            request.name?.let { name -> it[DoctorsTable.name] = name }
            request.specialization?.let { spec -> it[DoctorsTable.specialization] = spec }
            request.yearsOfExperience?.let { years -> it[DoctorsTable.yearsOfExperience] = years }
            request.bio?.let { b -> it[DoctorsTable.bio] = b }
            request.avatarUrl?.let { url -> it[DoctorsTable.avatarUrl] = url }
            request.consultationFee?.let { fee -> it[DoctorsTable.consultationFee] = fee.toBigDecimal() }
            request.isActive?.let { active -> it[DoctorsTable.isActive] = active }
        }

        updated > 0
    }

    /**
     * Deactivate doctor
     */
    fun deactivateDoctor(doctorId: String): Boolean = transaction {
        val updated = DoctorsTable.update({ DoctorsTable.id eq doctorId }) {
            it[isActive] = false
        }
        updated > 0
    }

    /**
     * Get doctor availability for a specific date
     * ⚠️ TIER 3 - NOT FULLY IMPLEMENTED
     */
    fun getDoctorAvailability(doctorId: String, date: String): List<DoctorAvailabilitySlot> {
        throw NotImplementedError("Doctor availability not yet implemented. Backend service has ORM type mismatches.")
    }

    /**
     * Get doctor's consultation history
     */
    fun getDoctorConsultations(
        doctorId: String,
        status: String? = null,
        limit: Int = 20,
        offset: Int = 0
    ): List<Any> = transaction {
        var query = ConsultationsTable.selectAll().where { ConsultationsTable.doctorId eq doctorId }

        if (!status.isNullOrEmpty()) {
            query = query.andWhere { ConsultationsTable.status eq status }
        }

        query
            .limit(limit, offset.toLong())
            .orderBy(ConsultationsTable.scheduledAt to SortOrder.DESC)
            .map { row ->
                mapOf(
                    "id" to row[ConsultationsTable.id],
                    "consultationCode" to row[ConsultationsTable.consultationCode],
                    "userId" to row[ConsultationsTable.userId],
                    "scheduledAt" to row[ConsultationsTable.scheduledAt].toString(),
                    "status" to row[ConsultationsTable.status],
                    "sessionType" to row[ConsultationsTable.sessionType],
                    "fee" to row[ConsultationsTable.fee],
                    "paymentStatus" to row[ConsultationsTable.paymentStatus]
                )
            }
    }

    /**
     * Get doctor's average rating
     */
    fun getDoctorRating(doctorId: String): Double = transaction {
        val avgRating = ConsultationRatingsTable
            .innerJoin(ConsultationsTable)
            .selectAll()
            .where { ConsultationsTable.doctorId eq doctorId }
            .map { it[ConsultationRatingsTable.rating] }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?: 0.0

        // Update doctor's average rating
        DoctorsTable.update({ DoctorsTable.id eq doctorId }) {
            it[averageRating] = avgRating.toBigDecimal()
        }

        avgRating
    }

    /**
     * Get all specializations
     */
    fun getAllSpecializations(): List<String> = transaction {
        DoctorsTable
            .selectAll()
            .where { DoctorsTable.isActive eq true }
            .map { it[DoctorsTable.specialization] }
            .distinct()
            .sorted()
    }

    // ─────────────────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────────────────

    private fun toDoctorDto(row: ResultRow): DoctorDto {
        val doctorId = row[DoctorsTable.id]
        val totalConsultations = ConsultationsTable.selectAll().where { ConsultationsTable.doctorId eq doctorId }.count().toInt()
        val totalRatings = ConsultationRatingsTable
            .innerJoin(ConsultationsTable)
            .selectAll()
            .where { ConsultationsTable.doctorId eq doctorId }
            .count()
            .toInt()

        return DoctorDto(
            id = row[DoctorsTable.id],
            name = row[DoctorsTable.name],
            specialization = row[DoctorsTable.specialization],
            yearsOfExperience = row[DoctorsTable.yearsOfExperience],
            bio = row[DoctorsTable.bio],
            avatarUrl = row[DoctorsTable.avatarUrl],
            consultationFee = row[DoctorsTable.consultationFee].toLong(),
            averageRating = row[DoctorsTable.averageRating].toDouble(),
            isActive = row[DoctorsTable.isActive],
            totalConsultations = totalConsultations,
            totalRatings = totalRatings
        )
    }
}
