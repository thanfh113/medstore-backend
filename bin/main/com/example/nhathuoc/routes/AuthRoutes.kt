package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.RefreshTokensTable
import com.example.nhathuoc.database.tables.RewardAccountsTable
import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.plugins.BadRequestException
import com.example.nhathuoc.plugins.ConflictException
import com.example.nhathuoc.plugins.NotFoundException
import com.example.nhathuoc.util.EmailHelper
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
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.days

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
    val avatarUrl: String?
)

// â”€â”€â”€ Utilities â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
                    it[RefreshTokensTable.token]     = ""
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
                        id        = userId,
                        phone     = req.phone,
                        fullName  = req.fullName,
                        email     = req.email,
                        role      = "USER",
                        avatarUrl = null
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

            if (!PasswordHelper.verify(req.password, user[UsersTable.password]))
                throw BadRequestException("Mật khẩu không chính xác")
            if (!user[UsersTable.isActive])
                throw BadRequestException("Tài khoản đã bị khóa. Vui lòng liên hệ hỗ trợ")

            val userId    = user[UsersTable.id]
            val role      = user[UsersTable.role]
            call.application.environment.log.info("Desktop/Auth login success: userId={}, role={}", userId, role)
            val expiresAt = Clock.System.now().plus(7.days).toLocalDateTime(TimeZone.UTC)
            val newRefresh = JwtHelper.generateRefreshToken(userId)

            transaction {
                RefreshTokensTable.deleteWhere { RefreshTokensTable.userId eq userId }
                RefreshTokensTable.insert {
                    it[RefreshTokensTable.id]        = UUID.randomUUID().toString()
                    it[RefreshTokensTable.userId]    = userId
                    it[RefreshTokensTable.token]     = ""
                    it[RefreshTokensTable.tokenHash] = hashRefreshToken(newRefresh)
                    it[RefreshTokensTable.expiresAt] = expiresAt
                }
            }

            call.respond(
                AuthResponse(
                    accessToken  = JwtHelper.generateAccessToken(userId, role),
                    refreshToken = newRefresh,
                    user = UserResponse(
                        id        = userId,
                        phone     = user[UsersTable.phone],
                        fullName  = user[UsersTable.fullName],
                        email     = user[UsersTable.email],
                        role      = role,
                        avatarUrl = user[UsersTable.avatarUrl]
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
                    .where {
                        (RefreshTokensTable.tokenHash eq hashedToken) or
                        (RefreshTokensTable.token eq hashedToken) or
                        (RefreshTokensTable.token eq req.refreshToken)
                    }
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
                    it[RefreshTokensTable.token]     = ""
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

