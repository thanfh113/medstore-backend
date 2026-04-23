package com.example.nhathuoc.routes

import com.example.nhathuoc.service.AddToCartRequest
import com.example.nhathuoc.service.CartItemDto
import com.example.nhathuoc.service.CartService
import com.example.nhathuoc.service.CartSummaryDto
import com.example.nhathuoc.service.UpdateCartItemRequest
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

@Serializable
data class CartItemResponse(
    val id: String,
    val userId: String,
    val productId: String,
    val productName: String,
    val productPrice: Double,
    val productImageUrl: String?,
    val quantity: Int,
    val unit: String,
    val subtotal: Double,
    val isAvailable: Boolean,
    val stock: Int,
    val createdAt: String
)

@Serializable
data class CartSummaryResponse(
    val items: List<CartItemResponse>,
    val totalItems: Int,
    val subtotal: Double,
    val estimatedShipping: Double,
    val total: Double
)

@Serializable
data class CartItemIdResponse(
    val cartItemId: String
)

@Serializable
data class CartCountResponse(
    val count: Int
)

@Serializable
data class CartValidationResponse(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
)

@Serializable
data class CartCleanupResponse(
    val removedItems: Int
)

@Serializable
data class CartMessageResponse(
    val message: String
)

private fun CartItemDto.toResponse() = CartItemResponse(
    id = id,
    userId = userId,
    productId = productId,
    productName = productName,
    productPrice = productPrice.toDouble(),
    productImageUrl = productImageUrl,
    quantity = quantity,
    unit = unit,
    subtotal = subtotal.toDouble(),
    isAvailable = isAvailable,
    stock = stock,
    createdAt = createdAt.toString()
)

private fun CartSummaryDto.toResponse() = CartSummaryResponse(
    items = items.map { it.toResponse() },
    totalItems = totalItems,
    subtotal = subtotal.toDouble(),
    estimatedShipping = estimatedShipping.toDouble(),
    total = total.toDouble()
)

private fun JWTPrincipal?.userIdOrNull(): String? =
    this?.payload?.getClaim("userId")?.asString()

fun Route.cartRoutes() {
    val cartService = CartService()

    authenticate("auth-jwt") {
        route("/cart") {
            get {
                try {
                    val userId = call.principal<JWTPrincipal>().userIdOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            RouteErrorResponse("User ID not found")
                        )

                    val cartSummary = cartService.getCartSummary(userId).toResponse()
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = cartSummary,
                            message = "Cart retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to get cart: ${e.message}")
                    )
                }
            }

            post("/items") {
                try {
                    val userId = call.principal<JWTPrincipal>().userIdOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            RouteErrorResponse("User ID not found")
                        )

                    val request = call.receive<AddToCartRequest>()
                    val cartItemId = cartService.addToCart(userId, request)

                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(
                            data = CartItemIdResponse(cartItemId),
                            message = "Item added to cart successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        RouteErrorResponse(e.message ?: "Invalid cart item")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to add item to cart: ${e.message}")
                    )
                }
            }

            put("/items/{id}") {
                try {
                    val userId = call.principal<JWTPrincipal>().userIdOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.Unauthorized,
                            RouteErrorResponse("User ID not found")
                        )

                    val cartItemId = call.parameters["id"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            RouteErrorResponse("Cart item ID is required")
                        )

                    val request = call.receive<UpdateCartItemRequest>()
                    cartService.updateCartItem(userId, cartItemId, request)

                    call.respond(
                        HttpStatusCode.OK,
                        CartMessageResponse("Cart item updated successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        RouteErrorResponse(e.message ?: "Invalid cart item")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to update cart item: ${e.message}")
                    )
                }
            }

            delete("/items/{id}") {
                try {
                    val userId = call.principal<JWTPrincipal>().userIdOrNull()
                        ?: return@delete call.respond(
                            HttpStatusCode.Unauthorized,
                            RouteErrorResponse("User ID not found")
                        )

                    val cartItemId = call.parameters["id"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            RouteErrorResponse("Cart item ID is required")
                        )

                    cartService.removeFromCart(userId, cartItemId)

                    call.respond(
                        HttpStatusCode.OK,
                        CartMessageResponse("Item removed from cart successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        RouteErrorResponse(e.message ?: "Invalid cart item")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to remove cart item: ${e.message}")
                    )
                }
            }

            delete("/clear") {
                try {
                    val userId = call.principal<JWTPrincipal>().userIdOrNull()
                        ?: return@delete call.respond(
                            HttpStatusCode.Unauthorized,
                            RouteErrorResponse("User ID not found")
                        )

                    cartService.clearCart(userId)

                    call.respond(
                        HttpStatusCode.OK,
                        CartMessageResponse("Cart cleared successfully")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to clear cart: ${e.message}")
                    )
                }
            }

            get("/count") {
                try {
                    val userId = call.principal<JWTPrincipal>().userIdOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            RouteErrorResponse("User ID not found")
                        )

                    val count = cartService.getCartItemCount(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = CartCountResponse(count),
                            message = "Cart count retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to get cart count: ${e.message}")
                    )
                }
            }

            post("/validate") {
                try {
                    val userId = call.principal<JWTPrincipal>().userIdOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            RouteErrorResponse("User ID not found")
                        )

                    val errors = cartService.validateCartForCheckout(userId)
                    val isValid = errors.isEmpty()
                    call.respond(
                        if (isValid) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                        RouteDataMessageResponse(
                            data = CartValidationResponse(isValid = isValid, errors = errors),
                            message = if (isValid) {
                                "Cart is valid for checkout"
                            } else {
                                "Cart validation failed"
                            }
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to validate cart: ${e.message}")
                    )
                }
            }

            post("/cleanup") {
                try {
                    val userId = call.principal<JWTPrincipal>().userIdOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            RouteErrorResponse("User ID not found")
                        )

                    val removedCount = cartService.cleanupCart(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = CartCleanupResponse(removedCount),
                            message = "Cart cleaned up successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        RouteErrorResponse("Failed to cleanup cart: ${e.message}")
                    )
                }
            }
        }
    }
}
