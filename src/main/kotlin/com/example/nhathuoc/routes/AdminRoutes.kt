package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.EmployeeProfilesTable
import com.example.nhathuoc.database.tables.BannersTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.RefreshTokensTable
import com.example.nhathuoc.database.tables.RewardAccountsTable
import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.service.ProductDeleteRequestDto
import com.example.nhathuoc.service.ProductService
import com.example.nhathuoc.util.AppRoles
import com.example.nhathuoc.util.CloudinaryHelper
import com.example.nhathuoc.util.PasswordHelper
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.requireRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SortOrder
import java.util.UUID

@kotlinx.serialization.Serializable
private data class FinanceSummaryDto(
    val shopId: String? = null,
    val grossRevenue: Double,
    val onlineRevenue: Double,
    val posRevenue: Double,
    val totalDiscount: Double,
    val totalExpenses: Double,
    val netProfit: Double,
    val successfulOrderCount: Int,
    val expenseCount: Int
)

@kotlinx.serialization.Serializable
private data class AdminEnvelope<T>(
    val data: T,
    val message: String
)

@kotlinx.serialization.Serializable
private data class AdminEmployeeProfileDto(
    val id: String,
    val qualificationTitle: String,
    val qualificationSpecialty: String? = null,
    val qualificationInstitution: String? = null,
    val qualificationDocumentUrl: String? = null,
    val qualificationDocumentPublicId: String? = null,
    val qualificationDocumentType: String? = null,
    val qualificationDocumentResourceType: String? = null,
    val qualificationVerified: Boolean,
    val qualificationSubmittedAt: String? = null,
    val qualificationVerifiedBy: String? = null,
    val qualificationVerifiedAt: String? = null,
    val qualificationNote: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@kotlinx.serialization.Serializable
private data class AdminEmployeeProfileRequest(
    val qualificationTitle: String? = null,
    val qualificationSpecialty: String? = null,
    val qualificationInstitution: String? = null,
    val qualificationDocumentUrl: String? = null,
    val qualificationDocumentPublicId: String? = null,
    val qualificationDocumentType: String? = null,
    val qualificationDocumentResourceType: String? = null,
    val qualificationVerified: Boolean? = null,
    val qualificationNote: String? = null
)

@kotlinx.serialization.Serializable
private data class AdminUserDto(
    val id: String,
    val fullName: String? = null,
    val phone: String,
    val email: String? = null,
    val role: String,
    val isActive: Boolean,
    val createdAt: String,
    val employeeProfile: AdminEmployeeProfileDto? = null
)

@kotlinx.serialization.Serializable
private data class AdminCreateUserRequest(
    val fullName: String? = null,
    val phone: String,
    val email: String? = null,
    val password: String,
    val role: String = AppRoles.EMPLOYEE,
    val employeeProfile: AdminEmployeeProfileRequest? = null
)

@kotlinx.serialization.Serializable
private data class AdminUpdateUserRequest(
    val fullName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val role: String? = null,
    val employeeProfile: AdminEmployeeProfileRequest? = null
)

@kotlinx.serialization.Serializable
private data class AdminResetPasswordRequest(
    val newPassword: String
)

@kotlinx.serialization.Serializable
private data class AdminReviewDeleteRequest(
    val approve: Boolean
)

@kotlinx.serialization.Serializable
private data class AdminBannerRequest(
    val imageUrl: String,
    val linkUrl: String? = null,
    val title: String? = null,
    val description: String? = null,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val startDt: String? = null,
    val endDt: String? = null
)

private const val DEFAULT_EMPLOYEE_QUALIFICATION_TITLE = "Cần cập nhật hồ sơ chuyên môn"

private fun String?.trimToNull(): String? = this?.trim()?.ifBlank { null }

private fun softDeletedEmail(userId: String): String = "deleted-$userId@deleted.medstore.local"

private fun softDeletedPhone(userId: String): String = "del-${userId.take(11)}"

private fun ResultRow.toEmployeeProfileDto(): AdminEmployeeProfileDto? {
    val profileId = getOrNull(EmployeeProfilesTable.id) ?: return null
    val documentUrl = this[EmployeeProfilesTable.qualificationDocumentUrl]
    val documentResourceType = this[EmployeeProfilesTable.qualificationDocumentResourceType]
    return AdminEmployeeProfileDto(
        id = profileId,
        qualificationTitle = this[EmployeeProfilesTable.qualificationTitle],
        qualificationSpecialty = this[EmployeeProfilesTable.qualificationSpecialty],
        qualificationInstitution = this[EmployeeProfilesTable.qualificationInstitution],
        qualificationDocumentUrl = documentUrl?.let { CloudinaryHelper.signedDeliveryUrl(it, documentResourceType) },
        qualificationDocumentPublicId = this[EmployeeProfilesTable.qualificationDocumentPublicId],
        qualificationDocumentType = this[EmployeeProfilesTable.qualificationDocumentType],
        qualificationDocumentResourceType = documentResourceType,
        qualificationVerified = this[EmployeeProfilesTable.qualificationVerified],
        qualificationSubmittedAt = this[EmployeeProfilesTable.qualificationSubmittedAt]?.toString(),
        qualificationVerifiedBy = this[EmployeeProfilesTable.qualificationVerifiedBy],
        qualificationVerifiedAt = this[EmployeeProfilesTable.qualificationVerifiedAt]?.toString(),
        qualificationNote = this[EmployeeProfilesTable.qualificationNote],
        createdAt = this[EmployeeProfilesTable.createdAt].toString(),
        updatedAt = this[EmployeeProfilesTable.updatedAt].toString()
    )
}

private fun ResultRow.toAdminUserDto(): AdminUserDto {
    val role = this[UsersTable.role]
    return AdminUserDto(
        id = this[UsersTable.id],
        fullName = this[UsersTable.fullName],
        phone = this[UsersTable.phone],
        email = this[UsersTable.email],
        role = role,
        isActive = this[UsersTable.isActive],
        createdAt = this[UsersTable.createdAt].toString(),
        employeeProfile = if (role == AppRoles.EMPLOYEE) toEmployeeProfileDto() else null
    )
}

private fun selectAdminUserById(userId: String): AdminUserDto {
    return UsersTable
        .join(EmployeeProfilesTable, JoinType.LEFT, UsersTable.id, EmployeeProfilesTable.userId)
        .selectAll()
        .where { (UsersTable.id eq userId) and UsersTable.deletedAt.isNull() }
        .single()
        .toAdminUserDto()
}

private fun upsertEmployeeProfile(
    userId: String,
    request: AdminEmployeeProfileRequest?,
    now: LocalDateTime,
    actorUserId: String?
) {
    val existing = EmployeeProfilesTable.selectAll()
        .where { EmployeeProfilesTable.userId eq userId }
        .singleOrNull()

    if (existing == null) {
        val documentUrl = request?.qualificationDocumentUrl.trimToNull()
        val documentType = request?.qualificationDocumentType.trimToNull() ?: detectDocumentType(documentUrl)
        val documentResourceType = request?.qualificationDocumentResourceType.trimToNull() ?: defaultDocumentResourceType(documentType)
        val verified = request?.qualificationVerified ?: false
        EmployeeProfilesTable.insert {
            it[EmployeeProfilesTable.id] = UUID.randomUUID().toString()
            it[EmployeeProfilesTable.userId] = userId
            it[EmployeeProfilesTable.qualificationTitle] =
                request?.qualificationTitle.trimToNull() ?: DEFAULT_EMPLOYEE_QUALIFICATION_TITLE
            it[EmployeeProfilesTable.qualificationSpecialty] = request?.qualificationSpecialty.trimToNull()
            it[EmployeeProfilesTable.qualificationInstitution] = request?.qualificationInstitution.trimToNull()
            it[EmployeeProfilesTable.qualificationDocumentUrl] = documentUrl
            it[EmployeeProfilesTable.qualificationDocumentPublicId] = request?.qualificationDocumentPublicId.trimToNull()
            it[EmployeeProfilesTable.qualificationDocumentType] = documentType
            it[EmployeeProfilesTable.qualificationDocumentResourceType] = documentResourceType
            it[EmployeeProfilesTable.qualificationVerified] = verified
            it[EmployeeProfilesTable.qualificationSubmittedAt] = if (documentUrl != null) now else null
            it[EmployeeProfilesTable.qualificationVerifiedBy] = if (verified) actorUserId else null
            it[EmployeeProfilesTable.qualificationVerifiedAt] = if (verified) now else null
            it[EmployeeProfilesTable.qualificationNote] = request?.qualificationNote.trimToNull()
            it[EmployeeProfilesTable.createdAt] = now
            it[EmployeeProfilesTable.updatedAt] = now
        }
        return
    }

    if (request == null) return

    val requestedDocumentUrl = request.qualificationDocumentUrl.trimToNull()
    val documentUrl = requestedDocumentUrl ?: existing[EmployeeProfilesTable.qualificationDocumentUrl]
    val documentPublicId = request.qualificationDocumentPublicId.trimToNull()
        ?: existing[EmployeeProfilesTable.qualificationDocumentPublicId]
    val documentChanged = requestedDocumentUrl != null &&
        requestedDocumentUrl != existing[EmployeeProfilesTable.qualificationDocumentUrl]
    val nextVerified = request.qualificationVerified ?: existing[EmployeeProfilesTable.qualificationVerified]
    val documentType = request.qualificationDocumentType.trimToNull()
        ?: detectDocumentType(documentUrl)
        ?: existing[EmployeeProfilesTable.qualificationDocumentType]
    val documentResourceType = request.qualificationDocumentResourceType.trimToNull()
        ?: defaultDocumentResourceType(documentType)
        ?: existing[EmployeeProfilesTable.qualificationDocumentResourceType]

    EmployeeProfilesTable.update({ EmployeeProfilesTable.userId eq userId }) {
        it[EmployeeProfilesTable.qualificationTitle] =
            request.qualificationTitle.trimToNull() ?: existing[EmployeeProfilesTable.qualificationTitle]
        it[EmployeeProfilesTable.qualificationSpecialty] = request.qualificationSpecialty.trimToNull()
        it[EmployeeProfilesTable.qualificationInstitution] = request.qualificationInstitution.trimToNull()
        it[EmployeeProfilesTable.qualificationDocumentUrl] = documentUrl
        it[EmployeeProfilesTable.qualificationDocumentPublicId] = documentPublicId
        it[EmployeeProfilesTable.qualificationDocumentType] = documentType
        it[EmployeeProfilesTable.qualificationDocumentResourceType] = documentResourceType
        it[EmployeeProfilesTable.qualificationVerified] = nextVerified
        if (documentChanged && documentUrl != null) {
            it[EmployeeProfilesTable.qualificationSubmittedAt] = now
        }
        if (request.qualificationVerified != null) {
            it[EmployeeProfilesTable.qualificationVerifiedBy] = if (nextVerified) actorUserId else null
            it[EmployeeProfilesTable.qualificationVerifiedAt] = if (nextVerified) now else null
        }
        it[EmployeeProfilesTable.qualificationNote] = request.qualificationNote.trimToNull()
        it[EmployeeProfilesTable.updatedAt] = now
    }
}

private fun detectDocumentType(url: String?): String? {
    val normalized = url?.substringBefore("?")?.lowercase() ?: return null
    return when {
        normalized.endsWith(".pdf") -> "PDF"
        normalized.endsWith(".png") ||
            normalized.endsWith(".jpg") ||
            normalized.endsWith(".jpeg") ||
            normalized.endsWith(".webp") ||
            normalized.endsWith(".gif") ||
            normalized.endsWith(".heic") -> "IMAGE"
        else -> "OTHER"
    }
}

private fun defaultDocumentResourceType(documentType: String?): String? {
    return when (documentType?.uppercase()) {
        "PDF" -> "raw"
        "IMAGE" -> "image"
        "OTHER" -> "raw"
        else -> null
    }
}

private fun parseAdminDateTime(value: String?): LocalDateTime? {
    val normalized = value?.trim()?.ifBlank { null } ?: return null
    return LocalDateTime.parse(normalized.replace(' ', 'T'))
}

private fun ResultRow.toBannerDto(): BannerDto {
    return BannerDto(
        id = this[BannersTable.id],
        imageUrl = this[BannersTable.imageUrl],
        linkUrl = this[BannersTable.linkUrl],
        title = this[BannersTable.title],
        description = this[BannersTable.description],
        sortOrder = this[BannersTable.sortOrder],
        isActive = this[BannersTable.isActive],
        startDt = this[BannersTable.startDt]?.toString(),
        endDt = this[BannersTable.endDt]?.toString(),
        createdAt = this[BannersTable.createdAt].toString()
    )
}

private fun logAccountActionInTx(
    targetUserId: String,
    actorUserId: String,
    action: String,
    reason: String? = null,
    metadata: String? = null,
    now: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC)
) {
    // Account audit table is intentionally removed in the simplified DATN schema.
    // Keep this function as a compatibility hook so account operations stay unchanged.
}

fun Route.adminRoutes(
    productService: ProductService = ProductService(),
    resolveAdminShopId: (String) -> String? = { _ ->
        null  // Shop management removed
    },
    listDeleteRequests: (String?) -> List<ProductDeleteRequestDto> = { status ->
        productService.listDeleteRequests(status)
    },
    reviewDeleteRequest: (String, String, Boolean) -> ProductDeleteRequestDto = { requestId, adminUserId, approve ->
        productService.reviewDeleteRequest(requestId, adminUserId, approve)
    }
) {
    authenticate("auth-jwt") {
        route("/admin") {
            // Dashboard
            get("/dashboard") {
                call.requireRole(AppRoles.ADMIN)
                call.respond(mapOf("message" to "TODO: thống kê tổng quan"))
            }
            // Finance
            get("/finance") {
                call.requireRole(AppRoles.ADMIN)

                val summary = transaction {
                    val orderRows = OrdersTable.selectAll()
                        .toList()

                    val successfulOrders = orderRows.filter {
                        it[OrdersTable.status] == "DELIVERED" || it[OrdersTable.paymentStatus] == "COMPLETED"
                    }

                    val grossRevenue = successfulOrders.sumOf { it[OrdersTable.total]?.toDouble() ?: 0.0 }
                    val onlineRevenue = successfulOrders
                        .filter { it[OrdersTable.orderChannel] == "ONLINE" }
                        .sumOf { it[OrdersTable.total]?.toDouble() ?: 0.0 }
                    val posRevenue = successfulOrders
                        .filter { it[OrdersTable.orderChannel] == "POS" }
                        .sumOf { it[OrdersTable.total]?.toDouble() ?: 0.0 }
                    val totalDiscount = successfulOrders.sumOf { it[OrdersTable.discount].toDouble() }

                    val totalExpenses = 0.0

                    FinanceSummaryDto(
                        shopId = null,
                        grossRevenue = grossRevenue,
                        onlineRevenue = onlineRevenue,
                        posRevenue = posRevenue,
                        totalDiscount = totalDiscount,
                        totalExpenses = totalExpenses,
                        netProfit = grossRevenue - totalExpenses,
                        successfulOrderCount = successfulOrders.size,
                        expenseCount = 0
                    )
                }

                call.respond(
                    AdminEnvelope(
                        data = summary,
                        message = "Get finance summary successfully"
                    )
                )
            }
            get("/finance/export") {
                call.requireRole(AppRoles.ADMIN)
                call.respond(mapOf("message" to "TODO: xuất báo cáo"))
            }
            // Users - Shop management removed
            get("/users") {
                call.requireRole(AppRoles.ADMIN)

                val users = transaction {
                    UsersTable
                        .join(EmployeeProfilesTable, JoinType.LEFT, UsersTable.id, EmployeeProfilesTable.userId)
                        .selectAll()
                        .where { UsersTable.deletedAt.isNull() }
                        .orderBy(UsersTable.createdAt to SortOrder.DESC)
                        .map { it.toAdminUserDto() }
                }

                call.respond(HttpStatusCode.OK, AdminEnvelope(users, "Get users successfully"))
            }
            post("/users") {
                val adminPrincipal = call.requireRole(AppRoles.ADMIN)
                val actorUserId = adminPrincipal.getUserId()
                val req = call.receive<AdminCreateUserRequest>()
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                val role = req.role.trim().uppercase()

                if (req.phone.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Phone is required"))
                }
                if (req.password.length < 6) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Password must be at least 6 characters"))
                }
                if (role !in setOf(AppRoles.ADMIN, AppRoles.EMPLOYEE, AppRoles.USER)) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unsupported role"))
                }

                try {
                    val created = transaction {
                        val normalizedPhone = req.phone.trim()
                        val normalizedEmail = req.email?.trim()?.ifBlank { null }

                        val existsPhone = UsersTable.selectAll()
                            .where { (UsersTable.phone eq normalizedPhone) and UsersTable.deletedAt.isNull() }
                            .count() > 0
                        if (existsPhone) throw IllegalArgumentException("Phone already exists")

                        val existsEmail = normalizedEmail != null &&
                            UsersTable.selectAll()
                                .where { (UsersTable.email eq normalizedEmail) and UsersTable.deletedAt.isNull() }
                                .count() > 0
                        if (existsEmail) throw IllegalArgumentException("Email already exists")

                        val userId = UUID.randomUUID().toString()
                        UsersTable.insert {
                            it[UsersTable.id] = userId
                            it[UsersTable.phone] = normalizedPhone
                            it[UsersTable.email] = normalizedEmail
                            it[UsersTable.password] = PasswordHelper.hash(req.password)
                            it[UsersTable.fullName] = req.fullName?.trim()?.ifBlank { null }
                            it[UsersTable.role] = role
                            it[UsersTable.createdAt] = now
                            it[UsersTable.updatedAt] = now
                            it[UsersTable.isActive] = true
                        }

                        if (role == AppRoles.USER) {
                            RewardAccountsTable.insert {
                                it[RewardAccountsTable.id] = UUID.randomUUID().toString()
                                it[RewardAccountsTable.userId] = userId
                                it[RewardAccountsTable.totalPoints] = 0
                                it[RewardAccountsTable.usedPoints] = 0
                            }
                        }

                        if (role == AppRoles.EMPLOYEE) {
                            upsertEmployeeProfile(userId, req.employeeProfile, now, actorUserId)
                        }

                        selectAdminUserById(userId)
                    }

                    call.respond(HttpStatusCode.Created, AdminEnvelope(created, "Create user successfully"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid user payload")))
                }
            }
            put("/users/{id}") {
                val adminPrincipal = call.requireRole(AppRoles.ADMIN)
                val actorUserId = adminPrincipal.getUserId()
                val userId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "User ID is required"))
                val req = call.receive<AdminUpdateUserRequest>()
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

                try {
                    val updated = transaction {
                        val current = UsersTable.selectAll()
                            .where { (UsersTable.id eq userId) and UsersTable.deletedAt.isNull() }
                            .singleOrNull()
                            ?: throw IllegalArgumentException("User not found")

                        val nextPhone = req.phone?.trim()?.ifBlank { null } ?: current[UsersTable.phone]
                        val nextEmail = req.email?.trim()?.ifBlank { null } ?: current[UsersTable.email]
                        val nextRole = req.role?.trim()?.uppercase()?.ifBlank { current[UsersTable.role] } ?: current[UsersTable.role]

                        if (nextRole !in setOf(AppRoles.ADMIN, AppRoles.EMPLOYEE, AppRoles.USER)) {
                            throw IllegalArgumentException("Unsupported role")
                        }

                        val duplicatedPhone = UsersTable.selectAll()
                            .where { (UsersTable.phone eq nextPhone) and UsersTable.deletedAt.isNull() }
                            .any { it[UsersTable.id] != userId }
                        if (duplicatedPhone) throw IllegalArgumentException("Phone already exists")

                        val duplicatedEmail = nextEmail != null && UsersTable.selectAll()
                            .where { (UsersTable.email eq nextEmail) and UsersTable.deletedAt.isNull() }
                            .any { it[UsersTable.id] != userId }
                        if (duplicatedEmail) throw IllegalArgumentException("Email already exists")

                        UsersTable.update({ UsersTable.id eq userId }) {
                            it[fullName] = req.fullName?.trim()?.ifBlank { null } ?: current[UsersTable.fullName]
                            it[phone] = nextPhone
                            it[email] = nextEmail
                            it[role] = nextRole
                            it[updatedAt] = now
                        }

                        if (nextRole == AppRoles.USER) {
                            val hasRewardAccount = RewardAccountsTable.selectAll()
                                .where { RewardAccountsTable.userId eq userId }
                                .count() > 0
                            if (!hasRewardAccount) {
                                RewardAccountsTable.insert {
                                    it[RewardAccountsTable.id] = UUID.randomUUID().toString()
                                    it[RewardAccountsTable.userId] = userId
                                    it[RewardAccountsTable.totalPoints] = 0
                                    it[RewardAccountsTable.usedPoints] = 0
                                }
                            }
                        }

                        if (nextRole == AppRoles.EMPLOYEE) {
                            upsertEmployeeProfile(userId, req.employeeProfile, now, actorUserId)
                        }

                        selectAdminUserById(userId)
                    }

                    call.respond(AdminEnvelope(updated, "Update user successfully"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid user payload")))
                }
            }
            put("/users/{id}/ban") {
                val adminPrincipal = call.requireRole(AppRoles.ADMIN)
                val actorUserId = adminPrincipal.getUserId()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val userId = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "User ID is required"))
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                transaction {
                    val active = UsersTable.selectAll()
                        .where { (UsersTable.id eq userId) and UsersTable.deletedAt.isNull() }
                        .firstOrNull()
                        ?.get(UsersTable.isActive)
                        ?: throw IllegalArgumentException("User not found")
                    UsersTable.update({ UsersTable.id eq userId }) {
                        it[UsersTable.isActive] = !active
                        it[UsersTable.updatedAt] = now
                    }
                    logAccountActionInTx(
                        targetUserId = userId,
                        actorUserId = actorUserId,
                        action = if (active) "LOCK" else "UNLOCK",
                        reason = if (active) "Admin locked account" else "Admin unlocked account",
                        now = now
                    )
                }
                call.respond(AdminEnvelope(mapOf("id" to userId), "Toggle user active state successfully"))
            }
            delete("/users/{id}") {
                val principal = call.requireRole(AppRoles.ADMIN)
                val currentAdminId = principal.getUserId() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val userId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "User ID is required"))
                if (userId == currentAdminId) {
                    return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cannot delete your own account"))
                }

                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                try {
                    transaction {
                        val current = UsersTable.selectAll()
                            .where { (UsersTable.id eq userId) and UsersTable.deletedAt.isNull() }
                            .singleOrNull()
                            ?: throw IllegalArgumentException("User not found")

                        if (current[UsersTable.role] == AppRoles.ADMIN) {
                            val remainingActiveAdmins = UsersTable.selectAll()
                                .where {
                                    (UsersTable.role eq AppRoles.ADMIN) and
                                        (UsersTable.isActive eq true) and
                                        UsersTable.deletedAt.isNull() and
                                        (UsersTable.id neq userId)
                                }
                                .count()
                            if (remainingActiveAdmins == 0L) {
                                throw IllegalArgumentException("Cannot delete the last active admin")
                            }
                        }

                        UsersTable.update({ UsersTable.id eq userId }) {
                            it[UsersTable.fullName] = "Deleted account"
                            it[UsersTable.phone] = softDeletedPhone(userId)
                            it[UsersTable.email] = softDeletedEmail(userId)
                            it[UsersTable.isActive] = false
                            it[UsersTable.updatedAt] = now
                            it[UsersTable.deletedAt] = now
                        }

                        RefreshTokensTable.update({ RefreshTokensTable.userId eq userId }) {
                            it[RefreshTokensTable.revokedAt] = now
                        }
                        logAccountActionInTx(
                            targetUserId = userId,
                            actorUserId = currentAdminId,
                            action = "SOFT_DELETE",
                            reason = "Admin soft deleted account",
                            now = now
                        )
                    }
                    call.respond(AdminEnvelope(mapOf("id" to userId), "Delete user successfully"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Cannot delete user")))
                }
            }
            post("/users/{id}/reset-password") {
                val adminPrincipal = call.requireRole(AppRoles.ADMIN)
                val actorUserId = adminPrincipal.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val userId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "User ID is required"))
                val request = call.receive<AdminResetPasswordRequest>()
                if (request.newPassword.length < 6) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Password must be at least 6 characters"))
                }
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                transaction {
                    val exists = UsersTable.selectAll()
                        .where { (UsersTable.id eq userId) and UsersTable.deletedAt.isNull() }
                        .count() > 0
                    if (!exists) throw IllegalArgumentException("User not found")
                    UsersTable.update({ UsersTable.id eq userId }) {
                        it[UsersTable.password] = PasswordHelper.hash(request.newPassword)
                        it[UsersTable.updatedAt] = now
                    }
                    logAccountActionInTx(
                        targetUserId = userId,
                        actorUserId = actorUserId,
                        action = "RESET_PASSWORD",
                        reason = "Admin reset password",
                        now = now
                    )
                }
                call.respond(AdminEnvelope(mapOf("id" to userId), "Reset password successfully"))
            }

            // Product delete requests
            get("/product-delete-requests") {
                call.requireRole(AppRoles.ADMIN)
                val status = call.request.queryParameters["status"]
                val rows = listDeleteRequests(status)
                call.respond(AdminEnvelope(rows, "Get product delete requests successfully"))
            }
            post("/product-delete-requests/{id}/review") {
                call.requireRole(AppRoles.ADMIN)
                val principal = call.principal<JWTPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val adminUserId = principal.getUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val requestId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Request ID is required"))
                val payload = call.receive<AdminReviewDeleteRequest>()

                val result = reviewDeleteRequest(
                    requestId,
                    adminUserId,
                    payload.approve
                )
                call.respond(AdminEnvelope(result, if (payload.approve) "Approved delete request" else "Rejected delete request"))
            }
            // Shops - removed
            get("/shops") {
                call.respond(mapOf("message" to "Shop management removed"))
            }
            put("/shops/{id}/approve") {
                call.respond(mapOf("message" to "Shop management removed"))
            }
            put("/shops/{id}/reject") {
                call.respond(mapOf("message" to "Shop management removed"))
            }
            // All orders
            get("/orders") {
                call.requireRole(AppRoles.ADMIN)
                call.respond(mapOf("message" to "TODO: tất cả đơn hàng"))
            }
            // Banners
            get("/banners") {
                call.requireRole(AppRoles.ADMIN)
                val banners = transaction {
                    BannersTable
                        .selectAll()
                        .orderBy(BannersTable.sortOrder to SortOrder.ASC)
                        .map { it.toBannerDto() }
                }
                call.respond(AdminEnvelope(banners, "Get banners successfully"))
            }
            post("/banners") {
                call.requireRole(AppRoles.ADMIN)
                val req = call.receive<AdminBannerRequest>()
                if (req.imageUrl.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Image URL is required"))
                }

                try {
                    val created = transaction {
                        val id = UUID.randomUUID().toString()
                        BannersTable.insert {
                            it[BannersTable.id] = id
                            it[BannersTable.imageUrl] = req.imageUrl.trim()
                            it[BannersTable.linkUrl] = req.linkUrl.trimToNull()
                            it[BannersTable.title] = req.title.trimToNull()
                            it[BannersTable.description] = req.description.trimToNull()
                            it[BannersTable.sortOrder] = req.sortOrder
                            it[BannersTable.isActive] = req.isActive
                            it[BannersTable.startDt] = parseAdminDateTime(req.startDt)
                            it[BannersTable.endDt] = parseAdminDateTime(req.endDt)
                        }
                        BannersTable.selectAll().where { BannersTable.id eq id }.single().toBannerDto()
                    }
                    call.respond(HttpStatusCode.Created, AdminEnvelope(created, "Create banner successfully"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid banner payload")))
                }
            }
            put("/banners/{id}") {
                call.requireRole(AppRoles.ADMIN)
                val id = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Banner ID is required"))
                val req = call.receive<AdminBannerRequest>()
                if (req.imageUrl.isBlank()) {
                    return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Image URL is required"))
                }

                try {
                    val updated = transaction {
                        val exists = BannersTable.selectAll().where { BannersTable.id eq id }.count() > 0
                        if (!exists) throw IllegalArgumentException("Banner not found")

                        BannersTable.update({ BannersTable.id eq id }) {
                            it[BannersTable.imageUrl] = req.imageUrl.trim()
                            it[BannersTable.linkUrl] = req.linkUrl.trimToNull()
                            it[BannersTable.title] = req.title.trimToNull()
                            it[BannersTable.description] = req.description.trimToNull()
                            it[BannersTable.sortOrder] = req.sortOrder
                            it[BannersTable.isActive] = req.isActive
                            it[BannersTable.startDt] = parseAdminDateTime(req.startDt)
                            it[BannersTable.endDt] = parseAdminDateTime(req.endDt)
                        }
                        BannersTable.selectAll().where { BannersTable.id eq id }.single().toBannerDto()
                    }
                    call.respond(AdminEnvelope(updated, "Update banner successfully"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid banner payload")))
                }
            }
            delete("/banners/{id}") {
                call.requireRole(AppRoles.ADMIN)
                val id = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Banner ID is required"))
                val deleted = transaction {
                    BannersTable.deleteWhere { BannersTable.id eq id }
                }
                if (deleted == 0) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Banner not found"))
                } else {
                    call.respond(AdminEnvelope(mapOf("id" to id), "Delete banner successfully"))
                }
            }
            // Rewards config
            get("/rewards/config") {
                call.requireRole(AppRoles.ADMIN)
                call.respond(mapOf("message" to "TODO: cấu hình điểm thưởng"))
            }
            put("/rewards/config") {
                call.requireRole(AppRoles.ADMIN)
                call.respond(mapOf("message" to "TODO: cập nhật cấu hình"))
            }
        }
    }
}
