package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.database.tables.ProductsTable
import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.util.AppRoles
import com.example.nhathuoc.util.requireInternalAccess
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
private data class InternalDashboardPeriodDto(
    val revenue: Double,
    val orderCount: Int,
    val completedOrderCount: Int,
    val pendingOrderCount: Int,
    val posRevenue: Double,
    val posOrderCount: Int,
    val posCompletedOrderCount: Int,
    val onlineRevenue: Double,
    val onlineOrderCount: Int,
    val onlineCompletedOrderCount: Int
)

@Serializable
private data class InternalDashboardOrderDto(
    val orderId: String,
    val orderCode: String,
    val customerName: String,
    val total: Double,
    val status: String,
    val paymentStatus: String,
    val orderChannel: String,
    val createdAt: String
)

@Serializable
private data class InternalDashboardDataDto(
    val totalRevenue: Double,
    val totalOrders: Int,
    val totalProducts: Int,
    val totalCustomers: Int,
    val pendingOrders: Int,
    val today: InternalDashboardPeriodDto,
    val month: InternalDashboardPeriodDto,
    val recentOrders: List<InternalDashboardOrderDto>
)

@Serializable
private data class InternalDashboardResponseDto(
    val data: InternalDashboardDataDto,
    val message: String
)

private fun ResultRow.isSuccessfulOrder(): Boolean {
    val status = this[OrdersTable.status].uppercase()
    val paymentStatus = this[OrdersTable.paymentStatus].uppercase()
    if (status == "RETURNED" || status == "CANCELLED") return false
    if (paymentStatus == "REFUNDED") return false
    return status == "DELIVERED" || paymentStatus == "COMPLETED" || paymentStatus == "PARTIALLY_REFUNDED"
}

private fun ResultRow.isPendingOrder(): Boolean {
    return when (this[OrdersTable.status].uppercase()) {
        "PENDING", "PROCESSING", "SHIPPING" -> true
        else -> false
    }
}

private fun ResultRow.orderTotal(): Double {
    return this[OrdersTable.total]?.toDouble() ?: 0.0
}

private fun buildDashboardPeriod(rows: List<ResultRow>): InternalDashboardPeriodDto {
    val completedOrders = rows.filter { it.isSuccessfulOrder() }
    val pendingOrders = rows.filter { it.isPendingOrder() }
    val posOrders = rows.filter { it[OrdersTable.orderChannel].uppercase() == "POS" }
    val onlineOrders = rows.filter { it[OrdersTable.orderChannel].uppercase() == "ONLINE" }
    val posCompletedOrders = posOrders.filter { it.isSuccessfulOrder() }
    val onlineCompletedOrders = onlineOrders.filter { it.isSuccessfulOrder() }

    return InternalDashboardPeriodDto(
        revenue = completedOrders.sumOf { it.orderTotal() },
        orderCount = rows.size,
        completedOrderCount = completedOrders.size,
        pendingOrderCount = pendingOrders.size,
        posRevenue = posCompletedOrders.sumOf { it.orderTotal() },
        posOrderCount = posOrders.size,
        posCompletedOrderCount = posCompletedOrders.size,
        onlineRevenue = onlineCompletedOrders.sumOf { it.orderTotal() },
        onlineOrderCount = onlineOrders.size,
        onlineCompletedOrderCount = onlineCompletedOrders.size
    )
}

fun Route.internalDashboardRoutes() {
    authenticate("auth-jwt") {
        route("/internal/dashboard") {
            get {
                call.requireInternalAccess()

                val data = transaction {
                    val now = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Ho_Chi_Minh"))
                    val today = now.date
                    val allOrders = OrdersTable.selectAll().toList()
                    val successfulOrders = allOrders.filter { it.isSuccessfulOrder() }
                    val todayOrders = allOrders.filter { it[OrdersTable.createdAt].date == today }
                    val monthOrders = allOrders.filter { row ->
                        val createdDate = row[OrdersTable.createdAt].date
                        createdDate.year == today.year && createdDate.monthNumber == today.monthNumber
                    }

                    val totalProducts = ProductsTable.selectAll().count().toInt()
                    val totalCustomers = UsersTable
                        .selectAll()
                        .where { UsersTable.role eq AppRoles.USER }
                        .count()
                        .toInt()

                    val recentOrders = OrdersTable
                        .selectAll()
                        .orderBy(OrdersTable.createdAt to SortOrder.DESC)
                        .limit(8)
                        .map { order ->
                            val customerId = order[OrdersTable.userId]
                            val customer = customerId?.let { id ->
                                UsersTable.selectAll()
                                    .where { UsersTable.id eq id }
                                    .singleOrNull()
                            }

                            InternalDashboardOrderDto(
                                orderId = order[OrdersTable.id],
                                orderCode = order[OrdersTable.orderCode],
                                customerName = customer?.get(UsersTable.fullName) ?: customerId ?: "Khách tại quầy",
                                total = order.orderTotal(),
                                status = order[OrdersTable.status],
                                paymentStatus = order[OrdersTable.paymentStatus],
                                orderChannel = order[OrdersTable.orderChannel],
                                createdAt = order[OrdersTable.createdAt].toString()
                            )
                        }

                    InternalDashboardDataDto(
                        totalRevenue = successfulOrders.sumOf { it.orderTotal() },
                        totalOrders = allOrders.size,
                        totalProducts = totalProducts,
                        totalCustomers = totalCustomers,
                        pendingOrders = allOrders.count { it.isPendingOrder() },
                        today = buildDashboardPeriod(todayOrders),
                        month = buildDashboardPeriod(monthOrders),
                        recentOrders = recentOrders
                    )
                }

                call.respond(
                    InternalDashboardResponseDto(
                        data = data,
                        message = "Get internal dashboard successfully"
                    )
                )
            }
        }
    }
}
