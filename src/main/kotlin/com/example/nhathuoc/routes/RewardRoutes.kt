package com.example.nhathuoc.routes

import com.example.nhathuoc.service.*
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.requireInternalAccess
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

            get("/vouchers") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val vouchers = rewardService.getAvailableVouchers(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = vouchers,
                            message = "Reward vouchers retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get reward vouchers: ${e.message}")
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

    authenticate("auth-jwt") {
        route("/internal/rewards") {
            get("/products") {
                call.requireInternalAccess()
                val rewardType = call.request.queryParameters["rewardType"]

                try {
                    val products = rewardService.getAdminRewardProducts(rewardType)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = products,
                            message = "Reward products retrieved successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }

            post("/products") {
                call.requireInternalAccess()
                val request = call.receive<AdminRewardProductUpsertRequest>()

                try {
                    val product = rewardService.createRewardProduct(request)
                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(
                            data = product,
                            message = "Reward product created successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }

            patch("/products/{id}") {
                call.requireInternalAccess()
                val rewardProductId = call.parameters["id"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Reward product ID is required"))
                val request = call.receive<AdminRewardProductUpsertRequest>()

                try {
                    val product = rewardService.updateRewardProduct(rewardProductId, request)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = product,
                            message = "Reward product updated successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }

            get("/redemptions") {
                call.requireInternalAccess()
                val status = call.request.queryParameters["status"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100

                try {
                    val redemptions = rewardService.listRedemptions(status, limit)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = redemptions,
                            message = "Reward redemptions retrieved successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }

            patch("/redemptions/{id}") {
                val principal = call.requireInternalAccess().first
                val actorUserId = principal.getUserId()
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val redemptionId = call.parameters["id"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Redemption ID is required"))
                val request = call.receive<UpdateRewardRedemptionRequest>()

                try {
                    val redemption = rewardService.updateRedemptionStatus(
                        redemptionId = redemptionId,
                        status = request.status,
                        actorUserId = actorUserId,
                        assignedTo = request.assignedTo,
                        note = request.note
                    )
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = redemption,
                            message = "Reward redemption updated successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }

            post("/adjust-points") {
                val principal = call.requireInternalAccess().first
                val actorUserId = principal.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val request = call.receive<AdjustRewardPointsRequest>()

                try {
                    val transaction = rewardService.adjustUserPoints(actorUserId, request)
                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(
                            data = transaction,
                            message = "Reward points adjusted successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }
    }
}
