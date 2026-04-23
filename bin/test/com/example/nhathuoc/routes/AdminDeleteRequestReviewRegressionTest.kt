package com.example.nhathuoc.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.nhathuoc.database.tables.CategoryAttributesTable
import com.example.nhathuoc.database.tables.CategoriesTable
import com.example.nhathuoc.database.tables.ProductAttributeValuesTable
import com.example.nhathuoc.database.tables.ProductCertificatesTable
import com.example.nhathuoc.database.tables.ProductDeleteRequestsTable
import com.example.nhathuoc.database.tables.ProductImagesTable
import com.example.nhathuoc.database.tables.ProductsTable
import com.example.nhathuoc.database.tables.UsersTable
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class AdminDeleteRequestReviewRegressionTest {

    @Test
    fun reviewRoute_serializesApprovedResponse_withoutSerializationError() = testApplication {
        val seeded = seedDeleteRequestData()
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(JWT.require(Algorithm.HMAC256(TEST_JWT_SECRET)).build())
                    validate { credential ->
                        val userId = credential.payload.getClaim("userId").asString()
                        val role = credential.payload.getClaim("role").asString()
                        if (userId != null && role != null) JWTPrincipal(credential.payload) else null
                    }
                }
            }
            routing {
                route("/api/v1") {
                    adminRoutes(resolveAdminShopId = { SHOP_ID })
                }
            }
        }

        val response = client.post("/api/v1/admin/product-delete-requests/${seeded.requestId}/review") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"approve":false}""")
        }

        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("\"status\":\"REJECTED\""), body)
        assertTrue(body.contains("Rejected delete request"), body)
    }

    @Test
    fun reviewRoute_approveDeletesProductWithoutFkViolation() = testApplication {
        val seeded = seedDeleteRequestData()
        application {
            install(ContentNegotiation) { json() }
            install(Authentication) {
                jwt("auth-jwt") {
                    verifier(JWT.require(Algorithm.HMAC256(TEST_JWT_SECRET)).build())
                    validate { credential ->
                        val userId = credential.payload.getClaim("userId").asString()
                        val role = credential.payload.getClaim("role").asString()
                        if (userId != null && role != null) JWTPrincipal(credential.payload) else null
                    }
                }
            }
            routing {
                route("/api/v1") {
                    adminRoutes(resolveAdminShopId = { SHOP_ID })
                }
            }
        }

        val response = client.post("/api/v1/admin/product-delete-requests/${seeded.requestId}/review") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"approve":true}""")
        }

        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("\"status\":\"APPROVED\""), body)

        transaction {
            val productCount = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq seeded.productId }
                .count()
            val requestCount = ProductDeleteRequestsTable
                .selectAll()
                .where { ProductDeleteRequestsTable.productId eq seeded.productId }
                .count()

            assertEquals(0L, productCount, "Approved review must delete product")
            assertEquals(0L, requestCount, "Approved review must remove delete-request rows before product delete")
        }
    }

    private fun seedDeleteRequestData(): SeededDeleteRequest {
        Database.connect(
            url = "jdbc:h2:mem:admin-delete-review-${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
            driver = "org.h2.Driver"
        )

        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        val requestId = "req-${System.nanoTime()}"
        val productId = "prod-${System.nanoTime()}"

        transaction {
            SchemaUtils.create(
                UsersTable,
                CategoriesTable,
                CategoryAttributesTable,
                ProductsTable,
                ProductImagesTable,
                ProductCertificatesTable,
                ProductAttributeValuesTable,
                ProductDeleteRequestsTable
            )

            UsersTable.insert {
                it[id] = ADMIN_USER_ID
                it[phone] = "0900000001"
                it[email] = "admin.review@test.local"
                it[password] = "hashed"
                it[fullName] = "Admin Reviewer"
                it[role] = "ADMIN"
                it[isActive] = true
                it[createdAt] = now
                it[updatedAt] = now
            }
            UsersTable.insert {
                it[id] = EMPLOYEE_USER_ID
                it[phone] = "0900000002"
                it[email] = "employee.review@test.local"
                it[password] = "hashed"
                it[fullName] = "Employee Requester"
                it[role] = "EMPLOYEE"
                it[isActive] = true
                it[createdAt] = now
                it[updatedAt] = now
            }

            ProductsTable.insert {
                it[id] = productId
                it[categoryId] = null
                it[name] = "Regression Product"
                it[slug] = null
                it[description] = null
                it[brand] = null
                it[origin] = null
                it[sku] = null
                it[unit] = "Hop"
                it[price] = java.math.BigDecimal("99000")
                it[originalPrice] = java.math.BigDecimal("99000")
                it[discountPct] = 0
                it[rewardPoints] = 0
                it[stock] = 10
                it[productType] = "MEDICAL_SUPPLY"
                it[registrationNumber] = null
                it[riskClassification] = "A"
                it[requiresCertification] = false
                it[requiresConsultation] = false
                it[isActive] = true
                it[isFlashSale] = false
                it[isBestSeller] = false
                it[flashSaleEnd] = null
                it[createdAt] = now
                it[updatedAt] = now
            }

            ProductDeleteRequestsTable.insert {
                it[ProductDeleteRequestsTable.id] = requestId
                it[ProductDeleteRequestsTable.shopId] = SHOP_ID
                it[ProductDeleteRequestsTable.productId] = productId
                it[ProductDeleteRequestsTable.requestedByUserId] = EMPLOYEE_USER_ID
                it[ProductDeleteRequestsTable.reason] = "Regression test"
                it[ProductDeleteRequestsTable.status] = "PENDING"
                it[ProductDeleteRequestsTable.reviewedByUserId] = null
                it[ProductDeleteRequestsTable.reviewedAt] = null
                it[ProductDeleteRequestsTable.createdAt] = now
                it[ProductDeleteRequestsTable.updatedAt] = now
            }
        }

        return SeededDeleteRequest(requestId = requestId, productId = productId)
    }

    private fun adminToken(): String = JWT.create()
        .withClaim("userId", ADMIN_USER_ID)
        .withClaim("role", "ADMIN")
        .sign(Algorithm.HMAC256(TEST_JWT_SECRET))

    private data class SeededDeleteRequest(
        val requestId: String,
        val productId: String
    )

    companion object {
        private const val TEST_JWT_SECRET = "admin-delete-review-regression-secret"
        private const val SHOP_ID = "shop-001"
        private const val ADMIN_USER_ID = "u-admin-review"
        private const val EMPLOYEE_USER_ID = "u-employee-review"
    }
}


