package com.example.nhathuoc.routes

import com.example.nhathuoc.service.CreateBatchRequest
import com.example.nhathuoc.service.InventoryService
import com.example.nhathuoc.service.ProductService
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.requireInternalAccess
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
private data class DesktopBatchPayload(
    val lotNumber: String? = null,
    val mfgDate: String? = null,
    val expDate: String? = null,
    val quantity: Int,
    val importPrice: Double? = null,
    val note: String? = null
)

@Serializable
private data class ProductDiseaseLinkPayload(
    val diseaseIds: List<String> = emptyList()
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
            post("/{id}/batches") {
                try {
                    call.requireInternalAccess()
                    val productId = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Product ID is required"))
                    val payload = call.receive<DesktopBatchPayload>()
                    if (payload.quantity <= 0) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "quantity must be > 0"))
                    }

                    val batchId = inventoryService.createBatch(
                        CreateBatchRequest(
                            productId = productId,
                            lotNumber = payload.lotNumber?.trim()?.ifBlank { null },
                            mfgDate = payload.mfgDate?.trim()?.ifBlank { null }?.let(LocalDate::parse),
                            expDate = payload.expDate?.trim()?.ifBlank { null }?.let(LocalDate::parse),
                            quantity = payload.quantity,
                            importPrice = payload.importPrice?.let(BigDecimal::valueOf),
                            note = payload.note?.trim()?.ifBlank { null }
                        )
                    )

                    call.respond(mapOf("data" to batchId, "message" to "Inventory batch created successfully"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create batch: ${e.message}"))
                }
            }

            post("/{id}/diseases") {
                try {
                    call.requireInternalAccess()
                    val productId = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Product ID is required"))
                    val payload = call.receive<ProductDiseaseLinkPayload>()
                    productService.replaceProductDiseases(productId, payload.diseaseIds)
                    call.respond(mapOf("message" to "Product diseases updated successfully"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to update product diseases: ${e.message}"))
                }
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
                            "message" to "Delete request submitted and waiting for admin approval"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to submit delete request: ${e.message}"))
                }
            }
        }
    }
}
