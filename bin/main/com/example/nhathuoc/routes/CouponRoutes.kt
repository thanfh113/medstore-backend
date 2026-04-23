package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.CouponRedemptionsTable
import com.example.nhathuoc.database.tables.CouponsTable
import com.example.nhathuoc.util.AppRoles
import com.example.nhathuoc.util.getRole
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.requireInternalAccess
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.insert
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

@Serializable
private data class CouponCreateRequest(
    val code: String,
    val name: String,
    val description: String? = null,
    val discountType: String,
    val discountValue: Double,
    val minOrderTotal: Double? = null,
    val maxDiscountAmount: Double? = null,
    val usageLimit: Int? = null,
    val usagePerUserLimit: Int? = null,
    val isActive: Boolean = true
)

@Serializable
private data class CouponValidateRequest(
    val code: String,
    val orderTotal: Double,
    val userId: String? = null
)

@Serializable
private data class CouponDto(
    val id: String,
    val code: String,
    val name: String,
    val discountType: String,
    val discountValue: Double,
    val minOrderTotal: Double? = null,
    val maxDiscountAmount: Double? = null,
    val usageLimit: Int? = null,
    val usagePerUserLimit: Int? = null,
    val usedCount: Int,
    val isActive: Boolean
)

@Serializable
private data class CouponValidateData(
    val couponId: String,
    val code: String,
    val discountAmount: Double,
    val payableAmount: Double
)

@Serializable
private data class CouponIdPayload(
    val id: String
)

@Serializable
private data class CouponEnvelope<T>(
    val data: T,
    val message: String
)

internal data class CouponComputation(
    val couponId: String,
    val code: String,
    val discountAmount: BigDecimal
)

internal fun computeCouponDiscount(
    shopId: String,
    code: String,
    orderTotal: BigDecimal,
    userId: String?
): Result<CouponComputation> {
    return computeCouponDiscount(code = code, orderTotal = orderTotal, userId = userId)
}

fun Route.couponRoutes() {
    authenticate("auth-jwt") {
        route("/internal/coupons") {
            get {
                call.requireInternalAccess()
                val data = transaction {
                    CouponsTable
                        .selectAll()
                        .orderBy(CouponsTable.createdAt to SortOrder.DESC)
                        .map { row ->
                            CouponDto(
                                id = row[CouponsTable.id],
                                code = row[CouponsTable.code],
                                name = row[CouponsTable.name],
                                discountType = row[CouponsTable.discountType],
                                discountValue = row[CouponsTable.discountValue].toDouble(),
                                minOrderTotal = row[CouponsTable.minOrderTotal]?.toDouble(),
                                maxDiscountAmount = row[CouponsTable.maxDiscountAmount]?.toDouble(),
                                usageLimit = row[CouponsTable.usageLimit],
                                usagePerUserLimit = row[CouponsTable.usagePerUserLimit],
                                usedCount = row[CouponsTable.usedCount],
                                isActive = row[CouponsTable.isActive]
                            )
                        }
                }
                call.respond(
                    CouponEnvelope(
                        data = data,
                        message = "Get coupons successfully"
                    )
                )
            }

            post {
                val (principal, _) = call.requireInternalAccess()
                if (principal.getRole() != AppRoles.ADMIN) {
                    return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Only ADMIN can create coupon"))
                }

                val request = call.receive<CouponCreateRequest>()
                val normalizedCode = request.code.trim().uppercase()
                val discountType = request.discountType.trim().uppercase()
                if (normalizedCode.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Coupon code is required"))
                }
                if (request.name.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Coupon name is required"))
                }
                if (discountType !in setOf("PERCENT", "FIXED_AMOUNT")) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "discountType must be PERCENT or FIXED_AMOUNT"))
                }
                if (request.discountValue <= 0.0) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "discountValue must be > 0"))
                }

                val createdId = transaction {
                    val existed = CouponsTable
                        .selectAll()
                        .where { CouponsTable.code eq normalizedCode }
                        .singleOrNull()
                    if (existed != null) {
                        throw IllegalArgumentException("Coupon code already exists")
                    }

                    val id = UUID.randomUUID().toString()
                    CouponsTable.insert {
                        it[CouponsTable.id] = id
                        it[CouponsTable.code] = normalizedCode
                        it[CouponsTable.name] = request.name.trim()
                        it[CouponsTable.description] = request.description?.trim()?.ifBlank { null }
                        it[CouponsTable.discountType] = discountType
                        it[CouponsTable.discountValue] = request.discountValue.toBigDecimal()
                        it[CouponsTable.minOrderTotal] = request.minOrderTotal?.toBigDecimal()
                        it[CouponsTable.maxDiscountAmount] = request.maxDiscountAmount?.toBigDecimal()
                        it[CouponsTable.usageLimit] = request.usageLimit
                        it[CouponsTable.usagePerUserLimit] = request.usagePerUserLimit
                        it[CouponsTable.isActive] = request.isActive
                        it[CouponsTable.createdByUserId] = principal.getUserId()
                    }
                    id
                }

                call.respond(
                    HttpStatusCode.Created,
                    CouponEnvelope(
                        data = CouponIdPayload(id = createdId),
                        message = "Coupon created successfully"
                    )
                )
            }

            post("/validate") {
                call.requireInternalAccess()
                val request = call.receive<CouponValidateRequest>()

                val orderTotal = request.orderTotal.toBigDecimal().setScale(2, RoundingMode.HALF_UP)
                if (orderTotal <= BigDecimal.ZERO) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "orderTotal must be > 0"))
                }

                val result = transaction {
                    computeCouponDiscount(
                        code = request.code,
                        orderTotal = orderTotal,
                        userId = request.userId
                    )
                }

                result.onSuccess { computed ->
                    call.respond(
                        CouponEnvelope(
                            data = CouponValidateData(
                                couponId = computed.couponId,
                                code = computed.code,
                                discountAmount = computed.discountAmount.toDouble(),
                                payableAmount = orderTotal.subtract(computed.discountAmount).toDouble()
                            ),
                            message = "Coupon is valid"
                        )
                    )
                }.onFailure {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Invalid coupon")))
                }
            }
        }
    }
}

internal fun computeCouponDiscount(
    code: String,
    orderTotal: BigDecimal,
    userId: String?
): Result<CouponComputation> {
    val normalizedCode = code.trim().uppercase()
    if (normalizedCode.isBlank()) return Result.failure(IllegalArgumentException("Coupon code is required"))

    val coupon = CouponsTable
        .selectAll()
        .where { CouponsTable.code eq normalizedCode }
        .singleOrNull()
        ?: return Result.failure(IllegalArgumentException("Coupon not found"))

    if (!coupon[CouponsTable.isActive]) {
        return Result.failure(IllegalArgumentException("Coupon is inactive"))
    }

    val minOrderTotal = coupon[CouponsTable.minOrderTotal]
    if (minOrderTotal != null && orderTotal < minOrderTotal) {
        return Result.failure(IllegalArgumentException("Order total does not meet coupon minimum value"))
    }

    val usageLimit = coupon[CouponsTable.usageLimit]
    if (usageLimit != null && coupon[CouponsTable.usedCount] >= usageLimit) {
        return Result.failure(IllegalArgumentException("Coupon usage limit reached"))
    }

    if (!userId.isNullOrBlank()) {
        val usagePerUserLimit = coupon[CouponsTable.usagePerUserLimit]
        if (usagePerUserLimit != null) {
            val usedByUser = CouponRedemptionsTable
                .selectAll()
                .where {
                    (CouponRedemptionsTable.couponId eq coupon[CouponsTable.id]) and
                        (CouponRedemptionsTable.userId eq userId) and
                        (CouponRedemptionsTable.status eq "APPLIED")
                }
                .count()
                .toInt()
            if (usedByUser >= usagePerUserLimit) {
                return Result.failure(IllegalArgumentException("Coupon usage per user limit reached"))
            }
        }
    }

    val discount = when (coupon[CouponsTable.discountType]) {
        "PERCENT" -> {
            val pct = coupon[CouponsTable.discountValue]
            orderTotal.multiply(pct).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        }
        else -> coupon[CouponsTable.discountValue]
    }

    val cappedDiscount = coupon[CouponsTable.maxDiscountAmount]?.let { max ->
        if (discount > max) max else discount
    } ?: discount

    val normalizedDiscount = when {
        cappedDiscount < BigDecimal.ZERO -> BigDecimal.ZERO
        cappedDiscount > orderTotal -> orderTotal
        else -> cappedDiscount
    }.setScale(2, RoundingMode.HALF_UP)

    return Result.success(
        CouponComputation(
            couponId = coupon[CouponsTable.id],
            code = coupon[CouponsTable.code],
            discountAmount = normalizedDiscount
        )
    )
}

