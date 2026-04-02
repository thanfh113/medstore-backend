package com.example.nhathuoc.plugins

import com.example.nhathuoc.routes.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        // Health check
        get("/") {
            call.respond(mapOf("status" to "ok", "message" to "Medical Supply API is running"))
        }

        route("/api/v1") {
            authRoutes()
            userRoutes()

            // Enhanced routes with full business logic
            productRoutes()          // Replaces legacy productRoutes()
            categoryRoutes()         // Replaces legacy categoryRoutes()
            inventoryRoutes()        // New feature: batch & inventory management
            orderFulfillmentRoutes() // Enhanced order processing with batch allocation
            prescriptionRoutes()     // Medical prescription management

            // Checkout & Payment System
            checkoutRoutes()         // Checkout flow (preview + create order)
            paymentRoutes()          // Payment processing (VNPay, MoMo, COD) + webhooks

            // Medical Consultation System (⚠️ TIER 3 - INCOMPLETE)
            // doctorRoutes()        // Disabled - Service has ORM compatibility issues
            // consultationRoutes()  // Disabled - Service has ORM compatibility issues


            // Legacy routes (TODO: remove after frontend migration)
            // These are deprecated placeholder implementations
            route("/legacy") {
                productRoutesLegacy()
                categoryRoutesLegacy()
                orderRoutesLegacy()
            }

            // Other existing routes (TODO: migrate these to enhanced versions)
            cartRoutes()
            rewardRoutes()
            chatRoutes()
            pharmacyRoutes()
            notificationRoutes()
            bannerRoutes()
            healthArticleRoutes()
            uploadRoutes()
            adminRoutes()
        }
    }
}
