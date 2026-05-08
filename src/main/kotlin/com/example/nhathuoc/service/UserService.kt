package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import com.example.nhathuoc.util.EmailHelper
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
data class UserProfileDto(
    val id: String,
    val phone: String,
    val email: String?,
    val fullName: String?,
    val avatarUrl: String?,
    val gender: String?,
    val dateOfBirth: String?,
    val role: String,
    val isActive: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

@Serializable
data class UpdateUserProfileRequest(
    val email: String? = null,
    val fullName: String? = null,
    val gender: String? = null, // Nam/Nữ/Khác
    val dateOfBirth: String? = null, // YYYY-MM-DD format
    val avatarUrl: String? = null
)

@Serializable
data class UserAddressDto(
    val id: String,
    val userId: String,
    val label: String?, // Nhà, Cơ quan, etc.
    val recipientName: String?,
    val phone: String?,
    val address: String,
    val ward: String?,
    val district: String?,
    val province: String?,
    val isDefault: Boolean,
    val fullAddress: String? = null,
    val wardCode: String? = null,
    val provinceCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String = "MANUAL"
)

@Serializable
data class CreateAddressRequest(
    val label: String? = null,
    val recipientName: String? = null,
    val phone: String? = null,
    val address: String,
    val fullAddress: String? = null,
    val ward: String? = null,
    val wardCode: String? = null,
    val district: String? = null,
    val province: String? = null,
    val provinceCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String = "MANUAL",
    val isDefault: Boolean = false
)

@Serializable
data class UpdateAddressRequest(
    val label: String? = null,
    val recipientName: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val fullAddress: String? = null,
    val ward: String? = null,
    val wardCode: String? = null,
    val district: String? = null,
    val province: String? = null,
    val provinceCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String? = null,
    val isDefault: Boolean? = null
)

private fun ResultRow.toUserAddressDto(): UserAddressDto = UserAddressDto(
    id = this[UserAddressesTable.id],
    userId = this[UserAddressesTable.userId],
    label = this[UserAddressesTable.label],
    recipientName = this[UserAddressesTable.recipientName],
    phone = this[UserAddressesTable.phone],
    address = this[UserAddressesTable.address],
    fullAddress = this[UserAddressesTable.fullAddress],
    ward = this[UserAddressesTable.ward],
    wardCode = this[UserAddressesTable.wardCode],
    district = this[UserAddressesTable.district],
    province = this[UserAddressesTable.province],
    provinceCode = this[UserAddressesTable.provinceCode],
    latitude = this[UserAddressesTable.latitude]?.toDouble(),
    longitude = this[UserAddressesTable.longitude]?.toDouble(),
    locationSource = this[UserAddressesTable.locationSource],
    isDefault = this[UserAddressesTable.isDefault]
)

private fun buildFullAddress(address: String, ward: String?, district: String?, province: String?): String {
    return listOf(address, ward, district, province)
        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
        .joinToString(", ")
}

private fun normalizedLocationSource(source: String?): String {
    return when (source?.trim()?.uppercase()) {
        "MAP" -> "MAP"
        "GPS" -> "GPS"
        else -> "MANUAL"
    }
}

private fun Double?.toDbDecimal(): BigDecimal? = this?.let { BigDecimal.valueOf(it) }

private fun activeAddressCondition(userId: String): Op<Boolean> =
    (UserAddressesTable.userId eq userId) and (UserAddressesTable.isDeleted eq false)

// ─────────────────────────────────────────────────────────────
// SERVICE
// ─────────────────────────────────────────────────────────────

class UserService {

    /**
     * Get user profile by ID
     */
    fun getUserProfile(userId: String): UserProfileDto? {
        return transaction {
            UsersTable
                .selectAll()
                .where { UsersTable.id eq userId }
                .singleOrNull()
                ?.let { row ->
                    UserProfileDto(
                        id = row[UsersTable.id],
                        phone = row[UsersTable.phone],
                        email = row[UsersTable.email],
                        fullName = row[UsersTable.fullName],
                        avatarUrl = row[UsersTable.avatarUrl],
                        gender = row[UsersTable.gender],
                        dateOfBirth = row[UsersTable.dateOfBirth],
                        role = row[UsersTable.role],
                        isActive = row[UsersTable.isActive],
                        createdAt = row[UsersTable.createdAt],
                        updatedAt = row[UsersTable.updatedAt]
                    )
                }
        }
    }

    /**
     * Update user profile
     */
    fun updateUserProfile(userId: String, request: UpdateUserProfileRequest) {
        transaction {
            // Check if user exists
            val existsUser = UsersTable
                .selectAll()
                .where { UsersTable.id eq userId }
                .singleOrNull()
                ?: throw IllegalArgumentException("User not found")

            // Validate email uniqueness if provided
            val normalizedEmail = EmailHelper.normalize(request.email)
            if (normalizedEmail != null) {
                val emailExists = UsersTable
                    .selectAll()
                    .where { (UsersTable.email eq normalizedEmail) and (UsersTable.id neq userId) }
                    .singleOrNull()

                if (emailExists != null) {
                    throw IllegalArgumentException("Email already exists")
                }

                // Basic email validation
                if (!EmailHelper.isValid(normalizedEmail)) {
                    throw IllegalArgumentException("Invalid email format")
                }
            }

            // Validate gender if provided
            if (!request.gender.isNullOrBlank()) {
                val validGenders = listOf("Nam", "Nữ", "Khác")
                if (request.gender !in validGenders) {
                    throw IllegalArgumentException("Invalid gender. Must be one of: ${validGenders.joinToString(", ")}")
                }
            }

            // Validate date of birth format if provided
            if (!request.dateOfBirth.isNullOrBlank()) {
                try {
                    // Basic date format validation (YYYY-MM-DD)
                    val dateRegex = "^\\d{4}-\\d{2}-\\d{2}$".toRegex()
                    if (!dateRegex.matches(request.dateOfBirth)) {
                        throw IllegalArgumentException("Invalid date format. Use YYYY-MM-DD")
                    }
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid date format. Use YYYY-MM-DD")
                }
            }

            UsersTable.update({ UsersTable.id eq userId }) {
                if (request.email != null) it[UsersTable.email] = normalizedEmail
                if (request.fullName != null) it[UsersTable.fullName] = request.fullName
                if (request.gender != null) it[UsersTable.gender] = request.gender
                if (request.dateOfBirth != null) it[UsersTable.dateOfBirth] = request.dateOfBirth
                if (request.avatarUrl != null) it[UsersTable.avatarUrl] = request.avatarUrl
                // Update timestamp handled by defaultExpression
            }
        }
    }

    /**
     * Get user addresses
     */
    fun getUserAddresses(userId: String): List<UserAddressDto> {
        return transaction {
            UserAddressesTable
                .selectAll()
                .where { activeAddressCondition(userId) }
                .orderBy(UserAddressesTable.isDefault to SortOrder.DESC) // Default address first
                .map { row -> row.toUserAddressDto() }
        }
    }

    /**
     * Get address by ID
     */
    fun getAddressById(userId: String, addressId: String): UserAddressDto? {
        return transaction {
            UserAddressesTable
                .selectAll()
                .where { (UserAddressesTable.id eq addressId) and activeAddressCondition(userId) }
                .singleOrNull()
                ?.toUserAddressDto()
        }
    }

    /**
     * Create new address
     */
    fun createAddress(userId: String, request: CreateAddressRequest): String {
        return transaction {
            // Validate user exists
            val userExists = UsersTable
                .selectAll()
                .where { UsersTable.id eq userId }
                .singleOrNull()
                ?: throw IllegalArgumentException("User not found")

            val addressLine = request.address.trim()
            if (addressLine.isBlank()) {
                throw IllegalArgumentException("Address is required")
            }

            val addressId = UUID.randomUUID().toString()
            val requestedFullAddress = request.fullAddress
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val fullAddress = if (requestedFullAddress != null && requestedFullAddress != addressLine) {
                requestedFullAddress
            } else {
                buildFullAddress(addressLine, request.ward, request.district, request.province)
            }

            // If this is set as default, unset other default addresses
            if (request.isDefault) {
                UserAddressesTable.update({ activeAddressCondition(userId) }) {
                    it[UserAddressesTable.isDefault] = false
                }
            }

            // If this is the first address, make it default automatically
            val addressCount = UserAddressesTable
                .selectAll()
                .where { activeAddressCondition(userId) }
                .count()

            val shouldBeDefault = request.isDefault || addressCount == 0L

            UserAddressesTable.insert {
                it[UserAddressesTable.id] = addressId
                it[UserAddressesTable.userId] = userId
                it[UserAddressesTable.label] = request.label
                it[UserAddressesTable.recipientName] = request.recipientName
                it[UserAddressesTable.phone] = request.phone
                it[UserAddressesTable.address] = addressLine
                it[UserAddressesTable.fullAddress] = fullAddress
                it[UserAddressesTable.ward] = request.ward
                it[UserAddressesTable.wardCode] = request.wardCode
                it[UserAddressesTable.district] = request.district
                it[UserAddressesTable.province] = request.province
                it[UserAddressesTable.provinceCode] = request.provinceCode
                it[UserAddressesTable.latitude] = request.latitude.toDbDecimal()
                it[UserAddressesTable.longitude] = request.longitude.toDbDecimal()
                it[UserAddressesTable.locationSource] = normalizedLocationSource(request.locationSource)
                it[UserAddressesTable.isDefault] = shouldBeDefault
            }

            addressId
        }
    }

    /**
     * Update address
     */
    fun updateAddress(userId: String, addressId: String, request: UpdateAddressRequest) {
        transaction {
            // Check if address exists and belongs to user
            val existingAddress = UserAddressesTable
                .selectAll()
                .where { (UserAddressesTable.id eq addressId) and activeAddressCondition(userId) }
                .singleOrNull()
                ?: throw IllegalArgumentException("Address not found")

            // If setting as default, unset other default addresses
            if (request.isDefault == true) {
                UserAddressesTable.update({ activeAddressCondition(userId) and (UserAddressesTable.id neq addressId) }) {
                    it[UserAddressesTable.isDefault] = false
                }
            }

            UserAddressesTable.update({ (UserAddressesTable.id eq addressId) and activeAddressCondition(userId) }) {
                if (request.label != null) it[UserAddressesTable.label] = request.label
                if (request.recipientName != null) it[UserAddressesTable.recipientName] = request.recipientName
                if (request.phone != null) it[UserAddressesTable.phone] = request.phone
                if (request.address != null) it[UserAddressesTable.address] = request.address.trim()
                if (request.fullAddress != null) it[UserAddressesTable.fullAddress] = request.fullAddress.trim().takeIf { it.isNotBlank() }
                if (request.ward != null) it[UserAddressesTable.ward] = request.ward
                if (request.wardCode != null) it[UserAddressesTable.wardCode] = request.wardCode
                if (request.district != null) it[UserAddressesTable.district] = request.district
                if (request.province != null) it[UserAddressesTable.province] = request.province
                if (request.provinceCode != null) it[UserAddressesTable.provinceCode] = request.provinceCode
                if (request.latitude != null) it[UserAddressesTable.latitude] = request.latitude.toDbDecimal()
                if (request.longitude != null) it[UserAddressesTable.longitude] = request.longitude.toDbDecimal()
                if (request.locationSource != null) it[UserAddressesTable.locationSource] = normalizedLocationSource(request.locationSource)
                if (request.isDefault != null) it[UserAddressesTable.isDefault] = request.isDefault
            }
        }
    }

    /**
     * Delete address
     */
    fun deleteAddress(userId: String, addressId: String) {
        transaction {
            // Check if address exists and belongs to user
            val existingAddress = UserAddressesTable
                .selectAll()
                .where { (UserAddressesTable.id eq addressId) and activeAddressCondition(userId) }
                .singleOrNull()
                ?: throw IllegalArgumentException("Address not found")

            val wasDefault = existingAddress[UserAddressesTable.isDefault]

            // Keep historical orders valid; hide the address from user-facing lists instead.
            UserAddressesTable.update({
                (UserAddressesTable.id eq addressId) and (UserAddressesTable.userId eq userId)
            }) {
                it[UserAddressesTable.isDeleted] = true
                it[UserAddressesTable.isDefault] = false
            }

            // If deleted address was default, set another address as default
            if (wasDefault) {
                val firstRemainingAddress = UserAddressesTable
                    .selectAll()
                    .where { activeAddressCondition(userId) }
                    .limit(1)
                    .singleOrNull()

                if (firstRemainingAddress != null) {
                    UserAddressesTable.update({ UserAddressesTable.id eq firstRemainingAddress[UserAddressesTable.id] }) {
                        it[UserAddressesTable.isDefault] = true
                    }
                }
            }
        }
    }

    /**
     * Set default address
     */
    fun setDefaultAddress(userId: String, addressId: String) {
        transaction {
            // Check if address exists and belongs to user
            val addressExists = UserAddressesTable
                .selectAll()
                .where { (UserAddressesTable.id eq addressId) and activeAddressCondition(userId) }
                .singleOrNull()
                ?: throw IllegalArgumentException("Address not found")

            // Unset all other default addresses for this user
            UserAddressesTable.update({ activeAddressCondition(userId) and (UserAddressesTable.id neq addressId) }) {
                it[UserAddressesTable.isDefault] = false
            }

            // Set this address as default
            UserAddressesTable.update({ UserAddressesTable.id eq addressId }) {
                it[UserAddressesTable.isDefault] = true
            }
        }
    }

    /**
     * Get default address
     */
    fun getDefaultAddress(userId: String): UserAddressDto? {
        return transaction {
            UserAddressesTable
                .selectAll()
                .where { activeAddressCondition(userId) and (UserAddressesTable.isDefault eq true) }
                .singleOrNull()
                ?.toUserAddressDto()
        }
    }
}
