package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.RefreshTokensTable
import com.example.nhathuoc.database.tables.RewardAccountsTable
import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.plugins.BadRequestException
import com.example.nhathuoc.plugins.ConflictException
import com.example.nhathuoc.plugins.NotFoundException
import com.example.nhathuoc.util.JwtHelper
import com.example.nhathuoc.util.PasswordHelper
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlinx.datetime.*

// ─── DTOs ────────────────────────────────────────────────────────

@Serializable
data class RegisterRequest(
    val phone: String,
    val password: String,
    val fullName: String? = null
)

@Serializable
data class LoginRequest(
    val phone: String,
    val password: String
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String
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

// ─── Routes ──────────────────────────────────────────────────────

fun Route.authRoutes() {
    route("/auth") {

        // POST /api/v1/auth/register
        post("/register") {
            val req = call.receive<RegisterRequest>()

            if (req.phone.isBlank() || req.password.isBlank()) {
                throw BadRequestException("Số điện thoại và mật khẩu không được để trống")
            }
            if (req.password.length < 6) {
                throw BadRequestException("Mật khẩu phải có ít nhất 6 ký tự")
            }

            val existingUser = transaction {
                UsersTable.selectAll()
                    .where { UsersTable.phone eq req.phone }
                    .firstOrNull()
            }

            if (existingUser != null) {
                throw ConflictException("Số điện thoại đã được đăng ký")
            }

            val userId = UUID.randomUUID().toString()
            val hashedPassword = PasswordHelper.hash(req.password)

            transaction {
                UsersTable.insert {
                    it[id]       = userId
                    it[phone]    = req.phone
                    it[password] = hashedPassword
                    it[fullName] = req.fullName
                    it[role]     = "USER"
                }
                // Tạo reward account tự động
                RewardAccountsTable.insert {
                    it[id]     = UUID.randomUUID().toString()
                    it[userId] = userId
                }
            }

            val accessToken  = JwtHelper.generateAccessToken(userId, "USER")
            val refreshToken = JwtHelper.generateRefreshToken(userId)
            val expiresAt    = Clock.System.now().plus(7, DateTimeUnit.DAY, TimeZone.UTC)
                .toLocalDateTime(TimeZone.UTC)

            transaction {
                RefreshTokensTable.insert {
                    it[id]                          = UUID.randomUUID().toString()
                    it[RefreshTokensTable.userId]   = userId
                    it[token]                       = refreshToken
                    it[RefreshTokensTable.expiresAt] = expiresAt
                }
            }

            call.respond(
                HttpStatusCode.Created,
                AuthResponse(
                    accessToken  = accessToken,
                    refreshToken = refreshToken,
                    user = UserResponse(
                        id       = userId,
                        phone    = req.phone,
                        fullName = req.fullName,
                        email    = null,
                        role     = "USER",
                        avatarUrl = null
                    )
                )
            )
        }

        // POST /api/v1/auth/login
        post("/login") {
            val req = call.receive<LoginRequest>()

            val user = transaction {
                UsersTable.selectAll()
                    .where { UsersTable.phone eq req.phone }
                    .firstOrNull()
            } ?: throw NotFoundException("Tài khoản không tồn tại")

            if (!PasswordHelper.verify(req.password, user[UsersTable.password])) {
                throw BadRequestException("Mật khẩu không chính xác")
            }

            if (!user[UsersTable.isActive]) {
                throw BadRequestException("Tài khoản đã bị khoá. Vui lòng liên hệ hỗ trợ")
            }

            val userId       = user[UsersTable.id]
            val role         = user[UsersTable.role]
            val accessToken  = JwtHelper.generateAccessToken(userId, role)
            val refreshToken = JwtHelper.generateRefreshToken(userId)
            val expiresAt    = Clock.System.now().plus(7, DateTimeUnit.DAY, TimeZone.UTC)
                .toLocalDateTime(TimeZone.UTC)

            transaction {
                // Xoá refresh tokens cũ của user này
                RefreshTokensTable.deleteWhere { RefreshTokensTable.userId eq userId }
                RefreshTokensTable.insert {
                    it[id]                          = UUID.randomUUID().toString()
                    it[RefreshTokensTable.userId]   = userId
                    it[token]                       = refreshToken
                    it[RefreshTokensTable.expiresAt] = expiresAt
                }
            }

            call.respond(
                AuthResponse(
                    accessToken  = accessToken,
                    refreshToken = refreshToken,
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

            val tokenRow = transaction {
                RefreshTokensTable.selectAll()
                    .where { RefreshTokensTable.token eq req.refreshToken }
                    .firstOrNull()
            } ?: throw BadRequestException("Refresh token không hợp lệ")

            val userId = tokenRow[RefreshTokensTable.userId]
            val user = transaction {
                UsersTable.selectAll().where { UsersTable.id eq userId }.firstOrNull()
            } ?: throw NotFoundException("Người dùng không tồn tại")

            val role        = user[UsersTable.role]
            val newAccess   = JwtHelper.generateAccessToken(userId, role)
            val newRefresh  = JwtHelper.generateRefreshToken(userId)
            val expiresAt   = Clock.System.now().plus(7, DateTimeUnit.DAY, TimeZone.UTC)
                .toLocalDateTime(TimeZone.UTC)

            transaction {
                RefreshTokensTable.deleteWhere { RefreshTokensTable.userId eq userId }
                RefreshTokensTable.insert {
                    it[id]                          = UUID.randomUUID().toString()
                    it[RefreshTokensTable.userId]   = userId
                    it[token]                       = newRefresh
                    it[RefreshTokensTable.expiresAt] = expiresAt
                }
            }

            call.respond(mapOf("accessToken" to newAccess, "refreshToken" to newRefresh))
        }

        // POST /api/v1/auth/logout
        authenticate("auth-jwt") {
            post("/logout") {
                val principal = call.principal<JWTPrincipal>()
                val userId    = principal?.payload?.getClaim("userId")?.asString() ?: return@post

                transaction {
                    RefreshTokensTable.deleteWhere { RefreshTokensTable.userId eq userId }
                }

                call.respond(mapOf("message" to "Đăng xuất thành công"))
            }
        }
    }
}
