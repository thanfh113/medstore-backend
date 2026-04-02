package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.CategoriesTable
import com.example.nhathuoc.service.CategoryAttributeService
import com.example.nhathuoc.service.CreateCategoryAttributeRequest
import com.example.nhathuoc.service.UpdateCategoryAttributeRequest
import com.example.nhathuoc.util.requireRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class CategoryDto(
    val id: String,
    val parentId: String?,
    val name: String,
    val slug: String?,
    val description: String?,
    val iconUrl: String?,
    val sortOrder: Int,
    val isActive: Boolean,
    val productTypeDefault: String?  // Match DB: nullable
)

fun Route.categoryRoutes() {
    val categoryAttributeService = CategoryAttributeService()

    route("/categories") {
        // GET /api/v1/categories - Public endpoint
        get {
            try {
                val categories = transaction {
                    CategoriesTable.selectAll()
                        .map { row ->
                            CategoryDto(
                                id = row[CategoriesTable.id],
                                parentId = row[CategoriesTable.parentId],
                                name = row[CategoriesTable.name],
                                slug = row[CategoriesTable.slug],
                                description = row[CategoriesTable.description],
                                iconUrl = row[CategoriesTable.iconUrl],
                                sortOrder = row[CategoriesTable.sortOrder],
                                isActive = row[CategoriesTable.isActive],
                                productTypeDefault = row[CategoriesTable.productTypeDefault]
                            )
                        }
                        .filter { it.isActive }
                        .sortedBy { it.sortOrder }
                }

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "data" to categories,
                        "message" to "Get categories successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to get categories: ${e.message}")
                )
            }
        }

        // GET /api/v1/categories/{categoryId}/attributes - Public endpoint
        get("/{categoryId}/attributes") {
            val categoryId = call.parameters["categoryId"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Category ID is required")
                )

            try {
                val attributes = categoryAttributeService.getCategoryAttributes(categoryId)

                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "data" to attributes,
                        "message" to "Get category attributes successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to get category attributes: ${e.message}")
                )
            }
        }

        // ADMIN only routes
        authenticate("auth-jwt") {
            // POST /api/v1/categories/{categoryId}/attributes - ADMIN only
            post("/{categoryId}/attributes") {
                try {
                    val principal = call.requireRole("ADMIN")

                    val categoryId = call.parameters["categoryId"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Category ID is required")
                        )

                    val request = call.receive<CreateCategoryAttributeRequest>()

                    val attributeId = categoryAttributeService.createCategoryAttribute(categoryId, request)

                    call.respond(
                        HttpStatusCode.Created,
                        mapOf(
                            "data" to mapOf("id" to attributeId),
                            "message" to "Category attribute created successfully"
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
                        mapOf("error" to "Failed to create category attribute: ${e.message}")
                    )
                }
            }

            // PUT /api/v1/categories/{categoryId}/attributes/{attrId} - ADMIN only
            put("/{categoryId}/attributes/{attrId}") {
                try {
                    val principal = call.requireRole("ADMIN")

                    val categoryId = call.parameters["categoryId"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Category ID is required")
                        )

                    val attrId = call.parameters["attrId"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Attribute ID is required")
                        )

                    val request = call.receive<UpdateCategoryAttributeRequest>()

                    categoryAttributeService.updateCategoryAttribute(categoryId, attrId, request)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Category attribute updated successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update category attribute: ${e.message}")
                    )
                }
            }

            // DELETE /api/v1/categories/{categoryId}/attributes/{attrId} - ADMIN only
            delete("/{categoryId}/attributes/{attrId}") {
                try {
                    val principal = call.requireRole("ADMIN")

                    val categoryId = call.parameters["categoryId"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Category ID is required")
                        )

                    val attrId = call.parameters["attrId"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Attribute ID is required")
                        )

                    categoryAttributeService.deleteCategoryAttribute(categoryId, attrId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Category attribute deleted successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to delete category attribute: ${e.message}")
                    )
                }
            }
        }
    }
}