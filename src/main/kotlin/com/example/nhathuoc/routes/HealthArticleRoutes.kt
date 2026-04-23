package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.HealthArticlesTable
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
private data class HealthArticleSummaryDto(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val author: String?,
    val category: String?,
    val publishedAt: String?
)

@Serializable
private data class HealthArticleDetailDto(
    val id: String,
    val title: String,
    val content: String?,
    val thumbnailUrl: String?,
    val author: String?,
    val category: String?,
    val publishedAt: String?,
    val createdAt: String,
    val updatedAt: String
)

fun Route.healthArticleRoutes() {
    route("/technical-articles") {
        get {
            try {
                val category = call.parameters["category"]?.trim()?.takeIf { it.isNotEmpty() }
                val limit = call.parameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20

                val articles = transaction {
                    HealthArticlesTable
                        .selectAll()
                        .where {
                            (HealthArticlesTable.isPublished eq true) and
                                if (category != null) {
                                    HealthArticlesTable.category eq category
                                } else {
                                    Op.TRUE
                                }
                        }
                        .orderBy(HealthArticlesTable.publishedAt to SortOrder.DESC, HealthArticlesTable.createdAt to SortOrder.DESC)
                        .limit(limit)
                        .map { row ->
                            HealthArticleSummaryDto(
                                id = row[HealthArticlesTable.id],
                                title = row[HealthArticlesTable.title],
                                thumbnailUrl = row[HealthArticlesTable.thumbnailUrl],
                                author = row[HealthArticlesTable.author],
                                category = row[HealthArticlesTable.category],
                                publishedAt = row[HealthArticlesTable.publishedAt]?.toString()
                            )
                        }
                }

                call.respond(
                    HttpStatusCode.OK,
                    RouteDataMessageResponse(
                        data = articles,
                        message = "Technical articles retrieved successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    RouteErrorResponse("Failed to get technical articles: ${e.message}")
                )
            }
        }

        get("/{id}") {
            try {
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, RouteErrorResponse("Article ID is required"))

                val article = transaction {
                    val row = HealthArticlesTable
                        .selectAll()
                        .where { (HealthArticlesTable.id eq id) and (HealthArticlesTable.isPublished eq true) }
                        .singleOrNull()
                        ?: return@transaction null

                    HealthArticleDetailDto(
                        id = row[HealthArticlesTable.id],
                        title = row[HealthArticlesTable.title],
                        content = row[HealthArticlesTable.content],
                        thumbnailUrl = row[HealthArticlesTable.thumbnailUrl],
                        author = row[HealthArticlesTable.author],
                        category = row[HealthArticlesTable.category],
                        publishedAt = row[HealthArticlesTable.publishedAt]?.toString(),
                        createdAt = row[HealthArticlesTable.createdAt].toString(),
                        updatedAt = row[HealthArticlesTable.updatedAt].toString()
                    )
                }

                if (article == null) {
                    return@get call.respond(HttpStatusCode.NotFound, RouteErrorResponse("Article not found"))
                }

                call.respond(
                    HttpStatusCode.OK,
                    RouteDataMessageResponse(
                        data = article,
                        message = "Technical article retrieved successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    RouteErrorResponse("Failed to get technical article: ${e.message}")
                )
            }
        }
    }
}
