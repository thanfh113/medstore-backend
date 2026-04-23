package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.EmployeeProfilesTable
import com.example.nhathuoc.database.tables.ExpensesTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.RefreshTokensTable
import com.example.nhathuoc.database.tables.RewardAccountsTable
import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.service.ProductDeleteRequestDto
import com.example.nhathuoc.service.ProductService
import com.example.nhathuoc.util.AppRoles
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
    val qualificationVerified: Boolean,
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

private const val DEFAULT_EMPLOYEE_QUALIFICATION_TITLE = "Cần cập nhật hồ sơ chuyên môn"

private fun String?.trimToNull(): String? = this?.trim()?.ifBlank { null }

private fun softDeletedEmail(userId: String): String = "deleted-$userId@deleted.medstore.local"

private fun softDeletedPhone(userId: String): String = "del-${userId.take(11)}"

private fun ResultRow.toEmployeeProfileDto(): AdminEmployeeProfileDto? {
    val profileId = getOrNull(EmployeeProfilesTable.id) ?: return null
    return AdminEmployeeProfileDto(
        id = profileId,
        qualificationTitle = this[EmployeeProfilesTable.qualificationTitle],
        qualificationSpecialty = this[EmployeeProfilesTable.qualificationSpecialty],
        qualificationInstitution = this[EmployeeProfilesTable.qualificationInstitution],
        qualificationDocumentUrl = this[EmployeeProfilesTable.qualificationDocumentUrl],
        qualificationDocumentPublicId = this[EmployeeProfilesTable.qualificationDocumentPublicId],
        qualificationVerified = this[EmployeeProfilesTable.qualificationVerified],
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
    now: LocalDateTime
) {
    val existing = EmployeeProfilesTable.selectAll()
        .where { EmployeeProfilesTable.userId eq userId }
        .singleOrNull()

    if (existing == null) {
        EmployeeProfilesTable.insert {
            it[EmployeeProfilesTable.id] = UUID.randomUUID().toString()
            it[EmployeeProfilesTable.userId] = userId
            it[EmployeeProfilesTable.qualificationTitle] =
                request?.qualificationTitle.trimToNull() ?: DEFAULT_EMPLOYEE_QUALIFICATION_TITLE
            it[EmployeeProfilesTable.qualificationSpecialty] = request?.qualificationSpecialty.trimToNull()
            it[EmployeeProfilesTable.qualificationInstitution] = request?.qualificationInstitution.trimToNull()
            it[EmployeeProfilesTable.qualificationDocumentUrl] = request?.qualificationDocumentUrl.trimToNull()
            it[EmployeeProfilesTable.qualificationDocumentPublicId] = request?.qualificationDocumentPublicId.trimToNull()
            it[EmployeeProfilesTable.qualificationVerified] = request?.qualificationVerified ?: false
            it[EmployeeProfilesTable.qualificationNote] = request?.qualificationNote.trimToNull()
            it[EmployeeProfilesTable.createdAt] = now
            it[EmployeeProfilesTable.updatedAt] = now
        }
        return
    }

    if (request == null) return

    EmployeeProfilesTable.update({ EmployeeProfilesTable.userId eq userId }) {
        it[EmployeeProfilesTable.qualificationTitle] =
            request.qualificationTitle.trimToNull() ?: existing[EmployeeProfilesTable.qualificationTitle]
        it[EmployeeProfilesTable.qualificationSpecialty] = request.qualificationSpecialty.trimToNull()
        it[EmployeeProfilesTable.qualificationInstitution] = request.qualificationInstitution.trimToNull()
        it[EmployeeProfilesTable.qualificationDocumentUrl] = request.qualificationDocumentUrl.trimToNull()
        it[EmployeeProfilesTable.qualificationDocumentPublicId] = request.qualificationDocumentPublicId.trimToNull()
        it[EmployeeProfilesTable.qualificationVerified] =
            request.qualificationVerified ?: existing[EmployeeProfilesTable.qualificationVerified]
        it[EmployeeProfilesTable.qualificationNote] = request.qualificationNote.trimToNull()
        it[EmployeeProfilesTable.updatedAt] = now
    }
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

                    val expenseRows = ExpensesTable.selectAll()
                        .toList()
                    val approvedExpenses = expenseRows.filter { it[ExpensesTable.status] == "APPROVED" }
                    val totalExpenses = approvedExpenses.sumOf { it[ExpensesTable.amount].toDouble() }

                    FinanceSummaryDto(
                        shopId = null,
                        grossRevenue = grossRevenue,
                        onlineRevenue = onlineRevenue,
                        posRevenue = posRevenue,
                        totalDiscount = totalDiscount,
                        totalExpenses = totalExpenses,
                        netProfit = grossRevenue - totalExpenses,
                        successfulOrderCount = successfulOrders.size,
                        expenseCount = approvedExpenses.size
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
                call.requireRole(AppRoles.ADMIN)
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
                            upsertEmployeeProfile(userId, req.employeeProfile, now)
                        }

                        selectAdminUserById(userId)
                    }

                    call.respond(HttpStatusCode.Created, AdminEnvelope(created, "Create user successfully"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid user payload")))
                }
            }
            put("/users/{id}") {
                call.requireRole(AppRoles.ADMIN)
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
                            upsertEmployeeProfile(userId, req.employeeProfile, now)
                        }

                        selectAdminUserById(userId)
                    }

                    call.respond(AdminEnvelope(updated, "Update user successfully"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid user payload")))
                }
            }
            put("/users/{id}/ban") {
                call.requireRole(AppRoles.ADMIN)
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
                }
                call.respond(AdminEnvelope(mapOf("id" to userId), "Toggle user active state successfully"))
            }
            delete("/users/{id}") {
                call.requireRole(AppRoles.ADMIN)
                val principal = call.principal<JWTPrincipal>() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
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
                    }
                    call.respond(AdminEnvelope(mapOf("id" to userId), "Delete user successfully"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Cannot delete user")))
                }
            }
            post("/users/{id}/reset-password") {
                call.requireRole(AppRoles.ADMIN)
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
            post("/banners") {
                call.requireRole(AppRoles.ADMIN)
                call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: thêm banner"))
            }
            put("/banners/{id}") {
                call.requireRole(AppRoles.ADMIN)
                call.respond(mapOf("message" to "TODO: sửa banner"))
            }
            delete("/banners/{id}") {
                call.requireRole(AppRoles.ADMIN)
                call.respond(mapOf("message" to "TODO: xoá banner"))
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
