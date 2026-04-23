package com.example.nhathuoc.util

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

object AppRoles {
    const val ADMIN = "ADMIN"
    const val EMPLOYEE = "EMPLOYEE"
    const val USER = "USER"

    val internalRoles = setOf(ADMIN, EMPLOYEE)
}

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
 * Resolve the current managed shop for the authenticated user.
 * Note: multi-shop was removed. Keep a synthetic context id so older
 * internal routes can keep destructuring without binding to a real table.
 */
suspend fun ApplicationCall.getManagedShopId(userId: String, role: String?): String? {
    val principal = this.principal<JWTPrincipal>()
    return principal?.payload?.getClaim("shopId")?.asString()?.takeIf { it.isNotBlank() }
        ?: "default-store"
}

/**
 * Require internal access (admin or employee role)
 */
suspend fun ApplicationCall.requireInternalAccess(
    allowedRoles: Set<String> = AppRoles.internalRoles
): Pair<JWTPrincipal, String> {
    val principal = requireAnyRole(allowedRoles)
    val contextId = principal.payload.getClaim("shopId").asString()?.takeIf { it.isNotBlank() }
        ?: "default-store"
    return principal to contextId
}

/**
 * Legacy helper kept so existing code keeps compiling while routes migrate.
 */
suspend fun ApplicationCall.requireShopAccess(): Pair<JWTPrincipal, String> {
    return requireInternalAccess()
}

/**
 * Validate that a resource belongs to the current supplier - no longer applicable
 */
suspend fun ApplicationCall.validateShopOwnership(resourceShopId: String, currentShopId: String) {
    // Shop validation removed - shops no longer exist in the system
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
