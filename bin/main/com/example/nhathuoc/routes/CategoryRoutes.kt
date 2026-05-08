package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.CategoriesTable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
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

@Serializable
private data class CategoryListEnvelope(
    val data: List<CategoryDto>,
    val message: String
)

fun Route.categoryRoutes() {
    route("/categories") {
        // GET /api/v1/categories - Public endpoint
        get {
            try {
                val categories = transaction {
                    CategoriesTable.selectAll()
                        .mapNotNull { row ->
                            if (!row[CategoriesTable.isActive] || row[CategoriesTable.deletedAt] != null) {
                                return@mapNotNull null
                            }

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
                        .sortedBy { it.sortOrder }
                }

                val response = buildJsonObject {
                    put(
                        "data",
                        JsonArray(
                            categories.map { category ->
                                buildJsonObject {
                                    put("id", JsonPrimitive(category.id))
                                    put("parentId", category.parentId?.let(::JsonPrimitive) ?: JsonNull)
                                    put("name", JsonPrimitive(category.name))
                                    put("slug", category.slug?.let(::JsonPrimitive) ?: JsonNull)
                                    put("description", category.description?.let(::JsonPrimitive) ?: JsonNull)
                                    put("iconUrl", category.iconUrl?.let(::JsonPrimitive) ?: JsonNull)
                                    put("sortOrder", JsonPrimitive(category.sortOrder))
                                    put("isActive", JsonPrimitive(category.isActive))
                                    put("productTypeDefault", category.productTypeDefault?.let(::JsonPrimitive) ?: JsonNull)
                                }
                            }
                        )
                    )
                    put("message", JsonPrimitive("Get categories successfully"))
                }

                call.respondText(
                    text = response.toString(),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.OK
                )
            } catch (e: Exception) {
                call.application.log.error("Failed to get categories", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to get categories: ${e.message}")
                )
            }
        }

        // Compatibility endpoint. Dynamic attributes were removed from the simplified schema.
        get("/{categoryId}/attributes") {
            val categoryId = call.parameters["categoryId"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Category ID is required")
                )

            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "data" to emptyList<Any>(),
                    "message" to "Dynamic category attributes were removed from this project scope"
                )
            )
        }
    }
}
