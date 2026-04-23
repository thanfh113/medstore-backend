package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.PharmacyBranchesTable
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
private data class PharmacyBranchDto(
    val id: String,
    val name: String,
    val address: String,
    val ward: String,
    val district: String,
    val province: String,
    val phone: String,
    val email: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val distance: Double? = null,
    val rating: Double? = null,
    val totalReviews: Int? = null,
    val isOpen: Boolean = true,
    val openingHours: String? = null,
    val services: List<String> = emptyList(),
    val imageUrl: String? = null,
    val isVerified: Boolean = true,
    val isActive: Boolean = true,
    val managerName: String? = null,
    val managerPhone: String? = null,
    val createdAt: String,
    val updatedAt: String
)

fun Route.pharmacyRoutes() {
    route("/pharmacies") {
        get {
            try {
                val search = call.parameters["search"]?.trim()?.takeIf { it.isNotEmpty() }
                val activeOnly = call.parameters["activeOnly"]?.toBooleanStrictOrNull() ?: true

                val branches = transaction {
                    PharmacyBranchesTable
                        .selectAll()
                        .where {
                            val baseCondition =
                                if (activeOnly) {
                                    PharmacyBranchesTable.isActive eq true
                                } else {
                                    Op.TRUE
                                }

                            if (search == null) {
                                baseCondition
                            } else {
                                baseCondition and (
                                    (PharmacyBranchesTable.name like "%$search%") or
                                        (PharmacyBranchesTable.address like "%$search%")
                                    )
                            }
                        }
                        .orderBy(PharmacyBranchesTable.name to SortOrder.ASC)
                        .map(::toPharmacyBranchDto)
                }

                call.respond(
                    HttpStatusCode.OK,
                    RoutePaginatedDataMessageResponse(
                        data = branches,
                        pagination = RoutePaginationResponse(
                            page = 1,
                            limit = branches.size,
                            total = branches.size,
                            totalPages = if (branches.isEmpty()) 0 else 1,
                            hasNext = false,
                            hasPrev = false
                        ),
                        message = "Pharmacy branches retrieved successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    RouteErrorResponse("Failed to get pharmacy branches: ${e.message}")
                )
            }
        }

        get("/{id}") {
            try {
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, RouteErrorResponse("Pharmacy ID is required"))

                val branch = transaction {
                    PharmacyBranchesTable
                        .selectAll()
                        .where {
                            (PharmacyBranchesTable.id eq id) and
                                (PharmacyBranchesTable.isActive eq true)
                        }
                        .singleOrNull()
                        ?.let(::toPharmacyBranchDto)
                }

                if (branch == null) {
                    return@get call.respond(HttpStatusCode.NotFound, RouteErrorResponse("Pharmacy branch not found"))
                }

                call.respond(HttpStatusCode.OK, branch)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    RouteErrorResponse("Failed to get pharmacy branch: ${e.message}")
                )
            }
        }
    }
}

private fun toPharmacyBranchDto(row: ResultRow): PharmacyBranchDto {
    return PharmacyBranchDto(
        id = row[PharmacyBranchesTable.id],
        name = row[PharmacyBranchesTable.name],
        address = row[PharmacyBranchesTable.address],
        ward = "",
        district = "",
        province = "",
        phone = row[PharmacyBranchesTable.phone] ?: "",
        latitude = row[PharmacyBranchesTable.latitude]?.toDouble(),
        longitude = row[PharmacyBranchesTable.longitude]?.toDouble(),
        openingHours = listOfNotNull(row[PharmacyBranchesTable.openTime], row[PharmacyBranchesTable.closeTime])
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" - "),
        isActive = row[PharmacyBranchesTable.isActive],
        createdAt = "",
        updatedAt = ""
    )
}
