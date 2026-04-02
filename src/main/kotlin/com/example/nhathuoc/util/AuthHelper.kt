package com.example.nhathuoc.util

import com.example.nhathuoc.database.tables.ShopsTable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Helper functions for Authentication and Authorization
 */

/**
 * Extract userId from JWT token
 */
fun JWTPrincipal.getUserId(): String? {
    return this.payload.getClaim("userId").asString()
}

/**
 * Extract role from JWT token
 */
fun JWTPrincipal.getRole(): String? {
    return this.payload.getClaim("role").asString()
}

/**
 * Require specific role for the endpoint
 */
suspend fun ApplicationCall.requireRole(requiredRole: String): JWTPrincipal {
    val principal = this.principal<JWTPrincipal>()
        ?: throw AuthenticationException("No JWT principal found")

    val role = principal.getRole()
    if (role != requiredRole) {
        respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "Access denied. Required role: $requiredRole")
        )
        throw AuthorizationException("Required role: $requiredRole, actual role: $role")
    }

    return principal
}

/**
 * Require any of the specified roles
 */
suspend fun ApplicationCall.requireAnyRole(allowedRoles: Set<String>): JWTPrincipal {
    val principal = this.principal<JWTPrincipal>()
        ?: throw AuthenticationException("No JWT principal found")

    val role = principal.getRole()
    if (role !in allowedRoles) {
        respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "Access denied. Required one of roles: ${allowedRoles.joinToString(", ")}")
        )
        throw AuthorizationException("Required roles: $allowedRoles, actual role: $role")
    }

    return principal
}

/**
 * Get shopId for the current user (only works for SHOP role - suppliers in medical supply context)
 */
suspend fun ApplicationCall.getShopIdForOwner(userId: String): String? {
    return transaction {
        ShopsTable
            .selectAll()
            .where { ShopsTable.ownerId eq userId }
            .singleOrNull()
            ?.get(ShopsTable.id)
    }
}

/**
 * Require SHOP role and get shop ID for the current user (supplier access)
 */
suspend fun ApplicationCall.requireShopAccess(): Pair<JWTPrincipal, String> {
    val principal = requireRole("SHOP")
    val userId = principal.getUserId()
        ?: throw AuthenticationException("User ID not found in token")

    val shopId = getShopIdForOwner(userId)
        ?: run {
            respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "No supplier found for this user")
            )
            throw AuthorizationException("No shop found for user: $userId")
        }

    return Pair(principal, shopId)
}

/**
 * Validate that a resource belongs to the current supplier
 */
suspend fun ApplicationCall.validateShopOwnership(resourceShopId: String, currentShopId: String) {
    if (resourceShopId != currentShopId) {
        respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "Access denied to this resource")
        )
        throw AuthorizationException("Resource doesn't belong to current shop")
    }
}

/**
 * Custom exception classes
 */
class AuthenticationException(message: String) : Exception(message)
class AuthorizationException(message: String) : Exception(message)

/**
 * Extension function to get both user ID and role from JWT
 */
fun JWTPrincipal.getUserInfo(): Pair<String?, String?> {
    return Pair(getUserId(), getRole())
}