package com.example.nhathuoc.routes

import com.example.nhathuoc.service.CreateStockReceiptRequest
import com.example.nhathuoc.service.InventoryService
import com.example.nhathuoc.service.ProductService
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.requireInternalAccess
import io.ktor.server.application.ApplicationCall
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
private data class DesktopStockPayload(
    val mfgDate: String? = null,
    val expDate: String? = null,
    val quantity: Int,
    val importPrice: Double? = null,
    val note: String? = null
)

@Serializable
private data class ProductDeleteRequestPayloadCompat(
    val reason: String? = null
)

fun Route.productDesktopCompatRoutes() {
    val productService = ProductService()
    val inventoryService = InventoryService()

    authenticate("auth-jwt") {
        route("/products") {
            post("/{id}/stock") {
                call.handleDesktopStockReceipt(inventoryService)
            }

            post("/{id}/delete-request") {
                try {
                    val (principal, _) = call.requireInternalAccess()
                    val productId = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Product ID is required"))
                    val requesterUserId = principal.getUserId()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid token user"))
                    val payload = runCatching { call.receive<ProductDeleteRequestPayloadCompat>() }
                        .getOrDefault(ProductDeleteRequestPayloadCompat())

                    val requestId = productService.createDeleteRequest(productId, requesterUserId, payload.reason)
                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to mapOf("id" to requestId),
                            "message" to "Product archived successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to archive product: ${e.message}"))
                }
            }
        }
    }
}

private suspend fun ApplicationCall.handleDesktopStockReceipt(inventoryService: InventoryService) {
    try {
        requireInternalAccess()
        val productId = parameters["id"]
            ?: return respond(HttpStatusCode.BadRequest, mapOf("error" to "Product ID is required"))
        val payload = receive<DesktopStockPayload>()
        if (payload.quantity <= 0) {
            return respond(HttpStatusCode.BadRequest, mapOf("error" to "quantity must be > 0"))
        }

        val productStockId = inventoryService.receiveStock(
            CreateStockReceiptRequest(
                productId = productId,
                mfgDate = payload.mfgDate?.trim()?.ifBlank { null }?.let(LocalDate::parse),
                expDate = payload.expDate?.trim()?.ifBlank { null }?.let(LocalDate::parse),
                quantity = payload.quantity,
                importPrice = payload.importPrice?.let(BigDecimal::valueOf),
                note = payload.note?.trim()?.ifBlank { null }
            )
        )

        respond(mapOf("data" to productStockId, "message" to "Product stock updated successfully"))
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
    } catch (e: Exception) {
        respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to update product stock: ${e.message}"))
    }
}
