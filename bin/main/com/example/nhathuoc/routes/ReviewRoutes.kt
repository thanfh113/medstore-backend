package com.example.nhathuoc.routes

import com.example.nhathuoc.service.CreateReviewRequest
import com.example.nhathuoc.service.ModerateReviewRequest
import com.example.nhathuoc.service.ReportReviewRequest
import com.example.nhathuoc.service.ReviewService
import com.example.nhathuoc.service.UpdateReviewReportRequest
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.requireInternalAccess
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
private data class ReviewIdResponse(val id: String)

fun Route.reviewRoutes() {
    val reviewService = ReviewService()

    route("/products/{productId}/reviews") {
        get {
            val productId = call.parameters["productId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Product ID is required"))
            val reviews = reviewService.listProductReviews(productId, includeHidden = false)
            call.respond(reviews)
        }

        authenticate("auth-jwt") {
            post {
                val productId = call.parameters["productId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Product ID is required"))
                val userId = call.principal<JWTPrincipal>()?.getUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val request = call.receive<CreateReviewRequest>()

                try {
                    val review = reviewService.createReview(productId, userId, request)
                    call.respond(HttpStatusCode.Created, review)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }
    }

    authenticate("auth-jwt") {
        post("/reviews/{id}/report") {
            val reviewId = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Review ID is required"))
            val userId = call.principal<JWTPrincipal>()?.getUserId()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
            val request = call.receive<ReportReviewRequest>()

            try {
                val reportId = reviewService.reportReview(reviewId, userId, request)
                call.respond(HttpStatusCode.Created, ReviewIdResponse(reportId))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            }
        }

        route("/internal/reviews") {
            get {
                call.requireInternalAccess()
                val status = call.request.queryParameters["status"]
                val productId = call.request.queryParameters["productId"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                call.respond(reviewService.listInternalReviews(status, productId, limit))
            }

            patch("/{id}") {
                val principal = call.requireInternalAccess().first
                val moderatorId = principal.getUserId()
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val reviewId = call.parameters["id"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Review ID is required"))
                val request = call.receive<ModerateReviewRequest>()

                try {
                    val review = reviewService.moderateReview(reviewId, moderatorId, request)
                    call.respond(review)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }

        route("/internal/review-reports") {
            get {
                call.requireInternalAccess()
                val status = call.request.queryParameters["status"]
                val reviewId = call.request.queryParameters["reviewId"]
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
                call.respond(reviewService.listInternalReviewReports(status, reviewId, limit))
            }

            patch("/{id}") {
                val principal = call.requireInternalAccess().first
                val moderatorId = principal.getUserId()
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                val reportId = call.parameters["id"]
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Report ID is required"))
                val request = call.receive<UpdateReviewReportRequest>()

                try {
                    val report = reviewService.updateReviewReport(reportId, moderatorId, request)
                    call.respond(report)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }
    }
}
