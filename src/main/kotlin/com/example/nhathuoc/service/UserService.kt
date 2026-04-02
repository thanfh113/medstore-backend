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

data class UpdateUserProfileRequest(
    val email: String? = null,
    val fullName: String? = null,
    val gender: String? = null, // Nam/Nữ/Khác
    val dateOfBirth: String? = null, // YYYY-MM-DD format
    val avatarUrl: String? = null
)

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
    val isDefault: Boolean
)

data class CreateAddressRequest(
    val label: String? = null,
    val recipientName: String? = null,
    val phone: String? = null,
    val address: String,
    val ward: String? = null,
    val district: String? = null,
    val province: String? = null,
    val isDefault: Boolean = false
)

data class UpdateAddressRequest(
    val label: String? = null,
    val recipientName: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val ward: String? = null,
    val district: String? = null,
    val province: String? = null,
    val isDefault: Boolean? = null
)

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
            if (!request.email.isNullOrBlank()) {
                val emailExists = UsersTable
                    .selectAll()
                    .where { (UsersTable.email eq request.email) and (UsersTable.id neq userId) }
                    .singleOrNull()

                if (emailExists != null) {
                    throw IllegalArgumentException("Email already exists")
                }

                // Basic email validation
                val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
                if (!emailRegex.matches(request.email)) {
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
                if (request.email != null) it[UsersTable.email] = request.email
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
                .where { UserAddressesTable.userId eq userId }
                .orderBy(UserAddressesTable.isDefault to SortOrder.DESC) // Default address first
                .map { row ->
                    UserAddressDto(
                        id = row[UserAddressesTable.id],
                        userId = row[UserAddressesTable.userId],
                        label = row[UserAddressesTable.label],
                        recipientName = row[UserAddressesTable.recipientName],
                        phone = row[UserAddressesTable.phone],
                        address = row[UserAddressesTable.address],
                        ward = row[UserAddressesTable.ward],
                        district = row[UserAddressesTable.district],
                        province = row[UserAddressesTable.province],
                        isDefault = row[UserAddressesTable.isDefault]
                    )
                }
        }
    }

    /**
     * Get address by ID
     */
    fun getAddressById(userId: String, addressId: String): UserAddressDto? {
        return transaction {
            UserAddressesTable
                .selectAll()
                .where { (UserAddressesTable.id eq addressId) and (UserAddressesTable.userId eq userId) }
                .singleOrNull()
                ?.let { row ->
                    UserAddressDto(
                        id = row[UserAddressesTable.id],
                        userId = row[UserAddressesTable.userId],
                        label = row[UserAddressesTable.label],
                        recipientName = row[UserAddressesTable.recipientName],
                        phone = row[UserAddressesTable.phone],
                        address = row[UserAddressesTable.address],
                        ward = row[UserAddressesTable.ward],
                        district = row[UserAddressesTable.district],
                        province = row[UserAddressesTable.province],
                        isDefault = row[UserAddressesTable.isDefault]
                    )
                }
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

            if (request.address.isBlank()) {
                throw IllegalArgumentException("Address is required")
            }

            val addressId = UUID.randomUUID().toString()

            // If this is set as default, unset other default addresses
            if (request.isDefault) {
                UserAddressesTable.update({ UserAddressesTable.userId eq userId }) {
                    it[UserAddressesTable.isDefault] = false
                }
            }

            // If this is the first address, make it default automatically
            val addressCount = UserAddressesTable
                .selectAll()
                .where { UserAddressesTable.userId eq userId }
                .count()

            val shouldBeDefault = request.isDefault || addressCount == 0L

            UserAddressesTable.insert {
                it[UserAddressesTable.id] = addressId
                it[UserAddressesTable.userId] = userId
                it[UserAddressesTable.label] = request.label
                it[UserAddressesTable.recipientName] = request.recipientName
                it[UserAddressesTable.phone] = request.phone
                it[UserAddressesTable.address] = request.address
                it[UserAddressesTable.ward] = request.ward
                it[UserAddressesTable.district] = request.district
                it[UserAddressesTable.province] = request.province
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
                .where { (UserAddressesTable.id eq addressId) and (UserAddressesTable.userId eq userId) }
                .singleOrNull()
                ?: throw IllegalArgumentException("Address not found")

            // If setting as default, unset other default addresses
            if (request.isDefault == true) {
                UserAddressesTable.update({ (UserAddressesTable.userId eq userId) and (UserAddressesTable.id neq addressId) }) {
                    it[UserAddressesTable.isDefault] = false
                }
            }

            UserAddressesTable.update({ UserAddressesTable.id eq addressId }) {
                if (request.label != null) it[UserAddressesTable.label] = request.label
                if (request.recipientName != null) it[UserAddressesTable.recipientName] = request.recipientName
                if (request.phone != null) it[UserAddressesTable.phone] = request.phone
                if (request.address != null) it[UserAddressesTable.address] = request.address
                if (request.ward != null) it[UserAddressesTable.ward] = request.ward
                if (request.district != null) it[UserAddressesTable.district] = request.district
                if (request.province != null) it[UserAddressesTable.province] = request.province
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
                .where { (UserAddressesTable.id eq addressId) and (UserAddressesTable.userId eq userId) }
                .singleOrNull()
                ?: throw IllegalArgumentException("Address not found")

            val wasDefault = existingAddress[UserAddressesTable.isDefault]

            // Delete the address
            UserAddressesTable.deleteWhere {
                (UserAddressesTable.id eq addressId) and (UserAddressesTable.userId eq userId)
            }

            // If deleted address was default, set another address as default
            if (wasDefault) {
                val firstRemainingAddress = UserAddressesTable
                    .selectAll()
                    .where { UserAddressesTable.userId eq userId }
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
                .where { (UserAddressesTable.id eq addressId) and (UserAddressesTable.userId eq userId) }
                .singleOrNull()
                ?: throw IllegalArgumentException("Address not found")

            // Unset all other default addresses for this user
            UserAddressesTable.update({ (UserAddressesTable.userId eq userId) and (UserAddressesTable.id neq addressId) }) {
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
                .where { (UserAddressesTable.userId eq userId) and (UserAddressesTable.isDefault eq true) }
                .singleOrNull()
                ?.let { row ->
                    UserAddressDto(
                        id = row[UserAddressesTable.id],
                        userId = row[UserAddressesTable.userId],
                        label = row[UserAddressesTable.label],
                        recipientName = row[UserAddressesTable.recipientName],
                        phone = row[UserAddressesTable.phone],
                        address = row[UserAddressesTable.address],
                        ward = row[UserAddressesTable.ward],
                        district = row[UserAddressesTable.district],
                        province = row[UserAddressesTable.province],
                        isDefault = row[UserAddressesTable.isDefault]
                    )
                }
        }
    }
}