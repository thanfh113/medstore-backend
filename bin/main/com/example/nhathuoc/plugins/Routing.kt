package com.example.nhathuoc.plugins

import com.example.nhathuoc.routes.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        // Health check
        get("/") {
            call.respond(mapOf("status" to "ok", "message" to "Nhà Thuốc API đang hoạt động"))
        }

        route("/api/v1") {
            authRoutes()
            userRoutes()
            productRoutes()
            categoryRoutes()
            cartRoutes()
            orderRoutes()
            rewardRoutes()
            vaccineRoutes()
            chatRoutes()
            pharmacyRoutes()
            notificationRoutes()
            bannerRoutes()
            healthArticleRoutes()
            adminRoutes()
        }
    }
}
