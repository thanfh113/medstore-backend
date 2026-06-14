package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.PasswordResetTokensTable
import com.example.nhathuoc.database.tables.RefreshTokensTable
import com.example.nhathuoc.database.tables.RewardAccountsTable
import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.plugins.BadRequestException
import com.example.nhathuoc.plugins.ConflictException
import com.example.nhathuoc.plugins.NotFoundException
import com.example.nhathuoc.util.EmailHelper
import com.example.nhathuoc.util.EmailService
import com.example.nhathuoc.util.JwtHelper
import com.example.nhathuoc.util.PasswordHelper
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

// â”€â”€â”€ DTOs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Serializable
data class RegisterRequest(
    val phone: String,
    val email: String,
    val password: String,
    val fullName: String? = null
)

@Serializable
data class LoginRequest(
    val credential: String,  // email hoặc phone
    val password: String
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ResetPasswordRequest(
    val email: String,
    val otp: String,
    val newPassword: String
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserResponse
)

@Serializable
data class UserResponse(
    val id: String,
    val phone: String,
    val fullName: String?,
    val email: String?,
    val role: String,
    val avatarUrl: String?,
    val gender: Int? = null,
    val dateOfBirth: String? = null
)

// â”€â”€â”€ Utilities â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

internal fun genderToInt(gender: String?): Int? = when (gender) {
    "Nam" -> 1; "Nữ" -> 2; "Khác" -> 3; else -> null
}

internal fun genderToString(gender: Int?): String? = when (gender) {
    1 -> "Nam"; 2 -> "Nữ"; 3 -> "Khác"; else -> null
}

private fun hashRefreshToken(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

// â”€â”€â”€ Routes â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

fun Route.authRoutes() {
    route("/auth") {

        // POST /api/v1/auth/register
        post("/register") {
            val rawReq = call.receive<RegisterRequest>()
            val req = rawReq.copy(
                phone = rawReq.phone.trim(),
                email = EmailHelper.normalize(rawReq.email) ?: ""
            )

            if (req.phone.isBlank() || req.email.isBlank() || req.password.isBlank())
                throw BadRequestException("Số điện thoại, email và mật khẩu không được để trống")
            if (!EmailHelper.isValid(req.email))
                throw BadRequestException("Email không hợp lệ. Ví dụ: ten@example.com")
            if (req.password.length < 6)
                throw BadRequestException("Mật khẩu phải có ít nhất 6 ký tự")

            val existsPhone = transaction {
                UsersTable.selectAll().where { UsersTable.phone eq req.phone }.count() > 0
            }
            if (existsPhone) throw ConflictException("Số điện thoại đã được đăng ký")

            val existsEmail = transaction {
                UsersTable.selectAll().where { UsersTable.email eq req.email }.count() > 0
            }
            if (existsEmail) throw ConflictException("Email đã được đăng ký")

            val userId       = UUID.randomUUID().toString()
            val hashedPwd    = PasswordHelper.hash(req.password)
            val refreshToken = JwtHelper.generateRefreshToken(userId)
            val expiresAt    = Clock.System.now().plus(7.days)
                .toLocalDateTime(TimeZone.UTC)

            transaction {
                UsersTable.insert {
                    it[UsersTable.id]       = userId
                    it[UsersTable.phone]    = req.phone
                    it[UsersTable.email]    = req.email
                    it[UsersTable.password] = hashedPwd
                    it[UsersTable.fullName] = req.fullName
                    it[UsersTable.role]     = "USER"
                }
                RewardAccountsTable.insert {
                    it[RewardAccountsTable.id]     = UUID.randomUUID().toString()
                    it[RewardAccountsTable.userId] = userId
                }
                RefreshTokensTable.insert {
                    it[RefreshTokensTable.id]        = UUID.randomUUID().toString()
                    it[RefreshTokensTable.userId]    = userId
                    it[RefreshTokensTable.tokenHash] = hashRefreshToken(refreshToken)
                    it[RefreshTokensTable.expiresAt] = expiresAt
                }
            }

            call.respond(
                HttpStatusCode.Created,
                AuthResponse(
                    accessToken  = JwtHelper.generateAccessToken(userId, "USER"),
                    refreshToken = refreshToken,
                    user = UserResponse(
                        id          = userId,
                        phone       = req.phone,
                        fullName    = req.fullName,
                        email       = req.email,
                        role        = "USER",
                        avatarUrl   = null,
                        gender      = null,
                        dateOfBirth = null
                    )
                )
            )
        }

        // POST /api/v1/auth/login
        post("/login") {
            val rawReq = call.receive<LoginRequest>()
            val req = rawReq.copy(credential = rawReq.credential.trim())
            call.application.environment.log.info("Desktop/Auth login attempt: credential={}", req.credential)

            val user = transaction {
                UsersTable.selectAll()
                    .where {
                        ((UsersTable.email eq req.credential) or (UsersTable.phone eq req.credential)) and
                            UsersTable.deletedAt.isNull()
                    }
                    .firstOrNull()
            } ?: throw NotFoundException("Tài khoản không tồn tại")

            // Check lock before password so locked accounts aren't told "wrong password"
            if (!user[UsersTable.isActive])
                throw BadRequestException("Tài khoản đã bị khóa. Vui lòng liên hệ hỗ trợ")

            if (!PasswordHelper.verify(req.password, user[UsersTable.password])) {
                val newAttempts = user[UsersTable.failedLoginAttempts] + 1
                transaction {
                    UsersTable.update({ UsersTable.id eq user[UsersTable.id] }) {
                        it[failedLoginAttempts] = newAttempts
                        if (newAttempts >= 5) it[isActive] = false
                    }
                }
                if (newAttempts >= 5)
                    throw BadRequestException("Tài khoản đã bị khóa do nhập sai mật khẩu quá 5 lần. Vui lòng liên hệ hỗ trợ.")
                throw BadRequestException("Mật khẩu không chính xác. Còn ${5 - newAttempts} lần thử.")
            }

            val userId    = user[UsersTable.id]
            val role      = user[UsersTable.role]
            call.application.environment.log.info("Desktop/Auth login success: userId={}, role={}", userId, role)
            val expiresAt = Clock.System.now().plus(7.days).toLocalDateTime(TimeZone.UTC)
            val newRefresh = JwtHelper.generateRefreshToken(userId)

            transaction {
                // Reset failed attempts and update last login time on success
                UsersTable.update({ UsersTable.id eq userId }) {
                    it[failedLoginAttempts] = 0
                    it[lastLoginAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                }
                RefreshTokensTable.deleteWhere { RefreshTokensTable.userId eq userId }
                RefreshTokensTable.insert {
                    it[RefreshTokensTable.id]        = UUID.randomUUID().toString()
                    it[RefreshTokensTable.userId]    = userId
                    it[RefreshTokensTable.tokenHash] = hashRefreshToken(newRefresh)
                    it[RefreshTokensTable.expiresAt] = expiresAt
                }
            }

            call.respond(
                AuthResponse(
                    accessToken  = JwtHelper.generateAccessToken(userId, role),
                    refreshToken = newRefresh,
                    user = UserResponse(
                        id          = userId,
                        phone       = user[UsersTable.phone],
                        fullName    = user[UsersTable.fullName],
                        email       = user[UsersTable.email],
                        role        = role,
                        avatarUrl   = user[UsersTable.avatarUrl],
                        gender      = genderToInt(user[UsersTable.gender]),
                        dateOfBirth = user[UsersTable.dateOfBirth]
                    )
                )
            )
        }

        // POST /api/v1/auth/refresh
        post("/refresh") {
            val req = call.receive<RefreshTokenRequest>()
            val decoded = try {
                JwtHelper.verifyRefreshToken(req.refreshToken)
            } catch (e: Exception) {
                throw BadRequestException("Refresh token không hợp lệ")
            }

            if (decoded.getClaim("type").asString() != "refresh") {
                throw BadRequestException("Token không phải refresh token")
            }

            val tokenUserId = decoded.getClaim("userId").asString()
                ?: throw BadRequestException("Refresh token thiếu userId")
            val hashedToken = hashRefreshToken(req.refreshToken)
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

            val tokenRow = transaction {
                RefreshTokensTable.selectAll()
                    .where { RefreshTokensTable.tokenHash eq hashedToken }
                    .firstOrNull()
            } ?: throw BadRequestException("Refresh token khĂ´ng há»£p lá»‡")

            val userId  = tokenRow[RefreshTokensTable.userId]
            if (userId != tokenUserId) {
                throw BadRequestException("Refresh token không hợp lệ")
            }
            if (tokenRow[RefreshTokensTable.expiresAt] <= now) {
                transaction {
                    RefreshTokensTable.update({ RefreshTokensTable.id eq tokenRow[RefreshTokensTable.id] }) {
                        it[RefreshTokensTable.revokedAt] = now
                    }
                }
                throw BadRequestException("Refresh token đã hết hạn")
            }
            if (tokenRow[RefreshTokensTable.revokedAt] != null) {
                throw BadRequestException("Refresh token đã bị thu hồi")
            }
            val user = transaction {
                UsersTable.selectAll()
                    .where { (UsersTable.id eq userId) and UsersTable.deletedAt.isNull() }
                    .firstOrNull()
            } ?: throw NotFoundException("Người dùng không tồn tại")

            if (!user[UsersTable.isActive]) {
                throw BadRequestException("Tài khoản đã bị khóa. Vui lòng liên hệ hỗ trợ")
            }

            val role       = user[UsersTable.role]
            val newRefresh = JwtHelper.generateRefreshToken(userId)
            val expiresAt  = Clock.System.now().plus(7.days).toLocalDateTime(TimeZone.UTC)

            transaction {
                RefreshTokensTable.update({ RefreshTokensTable.userId eq userId }) {
                    it[RefreshTokensTable.revokedAt] = now
                }
                RefreshTokensTable.insert {
                    it[RefreshTokensTable.id]        = UUID.randomUUID().toString()
                    it[RefreshTokensTable.userId]    = userId
                    it[RefreshTokensTable.tokenHash] = hashRefreshToken(newRefresh)
                    it[RefreshTokensTable.expiresAt] = expiresAt
                }
            }

            call.respond(
                mapOf(
                    "accessToken"  to JwtHelper.generateAccessToken(userId, role),
                    "refreshToken" to newRefresh
                )
            )
        }

        // POST /api/v1/auth/forgot-password
        post("/forgot-password") {
            val req = call.receive<ForgotPasswordRequest>()
            val normalizedEmail = EmailHelper.normalize(req.email)
                ?: throw BadRequestException("Email không hợp lệ")
            if (!EmailHelper.isValid(normalizedEmail))
                throw BadRequestException("Email không hợp lệ")

            val user = transaction {
                UsersTable.selectAll()
                    .where { UsersTable.email eq normalizedEmail and UsersTable.deletedAt.isNull() }
                    .firstOrNull()
            } ?: throw BadRequestException("Email này chưa được đăng ký trong hệ thống")

            val userId = user[UsersTable.id]
            val otp = (100000..999999).random().toString()
            val expiresAt = Clock.System.now().plus(10.minutes).toLocalDateTime(TimeZone.UTC)

            transaction {
                PasswordResetTokensTable.deleteWhere { PasswordResetTokensTable.userId eq userId }
                PasswordResetTokensTable.insert {
                    it[PasswordResetTokensTable.id]        = UUID.randomUUID().toString()
                    it[PasswordResetTokensTable.userId]    = userId
                    it[PasswordResetTokensTable.otp]       = otp
                    it[PasswordResetTokensTable.expiresAt] = expiresAt
                }
            }
            try {
                EmailService.sendPasswordResetOtp(normalizedEmail, otp)
            } catch (e: Exception) {
                call.application.environment.log.error("Failed to send OTP email to $normalizedEmail: ${e.message}")
                throw BadRequestException("Không thể gửi email. Vui lòng thử lại sau.")
            }
            call.respond(mapOf("message" to "Mã OTP đã được gửi. Kiểm tra hộp thư của bạn."))
        }

        // POST /api/v1/auth/reset-password
        post("/reset-password") {
            val req = call.receive<ResetPasswordRequest>()
            val normalizedEmail = EmailHelper.normalize(req.email)
                ?: throw BadRequestException("Email không hợp lệ")

            if (req.otp.isBlank() || req.newPassword.isBlank())
                throw BadRequestException("OTP và mật khẩu mới không được để trống")
            if (req.newPassword.length < 6)
                throw BadRequestException("Mật khẩu mới phải có ít nhất 6 ký tự")

            val user = transaction {
                UsersTable.selectAll()
                    .where { UsersTable.email eq normalizedEmail and UsersTable.deletedAt.isNull() }
                    .firstOrNull()
            } ?: throw BadRequestException("Email không tồn tại trong hệ thống")

            val userId = user[UsersTable.id]
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

            val tokenRow = transaction {
                PasswordResetTokensTable.selectAll()
                    .where { PasswordResetTokensTable.userId eq userId and PasswordResetTokensTable.usedAt.isNull() }
                    .orderBy(PasswordResetTokensTable.createdAt, SortOrder.DESC)
                    .firstOrNull()
            } ?: throw BadRequestException("Mã OTP không hợp lệ hoặc đã hết hạn")

            if (tokenRow[PasswordResetTokensTable.expiresAt] <= now)
                throw BadRequestException("Mã OTP đã hết hạn. Vui lòng yêu cầu mã mới.")
            if (tokenRow[PasswordResetTokensTable.otp] != req.otp.trim())
                throw BadRequestException("Mã OTP không chính xác")

            transaction {
                UsersTable.update({ UsersTable.id eq userId }) {
                    it[password]  = PasswordHelper.hash(req.newPassword)
                    it[updatedAt] = now
                    it[failedLoginAttempts] = 0
                    it[isActive]  = true
                }
                PasswordResetTokensTable.update({ PasswordResetTokensTable.id eq tokenRow[PasswordResetTokensTable.id] }) {
                    it[usedAt] = now
                }
                RefreshTokensTable.update({ RefreshTokensTable.userId eq userId }) {
                    it[RefreshTokensTable.revokedAt] = now
                }
            }
            call.respond(mapOf("message" to "Đặt lại mật khẩu thành công. Vui lòng đăng nhập lại."))
        }

        // POST /api/v1/auth/logout
        authenticate("auth-jwt") {
            post("/change-password") {
                val principal = call.principal<JWTPrincipal>()
                    ?: throw BadRequestException("Unauthorized")
                val userId = principal.payload.getClaim("userId").asString()
                    ?: throw BadRequestException("Invalid token")
                val request = call.receive<ChangePasswordRequest>()

                if (request.currentPassword.isBlank() || request.newPassword.isBlank()) {
                    throw BadRequestException("Mật khẩu hiện tại và mật khẩu mới không được để trống")
                }
                if (request.newPassword.length < 6) {
                    throw BadRequestException("Mật khẩu mới phải có ít nhất 6 ký tự")
                }

                val user = transaction {
                    UsersTable.selectAll()
                        .where { UsersTable.id eq userId }
                        .firstOrNull()
                } ?: throw NotFoundException("Người dùng không tồn tại")

                if (!PasswordHelper.verify(request.currentPassword, user[UsersTable.password])) {
                    throw BadRequestException("Mật khẩu hiện tại không chính xác")
                }
                if (PasswordHelper.verify(request.newPassword, user[UsersTable.password])) {
                    throw BadRequestException("Mật khẩu mới không được trùng mật khẩu hiện tại")
                }

                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                transaction {
                    UsersTable.update({ UsersTable.id eq userId }) {
                        it[password] = PasswordHelper.hash(request.newPassword)
                        it[updatedAt] = now
                    }
                    RefreshTokensTable.update({ RefreshTokensTable.userId eq userId }) {
                        it[revokedAt] = now
                    }
                }
                call.application.environment.log.info("Desktop/Auth password changed: userId={}", userId)

                call.respond(
                    mapOf(
                        "message" to "Đổi mật khẩu thành công. Vui lòng đăng nhập lại."
                    )
                )
            }

            post("/logout") {
                val principal = call.principal<JWTPrincipal>()
                val userId    = principal?.payload?.getClaim("userId")?.asString() ?: return@post
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                transaction {
                    RefreshTokensTable.update({ RefreshTokensTable.userId eq userId }) {
                        it[RefreshTokensTable.revokedAt] = now
                    }
                }
                call.respond(mapOf("message" to "Đăng xuất thành công"))
            }
        }
    }
}

