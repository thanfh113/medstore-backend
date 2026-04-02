package com.example.nhathuoc.routes

import com.example.nhathuoc.service.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.webSocket

// ─── User profile & addresses ─────────────────────────────────
fun Route.userRoutes() {
    val userService = UserService()

    authenticate("auth-jwt") {
        route("/users") {
            // GET /api/v1/users/me - Get user profile
            get("/me") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val userProfile = userService.getUserProfile(userId)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "User not found")
                        )

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to userProfile,
                            "message" to "User profile retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get user profile: ${e.message}")
                    )
                }
            }

            // PUT /api/v1/users/me - Update user profile
            put("/me") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@put call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val request = call.receive<UpdateUserProfileRequest>()
                    userService.updateUserProfile(userId, request)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "User profile updated successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update user profile: ${e.message}")
                    )
                }
            }

            // GET /api/v1/users/me/addresses - Get user addresses
            get("/me/addresses") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val addresses = userService.getUserAddresses(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to addresses,
                            "message" to "Addresses retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get addresses: ${e.message}")
                    )
                }
            }

            // POST /api/v1/users/me/addresses - Create new address
            post("/me/addresses") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val request = call.receive<CreateAddressRequest>()
                    val addressId = userService.createAddress(userId, request)

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to mapOf("addressId" to addressId),
                            "message" to "Address created successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to create address: ${e.message}")
                    )
                }
            }

            // GET /api/v1/users/me/addresses/{id} - Get specific address
            get("/me/addresses/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val addressId = call.parameters["id"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Address ID is required")
                        )

                    val address = userService.getAddressById(userId, addressId)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Address not found")
                        )

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to address,
                            "message" to "Address retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get address: ${e.message}")
                    )
                }
            }

            // PUT /api/v1/users/me/addresses/{id} - Update address
            put("/me/addresses/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@put call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val addressId = call.parameters["id"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Address ID is required")
                        )

                    val request = call.receive<UpdateAddressRequest>()
                    userService.updateAddress(userId, addressId, request)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Address updated successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update address: ${e.message}")
                    )
                }
            }

            // DELETE /api/v1/users/me/addresses/{id} - Delete address
            delete("/me/addresses/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@delete call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val addressId = call.parameters["id"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Address ID is required")
                        )

                    userService.deleteAddress(userId, addressId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Address deleted successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to delete address: ${e.message}")
                    )
                }
            }

            // POST /api/v1/users/me/addresses/{id}/default - Set default address
            post("/me/addresses/{id}/default") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val addressId = call.parameters["id"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Address ID is required")
                        )

                    userService.setDefaultAddress(userId, addressId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Default address set successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to set default address: ${e.message}")
                    )
                }
            }

            // GET /api/v1/users/me/addresses/default - Get default address
            get("/me/addresses/default") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val defaultAddress = userService.getDefaultAddress(userId)

                    if (defaultAddress != null) {
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf(
                                "data" to defaultAddress,
                                "message" to "Default address retrieved successfully"
                            )
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "No default address found")
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get default address: ${e.message}")
                    )
                }
            }
        }
    }
}

// ─── Products ─────────────────────────────────────────────────
@Deprecated("Use enhanced productRoutes() instead")
fun Route.productRoutesLegacy() {
    route("/products") {
        get {
            // ?category=&search=&page=&size=&sort=
            call.respond(mapOf("message" to "TODO: danh sách sản phẩm", "data" to emptyList<Any>()))
        }
        get("/flash-sale") {
            call.respond(mapOf("message" to "TODO: flash sale products"))
        }
        get("/best-sellers") {
            call.respond(mapOf("message" to "TODO: best seller products"))
        }
        get("/{id}") {
            val id = call.parameters["id"]
            call.respond(mapOf("message" to "TODO: chi tiết sản phẩm $id"))
        }

        // Xem giấy tờ công bố / chứng nhận sản phẩm (public - ai cũng xem được)
        get("/{id}/certificates") {
            val id = call.parameters["id"]
            call.respond(mapOf(
                "message" to "TODO: danh sách giấy tờ công bố sản phẩm $id",
                "data" to emptyList<Any>()
            ))
        }

        // Shop only routes
        authenticate("auth-jwt") {
            post {
                call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: tạo sản phẩm (SHOP)"))
            }
            put("/{id}") {
                call.respond(mapOf("message" to "TODO: sửa sản phẩm (SHOP)"))
            }
            delete("/{id}") {
                call.respond(mapOf("message" to "TODO: xoá sản phẩm (SHOP)"))
            }
            // SHOP upload giấy tờ công bố
            post("/{id}/certificates") {
                call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: upload giấy tờ công bố (SHOP)"))
            }
            delete("/{id}/certificates/{certId}") {
                call.respond(mapOf("message" to "TODO: xoá giấy tờ (SHOP)"))
            }
        }
    }
}


// ─── Categories ───────────────────────────────────────────────
@Deprecated("Use enhanced categoryRoutes() instead")
fun Route.categoryRoutesLegacy() {
    route("/categories") {
        get { call.respond(mapOf("message" to "TODO: danh sách danh mục")) }
        get("/{id}/products") {
            val name = call.parameters["id"]
            call.respond(mapOf("message" to "TODO: sản phẩm theo danh mục $name"))
        }
    }
    route("/diseases") {
        get { call.respond(mapOf("message" to "TODO: danh sách bệnh lý")) }
        get("/{id}/products") {
            call.respond(mapOf("message" to "TODO: sản phẩm theo bệnh lý"))
        }
    }
}

// ─── Cart ─────────────────────────────────────────────────────
fun Route.cartRoutes() {
    val cartService = CartService()

    authenticate("auth-jwt") {
        route("/cart") {
            // GET /api/v1/cart - Get user's cart summary
            get {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val cartSummary = cartService.getCartSummary(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to cartSummary,
                            "message" to "Cart retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get cart: ${e.message}")
                    )
                }
            }

            // POST /api/v1/cart/items - Add item to cart
            post("/items") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val request = call.receive<AddToCartRequest>()
                    val cartItemId = cartService.addToCart(userId, request)

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to mapOf("cartItemId" to cartItemId),
                            "message" to "Item added to cart successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to add item to cart: ${e.message}")
                    )
                }
            }

            // PUT /api/v1/cart/items/{id} - Update cart item quantity
            put("/items/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@put call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val cartItemId = call.parameters["id"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Cart item ID is required")
                        )

                    val request = call.receive<UpdateCartItemRequest>()
                    cartService.updateCartItem(userId, cartItemId, request)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Cart item updated successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update cart item: ${e.message}")
                    )
                }
            }

            // DELETE /api/v1/cart/items/{id} - Remove item from cart
            delete("/items/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@delete call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val cartItemId = call.parameters["id"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Cart item ID is required")
                        )

                    cartService.removeFromCart(userId, cartItemId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Item removed from cart successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to remove cart item: ${e.message}")
                    )
                }
            }

            // DELETE /api/v1/cart/clear - Clear entire cart
            delete("/clear") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@delete call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    cartService.clearCart(userId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Cart cleared successfully")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to clear cart: ${e.message}")
                    )
                }
            }

            // GET /api/v1/cart/count - Get cart item count for badge
            get("/count") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val count = cartService.getCartItemCount(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to mapOf("count" to count),
                            "message" to "Cart count retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get cart count: ${e.message}")
                    )
                }
            }

            // POST /api/v1/cart/validate - Validate cart for checkout
            post("/validate") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val errors = cartService.validateCartForCheckout(userId)

                    if (errors.isEmpty()) {
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf(
                                "data" to mapOf("isValid" to true),
                                "message" to "Cart is valid for checkout"
                            )
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "data" to mapOf(
                                    "isValid" to false,
                                    "errors" to errors
                                ),
                                "message" to "Cart validation failed"
                            )
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to validate cart: ${e.message}")
                    )
                }
            }

            // POST /api/v1/cart/cleanup - Remove unavailable items
            post("/cleanup") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val removedCount = cartService.cleanupCart(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to mapOf("removedItems" to removedCount),
                            "message" to "Cart cleaned up successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to cleanup cart: ${e.message}")
                    )
                }
            }
        }
    }
}

// ─── Orders ───────────────────────────────────────────────────
@Deprecated("Use orderFulfillmentRoutes() for enhanced order processing instead")
fun Route.orderRoutesLegacy() {
    authenticate("auth-jwt") {
        route("/orders") {
            post { call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: đặt hàng")) }
            get { call.respond(mapOf("message" to "TODO: lịch sử đơn hàng")) }
            get("/{id}") {
                val id = call.parameters["id"]
                call.respond(mapOf("message" to "TODO: chi tiết đơn $id"))
            }
            post("/{id}/cancel") { call.respond(mapOf("message" to "TODO: huỷ đơn")) }
            post("/{id}/reorder") { call.respond(mapOf("message" to "TODO: mua lại")) }

            // POST /api/v1/orders/prescription - Upload prescription
            post("/prescription") {
                val prescriptionService = PrescriptionService()
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val request = call.receive<UploadPrescriptionRequest>()
                    val prescriptionId = prescriptionService.uploadPrescription(userId, request)

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to mapOf("prescriptionId" to prescriptionId),
                            "message" to "Prescription uploaded successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to upload prescription: ${e.message}")
                    )
                }
            }

            // Shop: cập nhật trạng thái đơn
            put("/{id}/status") { call.respond(mapOf("message" to "TODO: cập nhật trạng thái (SHOP)")) }
        }
    }
}

// ─── Rewards ──────────────────────────────────────────────────
fun Route.rewardRoutes() {
    val rewardService = RewardService()

    route("/rewards") {
        // GET /api/v1/rewards/products - Get reward products (public)
        get("/products") {
            try {
                val minPoints = call.parameters["minPoints"]?.toIntOrNull()
                val maxPoints = call.parameters["maxPoints"]?.toIntOrNull()

                val products = if (minPoints != null || maxPoints != null) {
                    rewardService.getRewardProductsByPointRange(minPoints, maxPoints)
                } else {
                    rewardService.getRewardProducts()
                }

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "data" to products,
                        "message" to "Reward products retrieved successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to get reward products: ${e.message}")
                )
            }
        }

        authenticate("auth-jwt") {
            // GET /api/v1/rewards - Get user's reward summary
            get {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val summary = rewardService.getRewardSummary(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to summary,
                            "message" to "Reward summary retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get reward summary: ${e.message}")
                    )
                }
            }

            // GET /api/v1/rewards/account - Get reward account
            get("/account") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val account = rewardService.getOrCreateRewardAccount(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to account,
                            "message" to "Reward account retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get reward account: ${e.message}")
                    )
                }
            }

            // GET /api/v1/rewards/transactions - Get reward transactions
            get("/transactions") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
                    val transactions = rewardService.getRewardTransactions(userId, limit)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to transactions,
                            "message" to "Reward transactions retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get reward transactions: ${e.message}")
                    )
                }
            }

            // GET /api/v1/rewards/redemptions - Get user redemptions
            get("/redemptions") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val redemptions = rewardService.getUserRedemptions(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "data" to redemptions,
                            "message" to "User redemptions retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get user redemptions: ${e.message}")
                    )
                }
            }

            // POST /api/v1/rewards/redeem - Redeem reward
            post("/redeem") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val request = call.receive<RedeemRewardRequest>()
                    val redemptionId = rewardService.redeemReward(userId, request)

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to mapOf("redemptionId" to redemptionId),
                            "message" to "Reward redeemed successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to redeem reward: ${e.message}")
                    )
                }
            }
        }
    }
}


// ─── Medical Supply Technical Consultation ─────────────────────
fun Route.chatRoutes() {
    authenticate("auth-jwt") {
        route("/chat") {
            get("/sessions") { call.respond(mapOf("message" to "Get technical consultation sessions with medical supply experts")) }
            post("/sessions") { call.respond(HttpStatusCode.Created, mapOf("message" to "Create new technical consultation session")) }
            get("/sessions/{id}/messages") { call.respond(mapOf("message" to "Get consultation message history")) }
            post("/sessions/{id}/messages") { call.respond(HttpStatusCode.Created, mapOf("message" to "Send message to medical supply consultant")) }
        }
        // WebSocket for real-time technical consultation
        webSocket("/ws/chat/{sessionId}") {
            // TODO: implement WebSocket handler for real-time medical supply consultation
            // Will use AIPrompts.MEDICAL_SUPPLY_SYSTEM_PROMPT for AI-powered assistance
        }
    }
}

// ─── Pharmacies ───────────────────────────────────────────────
fun Route.pharmacyRoutes() {
    route("/pharmacies") {
        // ?lat=&lng=&radius=&search=
        get { call.respond(mapOf("message" to "TODO: find nearest medical supply store")) }
        get("/{id}") {
            val id = call.parameters["id"]
            call.respond(mapOf("message" to "TODO: medical supply store details $id"))
        }
    }
}

// ─── Notifications ────────────────────────────────────────────
fun Route.notificationRoutes() {
    authenticate("auth-jwt") {
        route("/notifications") {
            get { call.respond(mapOf("message" to "TODO: danh sách thông báo")) }
            put("/{id}/read") { call.respond(mapOf("message" to "TODO: đánh dấu đã đọc")) }
            put("/read-all") { call.respond(mapOf("message" to "TODO: đọc tất cả")) }
        }
    }
}

// ─── Banners ──────────────────────────────────────────────────
fun Route.bannerRoutes() {
    route("/banners") {
        get { call.respond(mapOf("message" to "TODO: danh sách banner")) }
    }
}

// ─── Technical Articles & Product Guides ─────────────────────
fun Route.healthArticleRoutes() {
    route("/technical-articles") {
        get { call.respond(mapOf("message" to "Get medical supply technical guides and product documentation")) }
        get("/{id}") { call.respond(mapOf("message" to "Get detailed technical article or product guide")) }
    }
}

// ─── Admin ────────────────────────────────────────────────────
fun Route.adminRoutes() {
    authenticate("auth-jwt") {
        route("/admin") {
            // Dashboard
            get("/dashboard") { call.respond(mapOf("message" to "TODO: thống kê tổng quan")) }
            // Finance
            get("/finance") { call.respond(mapOf("message" to "TODO: doanh thu tài chính")) }
            get("/finance/export") { call.respond(mapOf("message" to "TODO: xuất báo cáo")) }
            // Users
            get("/users") { call.respond(mapOf("message" to "TODO: danh sách người dùng")) }
            put("/users/{id}/ban") { call.respond(mapOf("message" to "TODO: khoá tài khoản")) }
            // Shops
            get("/shops") { call.respond(mapOf("message" to "TODO: medical supply store list")) }
            put("/shops/{id}/approve") { call.respond(mapOf("message" to "TODO: approve supplier")) }
            put("/shops/{id}/reject") { call.respond(mapOf("message" to "TODO: reject supplier")) }
            // All orders
            get("/orders") { call.respond(mapOf("message" to "TODO: tất cả đơn hàng")) }
            // Banners
            post("/banners") { call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: thêm banner")) }
            put("/banners/{id}") { call.respond(mapOf("message" to "TODO: sửa banner")) }
            delete("/banners/{id}") { call.respond(mapOf("message" to "TODO: xoá banner")) }
            // Rewards config
            get("/rewards/config") { call.respond(mapOf("message" to "TODO: cấu hình điểm thưởng")) }
            put("/rewards/config") { call.respond(mapOf("message" to "TODO: cập nhật cấu hình")) }
        }
    }
}
