package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.BannersTable
import com.example.nhathuoc.database.tables.CategoriesTable
import com.example.nhathuoc.database.tables.OrderItemsTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.PaymentsTable
import com.example.nhathuoc.plugins.NotFoundException
import com.example.nhathuoc.service.CheckoutRequest
import com.example.nhathuoc.service.CheckoutService
import com.example.nhathuoc.service.CreateAddressRequest
import com.example.nhathuoc.service.OrderLifecycleService
import com.example.nhathuoc.service.UpdateAddressRequest
import com.example.nhathuoc.service.UpdateUserProfileRequest
import com.example.nhathuoc.service.UserAddressDto
import com.example.nhathuoc.service.UserProfileDto
import com.example.nhathuoc.service.UserService
import com.example.nhathuoc.util.getUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Serializable
private data class CompatUpdateUserRequest(
    val fullName: String? = null,
    val email: String? = null,
    val avatarUrl: String? = null,
    val gender: Int? = null,
    val dateOfBirth: String? = null
)

@Serializable
private data class CompatAddAddressRequest(
    val type: String = "home",
    val recipientName: String,
    val recipientPhone: String,
    val fullAddress: String,
    val ward: String,
    val wardCode: String? = null,
    val district: String,
    val province: String,
    val provinceCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String = "MANUAL",
    val isDefault: Boolean = false
)

@Serializable
private data class CompatUserResponse(
    val id: String,
    val fullName: String? = null,
    val phone: String,
    val email: String? = null,
    val avatarUrl: String? = null,
    val gender: Int? = null,
    val dateOfBirth: String? = null,
    val role: String,
    val isActive: Boolean = true,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String? = null
)

@Serializable
private data class CompatUpdateUserResponse(
    val message: String,
    val user: CompatUserResponse
)

@Serializable
private data class CompatUserAddressResponse(
    val id: String,
    val userId: String,
    val type: String,
    val recipientName: String,
    val recipientPhone: String,
    val fullAddress: String,
    val ward: String,
    val wardCode: String? = null,
    val district: String,
    val province: String,
    val provinceCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String = "MANUAL",
    val isDefault: Boolean,
    val createdAt: String = "",
    val updatedAt: String = "",
    val label: String? = null,
    val phone: String? = null,
    val address: String
)

@Serializable
private data class CompatAddAddressResponse(
    val message: String,
    val address: CompatUserAddressResponse
)

@Serializable
private data class CompatCancelOrderRequest(
    val reason: String? = null
)

@Serializable
private data class CompatCheckoutOrderSummaryDto(
    val id: String,
    val orderCode: String,
    val total: Double? = null,
    val paymentMethod: String,
    val paymentStatus: String,
    val paymentUrl: String? = null
)

@Serializable
private data class CompatCheckoutResponse(
    val success: Boolean,
    val message: String,
    val data: CompatCheckoutOrderSummaryDto? = null,
    val error: String? = null
)

@Serializable
private data class CompatOrderItemDto(
    val id: String,
    val orderId: String,
    val productId: String,
    val name: String,
    val product: String? = null,
    val quantity: Int,
    val unit: String,
    val price: Double,
    val totalPrice: Double? = null,
    val createdAt: String? = null
)

@Serializable
private data class CompatOrderDto(
    val id: String,
    val orderCode: String,
    val userId: String,
    val status: String,
    val pickupType: String = "DELIVERY",
    val branchId: String? = null,
    val addressId: String? = null,
    val items: List<CompatOrderItemDto> = emptyList(),
    val subtotal: Double? = null,
    val shippingFee: Double = 0.0,
    val discount: Double = 0.0,
    val pointsUsed: Int = 0,
    val pointsEarned: Int = 0,
    val total: Double? = null,
    val paymentMethod: String,
    val paymentStatus: String,
    val shippingAddress: String? = null,
    val note: String? = null,
    val estimatedDelivery: String? = null,
    val deliveredAt: String? = null,
    val cancelledAt: String? = null,
    val cancelReason: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
private data class CompatOrderListResponse(
    val orders: List<CompatOrderDto>,
    val pagination: RoutePaginationResponse
)

@Serializable
private data class CompatCategoryDto(
    val id: String,
    val name: String,
    val slug: String,
    val description: String? = null,
    val iconUrl: String? = null,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val parentId: String? = null,
    val productTypeDefault: String? = null
)

@Serializable
private data class CompatBannerDetailDto(
    val id: String,
    val imageUrl: String,
    val linkUrl: String? = null,
    val title: String? = null,
    val description: String? = null,
    val sortOrder: Int = 0,
    val startDt: String? = null,
    val endDt: String? = null,
    val isActive: Boolean = true,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
private data class CompatMessageResponse(
    val message: String
)

@Serializable
private data class CompatPaymentMethodsResponse(
    val data: List<String>,
    val message: String
)

@Serializable
private data class CompatShopDto(
    val id: String,
    val ownerId: String,
    val name: String,
    val description: String? = null,
    val logoUrl: String? = null,
    val licenseNumber: String? = null,
    val isApproved: Boolean = false,
    val expiryAlertDays: Int = 30,
    val createdAt: String,
    val deletedAt: String? = null
)

private fun UserProfileDto.toCompatUserResponse(genderOverride: Int? = null): CompatUserResponse {
    val mappedGender = genderOverride ?: when (gender) {
        "Nam" -> 1
        "Nữ" -> 2
        "Khác" -> 3
        else -> null
    }
    return CompatUserResponse(
        id = id,
        fullName = fullName,
        phone = phone,
        email = email,
        avatarUrl = avatarUrl,
        gender = mappedGender,
        dateOfBirth = dateOfBirth,
        role = role,
        isActive = isActive,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        deletedAt = null
    )
}

private fun UserAddressDto.toCompatAddressResponse(): CompatUserAddressResponse {
    val normalizedType = label ?: "home"
    val normalizedPhone = phone ?: ""
    val normalizedAddress = fullAddress?.takeIf { it.isNotBlank() } ?: address
    return CompatUserAddressResponse(
        id = id,
        userId = userId,
        type = normalizedType,
        recipientName = recipientName ?: "",
        recipientPhone = normalizedPhone,
        fullAddress = normalizedAddress,
        ward = ward ?: "",
        wardCode = wardCode,
        district = district ?: "",
        province = province ?: "",
        provinceCode = provinceCode,
        latitude = latitude,
        longitude = longitude,
        locationSource = locationSource,
        isDefault = isDefault,
        createdAt = "",
        updatedAt = "",
        label = normalizedType,
        phone = normalizedPhone,
        address = normalizedAddress
    )
}

fun Route.mobileCompatRoutes() {
    val userService = UserService()
    val checkoutService = CheckoutService()
    val orderLifecycleService = OrderLifecycleService()

    authenticate("auth-jwt") {
        route("/user") {
            get("/me") {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                val profile = userService.getUserProfile(userId)
                    ?: throw NotFoundException("User not found")

                call.respond(profile.toCompatUserResponse())
            }

            put("/update") {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val request = call.receive<CompatUpdateUserRequest>()
                val mappedGender = when (request.gender) {
                    1 -> "Nam"
                    2 -> "Nữ"
                    3 -> "Khác"
                    else -> null
                }

                val normalizedGender = when (request.gender) {
                    1 -> "Nam"
                    2 -> "Nữ"
                    3 -> "Khác"
                    else -> mappedGender
                }

                userService.updateUserProfile(
                    userId,
                    UpdateUserProfileRequest(
                        email = request.email,
                        fullName = request.fullName,
                        gender = normalizedGender,
                        dateOfBirth = request.dateOfBirth,
                        avatarUrl = request.avatarUrl
                    )
                )

                val profile = userService.getUserProfile(userId)
                    ?: throw NotFoundException("User not found")

                call.respond(
                    CompatUpdateUserResponse(
                        message = "User updated successfully",
                        user = profile.toCompatUserResponse(genderOverride = request.gender)
                    )
                )
            }

            get("/addresses") {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))

                val addresses = userService.getUserAddresses(userId)
                    .map { address -> address.toCompatAddressResponse() }

                call.respond(addresses)
            }

            post("/addresses") {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val request = call.receive<CompatAddAddressRequest>()
                val addressId = userService.createAddress(
                    userId,
                    CreateAddressRequest(
                        label = request.type,
                        recipientName = request.recipientName,
                        phone = request.recipientPhone,
                        address = request.fullAddress,
                        fullAddress = request.fullAddress,
                        ward = request.ward,
                        wardCode = request.wardCode,
                        district = request.district,
                        province = request.province,
                        provinceCode = request.provinceCode,
                        latitude = request.latitude,
                        longitude = request.longitude,
                        locationSource = request.locationSource,
                        isDefault = request.isDefault
                    )
                )
                val address = userService.getAddressById(userId, addressId)
                    ?: throw NotFoundException("Address not found")

                call.respond(
                    HttpStatusCode.Created,
                    CompatAddAddressResponse(
                        message = "Address created successfully",
                        address = address.toCompatAddressResponse()
                    )
                )
            }

            put("/addresses/{id}") {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val addressId = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Address id is required"))
                val request = call.receive<CompatAddAddressRequest>()

                userService.updateAddress(
                    userId,
                    addressId,
                    UpdateAddressRequest(
                        label = request.type,
                        recipientName = request.recipientName,
                        phone = request.recipientPhone,
                        address = request.fullAddress,
                        fullAddress = request.fullAddress,
                        ward = request.ward,
                        wardCode = request.wardCode,
                        district = request.district,
                        province = request.province,
                        provinceCode = request.provinceCode,
                        latitude = request.latitude,
                        longitude = request.longitude,
                        locationSource = request.locationSource,
                        isDefault = request.isDefault
                    )
                )

                val address = userService.getAddressById(userId, addressId)
                    ?: throw NotFoundException("Address not found")

                call.respond(
                    CompatAddAddressResponse(
                        message = "Address updated successfully",
                        address = address.toCompatAddressResponse()
                    )
                )
            }

            delete("/addresses/{id}") {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val addressId = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Address id is required"))

                userService.deleteAddress(userId, addressId)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        route("/orders") {
            get {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val status = call.parameters["status"]
                val page = call.parameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val limit = call.parameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 10
                val offset = (page - 1L) * limit

                val result = transaction {
                    var base = OrdersTable.selectAll().where { OrdersTable.userId eq userId }
                    if (!status.isNullOrBlank()) {
                        base = OrdersTable.selectAll().where { (OrdersTable.userId eq userId) and (OrdersTable.status eq status) }
                    }

                    val total = base.count().toInt()
                    val orders = base
                        .orderBy(OrdersTable.createdAt to SortOrder.DESC)
                        .limit(limit, offset)
                        .map { row ->
                            CompatOrderDto(
                                id = row[OrdersTable.id],
                                orderCode = row[OrdersTable.orderCode],
                                userId = row[OrdersTable.userId] ?: "",
                                status = row[OrdersTable.status],
                                pickupType = row[OrdersTable.pickupType] ?: "DELIVERY",
                                branchId = row[OrdersTable.branchId],
                                addressId = row[OrdersTable.addressId],
                                items = emptyList(),
                                subtotal = row[OrdersTable.subtotal]?.toDouble(),
                                shippingFee = row[OrdersTable.shippingFee].toDouble(),
                                discount = row[OrdersTable.discount].toDouble(),
                                pointsUsed = row[OrdersTable.pointsUsed],
                                pointsEarned = row[OrdersTable.pointsEarned],
                                total = row[OrdersTable.total]?.toDouble(),
                                paymentMethod = row[OrdersTable.paymentMethod] ?: "COD",
                                paymentStatus = row[OrdersTable.paymentStatus] ?: "UNPAID",
                                shippingAddress = null,
                                note = row[OrdersTable.note],
                                estimatedDelivery = null,
                                deliveredAt = null,
                                cancelledAt = null,
                                cancelReason = null,
                                createdAt = row[OrdersTable.createdAt].toString(),
                                updatedAt = row[OrdersTable.updatedAt].toString()
                            )
                        }

                    CompatOrderListResponse(
                        orders = orders,
                        pagination = RoutePaginationResponse(
                            page = page,
                            limit = limit,
                            total = total,
                            totalPages = if (total == 0) 0 else ((total + limit - 1) / limit),
                            hasNext = offset + limit < total,
                            hasPrev = page > 1
                        )
                    )
                }

                call.respond(result)
            }

            get("/{orderId}") {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val orderId = call.parameters["orderId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Order ID is required"))

                val detail = checkoutService.getOrderDetails(orderId, userId)
                val items = detail.items.map {
                    CompatOrderItemDto(
                        id = it.id,
                        orderId = detail.id,
                        productId = it.productId ?: "",
                        name = it.productName,
                        product = null,
                        quantity = it.quantity,
                        unit = it.unit ?: "Cái",
                        price = it.price.toDouble(),
                        totalPrice = it.price.toDouble() * it.quantity,
                        createdAt = null
                    )
                }
                val orderRow = transaction {
                    OrdersTable.selectAll().where { (OrdersTable.id eq orderId) and (OrdersTable.userId eq userId) }.single()
                }

                call.respond(
                    CompatOrderDto(
                        id = detail.id,
                        orderCode = detail.orderCode,
                        userId = userId,
                        status = detail.status,
                        pickupType = detail.pickupType ?: "DELIVERY",
                        branchId = orderRow[OrdersTable.branchId],
                        addressId = orderRow[OrdersTable.addressId],
                        items = items,
                        subtotal = detail.subtotal?.toDouble(),
                        shippingFee = detail.shippingFee.toDouble(),
                        discount = detail.discount.toDouble(),
                        pointsUsed = orderRow[OrdersTable.pointsUsed],
                        pointsEarned = orderRow[OrdersTable.pointsEarned],
                        total = detail.total?.toDouble(),
                        paymentMethod = detail.paymentMethod ?: "COD",
                        paymentStatus = detail.paymentStatus ?: "UNPAID",
                        shippingAddress = null,
                        note = orderRow[OrdersTable.note],
                        estimatedDelivery = null,
                        deliveredAt = null,
                        cancelledAt = null,
                        cancelReason = null,
                        createdAt = detail.createdAt.toString(),
                        updatedAt = orderRow[OrdersTable.updatedAt].toString()
                    )
                )
            }

            post("/{orderId}/cancel") {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val orderId = call.parameters["orderId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Order ID is required"))
                val request = call.receive<CompatCancelOrderRequest>()

                val outcome = transaction {
                    val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    val order = OrdersTable
                        .selectAll()
                        .where {
                            (OrdersTable.id eq orderId) and
                                (OrdersTable.userId eq userId) and
                                ((OrdersTable.status eq "PENDING") or (OrdersTable.status eq "PROCESSING"))
                        }
                        .singleOrNull()
                        ?: return@transaction 0

                    if (order[OrdersTable.paymentStatus] == "COMPLETED") {
                        return@transaction -1
                    }

                    OrdersTable.update({ OrdersTable.id eq orderId }) {
                        it[OrdersTable.status] = "CANCELLED"
                        if (request.reason != null) {
                            it[OrdersTable.note] = request.reason
                        }
                        it[OrdersTable.paymentStatus] = "UNPAID"
                        it[OrdersTable.updatedAt] = now
                    }
                    orderLifecycleService.restoreStockOnCancel(order)
                    orderLifecycleService.cancelPendingPayments(orderId)
                    orderLifecycleService.revertCouponAndRewardUsage(orderId)
                    orderLifecycleService.notifyOrderStatusChanged(order, "CANCELLED", "UNPAID")
                    1
                }
                if (outcome == -1) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Đơn đã thanh toán, vui lòng tạo khiếu nại/hoàn tiền thay vì hủy trực tiếp"))
                }
                if (outcome == 0) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Đơn không thể hủy ở trạng thái hiện tại"))
                }

                call.respond(CompatMessageResponse("Đã hủy đơn hàng"))
            }

            post("/{orderId}/confirm-received") {
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val orderId = call.parameters["orderId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Order ID is required"))

                val updated = transaction {
                    val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    val order = OrdersTable.selectAll()
                        .where {
                            (OrdersTable.id eq orderId) and
                                (OrdersTable.userId eq userId) and
                                (OrdersTable.status eq "SHIPPING")
                        }
                        .singleOrNull()
                        ?: return@transaction 0
                    val method = order[OrdersTable.paymentMethod]?.uppercase()
                    OrdersTable.update({ OrdersTable.id eq orderId }) {
                        it[OrdersTable.status] = "DELIVERED"
                        it[OrdersTable.completedAt] = now
                        it[OrdersTable.updatedAt] = now
                        if (method == "COD") {
                            it[OrdersTable.paymentStatus] = "COMPLETED"
                        }
                    }
                    orderLifecycleService.notifyOrderStatusChanged(order, "DELIVERED", if (method == "COD") "COMPLETED" else order[OrdersTable.paymentStatus])
                }
                if (updated == 0) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Chỉ có thể xác nhận khi đơn đang giao và thuộc tài khoản của bạn")
                    )
                }

                call.respond(CompatMessageResponse("Order received successfully"))
            }
        }
    }

    authenticate("auth-jwt") {
        route("/checkout") {
            post {
                try {
                    val userId = call.principal<JWTPrincipal>()?.getUserId()
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                    val request = call.receive<CheckoutRequest>()
                    val order = checkoutService.createOrder(userId, request)
                    call.respond(
                        HttpStatusCode.Created,
                        CompatCheckoutResponse(
                            success = true,
                            message = "Order created successfully",
                            data = CompatCheckoutOrderSummaryDto(
                                id = order.id,
                                orderCode = order.orderCode,
                                total = order.total?.toDouble(),
                                paymentMethod = order.paymentMethod ?: "COD",
                                paymentStatus = order.paymentStatus,
                                paymentUrl = null
                            ),
                            error = null
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        CompatCheckoutResponse(
                            success = false,
                            message = e.message ?: "Checkout failed",
                            data = null,
                            error = null
                        )
                    )
                }
            }
        }
    }

    route("/categories") {
        get("/{id}") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Category ID is required"))
            val category = transaction {
                CategoriesTable.selectAll().where { CategoriesTable.id eq id }.singleOrNull()
            } ?: throw NotFoundException("Category not found")

            call.respond(
                CompatCategoryDto(
                    id = category[CategoriesTable.id],
                    name = category[CategoriesTable.name],
                    slug = category[CategoriesTable.slug] ?: category[CategoriesTable.id],
                    description = category[CategoriesTable.description],
                    iconUrl = category[CategoriesTable.iconUrl],
                    sortOrder = category[CategoriesTable.sortOrder],
                    isActive = category[CategoriesTable.isActive],
                    parentId = category[CategoriesTable.parentId],
                    productTypeDefault = category[CategoriesTable.productTypeDefault]
                )
            )
        }
    }

    route("/banners") {
        get("/{id}") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Banner ID is required"))
            val banner = transaction {
                BannersTable.selectAll().where { BannersTable.id eq id }.singleOrNull()
            } ?: throw NotFoundException("Banner not found")

            call.respond(
                CompatBannerDetailDto(
                    id = banner[BannersTable.id],
                    imageUrl = banner[BannersTable.imageUrl],
                    linkUrl = banner[BannersTable.linkUrl],
                    title = banner[BannersTable.title],
                    description = null,
                    sortOrder = banner[BannersTable.sortOrder],
                    startDt = banner[BannersTable.startDt]?.toString(),
                    endDt = banner[BannersTable.endDt]?.toString(),
                    isActive = banner[BannersTable.isActive],
                    createdAt = null,
                    updatedAt = null
                )
            )
        }
    }

    route("/payment-methods") {
        get {
            val methods = listOf("COD", "MOMO", "ZALOPAY")
            call.respond(methods)
        }
    }

    route("/shops") {
        get {
            call.respond(
                RoutePaginatedDataMessageResponse<CompatShopDto>(
                    data = emptyList(),
                    pagination = RoutePaginationResponse(
                        page = 1,
                        limit = 0,
                        total = 0,
                        totalPages = 0,
                        hasNext = false,
                        hasPrev = false
                    ),
                    message = "Shop management removed"
                )
            )
        }

        get("/{id}") {
            call.respond(HttpStatusCode.NotFound, RouteErrorResponse("Shop management removed"))
        }
    }
}

