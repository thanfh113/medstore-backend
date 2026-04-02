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
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.time.Duration.Companion.days

// ─── DTOs ────────────────────────────────────────────────────────

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

// ─── Utilities ───────────────────────────────────────────────────

private fun isValidEmail(email: String): Boolean {
    val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    return emailRegex.matches(email)
}

// ─── Routes ──────────────────────────────────────────────────────

fun Route.authRoutes() {
    route("/auth") {

        // POST /api/v1/auth/register
        post("/register") {
            val req = call.receive<RegisterRequest>()

            if (req.phone.isBlank() || req.email.isBlank() || req.password.isBlank())
                throw BadRequestException("Số điện thoại, email và mật khẩu không được để trống")
            if (!isValidEmail(req.email))
                throw BadRequestException("Email không hợp lệ. Vui lòng sử dụng định dạng @domain.com")
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
                    it[RefreshTokensTable.token]     = JwtHelper.generateRefreshToken(userId)
                    it[RefreshTokensTable.expiresAt] = expiresAt
                }
            }

            call.respond(
                HttpStatusCode.Created,
                AuthResponse(
                    accessToken  = JwtHelper.generateAccessToken(userId, "USER"),
                    refreshToken = JwtHelper.generateRefreshToken(userId),
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
            val req = call.receive<LoginRequest>()

            val user = transaction {
                UsersTable.selectAll()
                    .where { (UsersTable.email eq req.credential) or (UsersTable.phone eq req.credential) }
                    .firstOrNull()
            } ?: throw NotFoundException("Tài khoản không tồn tại")

            if (!PasswordHelper.verify(req.password, user[UsersTable.password]))
                throw BadRequestException("Mật khẩu không chính xác")
            if (!user[UsersTable.isActive])
                throw BadRequestException("Tài khoản đã bị khoá. Vui lòng liên hệ hỗ trợ")

            val userId    = user[UsersTable.id]
            val role      = user[UsersTable.role]
            val expiresAt = Clock.System.now().plus(7.days).toLocalDateTime(TimeZone.UTC)
            val newRefresh = JwtHelper.generateRefreshToken(userId)

            transaction {
                RefreshTokensTable.deleteWhere { RefreshTokensTable.userId eq userId }
                RefreshTokensTable.insert {
                    it[RefreshTokensTable.id]        = UUID.randomUUID().toString()
                    it[RefreshTokensTable.userId]    = userId
                    it[RefreshTokensTable.token]     = newRefresh
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

            val tokenRow = transaction {
                RefreshTokensTable.selectAll()
                    .where { RefreshTokensTable.token eq req.refreshToken }
                    .firstOrNull()
            } ?: throw BadRequestException("Refresh token không hợp lệ")

            val userId  = tokenRow[RefreshTokensTable.userId]
            val user    = transaction {
                UsersTable.selectAll().where { UsersTable.id eq userId }.firstOrNull()
            } ?: throw NotFoundException("Người dùng không tồn tại")

            val role       = user[UsersTable.role]
            val newRefresh = JwtHelper.generateRefreshToken(userId)
            val expiresAt  = Clock.System.now().plus(7.days).toLocalDateTime(TimeZone.UTC)

            transaction {
                RefreshTokensTable.deleteWhere { RefreshTokensTable.userId eq userId }
                RefreshTokensTable.insert {
                    it[RefreshTokensTable.id]        = UUID.randomUUID().toString()
                    it[RefreshTokensTable.userId]    = userId
                    it[RefreshTokensTable.token]     = newRefresh
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
