package com.example.nhathuoc.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.util.Env
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.http.*
import io.ktor.server.response.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun Application.configureAuthentication() {
    val jwtSecret   = Env.get("JWT_SECRET") ?: environment.config.property("jwt.secret").getString()
    val jwtIssuer   = Env.get("JWT_ISSUER") ?: environment.config.property("jwt.issuer").getString()
    val jwtAudience = Env.get("JWT_AUDIENCE") ?: environment.config.property("jwt.audience").getString()

    install(Authentication) {
        jwt("auth-jwt") {
            realm = "nhathuoc"
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withIssuer(jwtIssuer)
                    .withAudience(jwtAudience)
                    .build()
            )
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asString()
                val role   = credential.payload.getClaim("role").asString()
                val isUsableUser = if (userId != null) {
                    transaction {
                        UsersTable.selectAll()
                            .where { UsersTable.id eq userId }
                            .firstOrNull()
                            ?.let { it[UsersTable.isActive] && it[UsersTable.deletedAt] == null }
                            ?: false
                    }
                } else {
                    false
                }
                if (userId != null && role != null && isUsableUser) {
                    JWTPrincipal(credential.payload)
                } else null
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Token không hợp lệ hoặc đã hết hạn")
                )
            }
        }
    }
}
