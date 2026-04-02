package com.example.nhathuoc.routes

import com.example.nhathuoc.service.*
import com.example.nhathuoc.util.requireShopAccess
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.math.BigDecimal

fun Route.productRoutes() {
    val productService = ProductService()

    route("/products") {
        // GET /api/v1/products - Public endpoint
        get {
            try {
                val categoryId = call.parameters["category"]
                val brand = call.parameters["brand"]
                val minPrice = call.parameters["minPrice"]?.toBigDecimalOrNull()
                val maxPrice = call.parameters["maxPrice"]?.toBigDecimalOrNull()
                val sortBy = call.parameters["sortBy"] ?: "name"
                val page = call.parameters["page"]?.toIntOrNull() ?: 1
                val limit = call.parameters["limit"]?.toIntOrNull() ?: 20

                if (page < 1 || limit < 1 || limit > 100) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid page or limit. Page must be >= 1, limit must be 1-100")
                    )
                }

                val response = productService.getProducts(
                    categoryId = categoryId,
                    brand = brand,
                    minPrice = minPrice,
                    maxPrice = maxPrice,
                    sortBy = sortBy,
                    page = page,
                    limit = limit
                )

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "data" to response.products,
                        "pagination" to mapOf(
                            "page" to response.page,
                            "limit" to response.limit,
                            "total" to response.total,
                            "totalPages" to response.totalPages
                        ),
                        "message" to "Get products successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to get products: ${e.message}")
                )
            }
        }

        // GET /api/v1/products/{id} - Public endpoint
        get("/{id}") {
            val productId = call.parameters["id"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Product ID is required")
                )

            try {
                val product = productService.getProductById(productId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Product not found")
                    )

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "data" to product,
                        "message" to "Get product successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to get product: ${e.message}")
                )
            }
        }

        // SHOP only routes
        authenticate("auth-jwt") {
            // POST /api/v1/products - SHOP only
            post {
                try {
                    val (principal, shopId) = call.requireShopAccess()

                    val request = call.receive<CreateProductRequest>()

                    val productId = productService.createProduct(shopId, request)

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to mapOf("id" to productId),
                            "message" to "Product created successfully"
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
                        mapOf("error" to "Failed to create product: ${e.message}")
                    )
                }
            }

            // PUT /api/v1/products/{id} - SHOP only
            put("/{id}") {
                try {
                    val (principal, shopId) = call.requireShopAccess()

                    val productId = call.parameters["id"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Product ID is required")
                        )

                    val request = call.receive<UpdateProductRequest>()

                    productService.updateProduct(productId, shopId, request)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Product updated successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update product: ${e.message}")
                    )
                }
            }

            // DELETE /api/v1/products/{id} - SHOP only
            delete("/{id}") {
                try {
                    val (principal, shopId) = call.requireShopAccess()

                    val productId = call.parameters["id"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Product ID is required")
                        )

                    productService.deleteProduct(productId, shopId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Product deleted successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to delete product: ${e.message}")
                    )
                }
            }
        }
    }
}