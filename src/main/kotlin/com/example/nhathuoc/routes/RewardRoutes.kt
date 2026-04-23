package com.example.nhathuoc.routes

import com.example.nhathuoc.service.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class RewardRedeemResultResponse(
    val redemptionId: String
)

fun Route.rewardRoutes() {
    val rewardService = RewardService()

    route("/rewards") {
        // GET /api/v1/rewards/products - Get reward products (public)
        get("/products") {
            try {
                val minPoints = call.parameters["minPoints"]?.toIntOrNull()
                val maxPoints = call.parameters["maxPoints"]?.toIntOrNull()

                val products = if (minPoints != null || maxPoints != null) {
                    rewardService.getRewardProductsByPointRange(minPoints, maxPoints)
                } else {
                    rewardService.getRewardProducts()
                }

                call.respond(
                    HttpStatusCode.OK,
                    RouteDataMessageResponse(
                        data = products,
                        message = "Reward products retrieved successfully"
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to get reward products: ${e.message}")
                )
            }
        }

        authenticate("auth-jwt") {
            // GET /api/v1/rewards - Get user's reward summary
            get {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val summary = rewardService.getRewardSummary(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = summary,
                            message = "Reward summary retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get reward summary: ${e.message}")
                    )
                }
            }

            // GET /api/v1/rewards/account - Get reward account
            get("/account") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val account = rewardService.getOrCreateRewardAccount(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = account,
                            message = "Reward account retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get reward account: ${e.message}")
                    )
                }
            }

            // GET /api/v1/rewards/transactions - Get reward transactions
            get("/transactions") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val limit = call.parameters["limit"]?.toIntOrNull() ?: 50
                    val transactions = rewardService.getRewardTransactions(userId, limit)

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = transactions,
                            message = "Reward transactions retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get reward transactions: ${e.message}")
                    )
                }
            }

            // GET /api/v1/rewards/redemptions - Get user redemptions
            get("/redemptions") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val redemptions = rewardService.getUserRedemptions(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = redemptions,
                            message = "User redemptions retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get user redemptions: ${e.message}")
                    )
                }
            }

            // POST /api/v1/rewards/redeem - Redeem reward
            post("/redeem") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val request = call.receive<RedeemRewardRequest>()
                    val redemptionId = rewardService.redeemReward(userId, request)

                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(
                            data = RewardRedeemResultResponse(redemptionId),
                            message = "Reward redeemed successfully"
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
                        mapOf("error" to "Failed to redeem reward: ${e.message}")
                    )
                }
            }
        }
    }
}
