package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.BannersTable
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class BannerDto(
    val id: String,
    val imageUrl: String,
    val linkUrl: String?,
    val title: String?,
    val description: String?,
    val sortOrder: Int,
    val isActive: Boolean,
    val startDt: String?,
    val endDt: String?,
    val createdAt: String?
)

fun Route.bannerRoutes() {
    route("/banners") {
        get {
            try {
                val activeOnly = call.parameters["activeOnly"]?.toBooleanStrictOrNull()
                    ?: call.parameters["isActive"]?.toBooleanStrictOrNull()
                    ?: true
                val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

                val banners = transaction {
                    BannersTable
                        .selectAll()
                        .where {
                            if (!activeOnly) {
                                Op.TRUE
                            } else {
                                (BannersTable.isActive eq true) and
                                    (BannersTable.startDt.isNull() or (BannersTable.startDt lessEq now)) and
                                    (BannersTable.endDt.isNull() or (BannersTable.endDt greaterEq now))
                            }
                        }
                        .orderBy(BannersTable.sortOrder to SortOrder.ASC)
                        .map { row ->
                            BannerDto(
                                id = row[BannersTable.id],
                                imageUrl = row[BannersTable.imageUrl],
                                linkUrl = row[BannersTable.linkUrl],
                                title = row[BannersTable.title],
                                description = row[BannersTable.description],
                                sortOrder = row[BannersTable.sortOrder],
                                isActive = row[BannersTable.isActive],
                                startDt = row[BannersTable.startDt]?.toString(),
                                endDt = row[BannersTable.endDt]?.toString(),
                                createdAt = row[BannersTable.createdAt].toString()
                            )
                        }
                }

                call.respond(
                    HttpStatusCode.OK,
                    RouteDataMessageResponse(
                        data = banners,
                        message = "Banners retrieved successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    RouteErrorResponse("Failed to get banners: ${e.message}")
                )
            }
        }
    }
}
