package com.example.nhathuoc.plugins

import com.example.nhathuoc.routes.*
import com.example.nhathuoc.util.LocalUploadStorage
import io.ktor.server.application.*
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respond(mapOf("status" to "ok", "message" to "Medical Supply API is running"))
        }

        staticFiles("/static/uploads", LocalUploadStorage.rootDirectory())

        route("/api/v1") {
            authRoutes()
            userRoutes()

            productRoutes()
            productDesktopCompatRoutes()
            categoryRoutes()
            inventoryRoutes()
            orderFulfillmentRoutes()
            internalOrderRoutes()
            internalDashboardRoutes()
            couponRoutes()
            posOrderRoutes()

            checkoutRoutes()
            paymentRoutes()

            cartRoutes()
            rewardRoutes()
            reviewRoutes()
            complaintRoutes()
            chatRoutes()
            internalChatRoutes()
            settingsRoutes()
            notificationRoutes()
            bannerRoutes()
            mobileCompatRoutes()
            uploadRoutes()
            adminRoutes()
        }
    }
}
