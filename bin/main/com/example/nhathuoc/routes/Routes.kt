package com.example.nhathuoc.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// ─── User profile & addresses ─────────────────────────────────
fun Route.userRoutes() {
    authenticate("auth-jwt") {
        route("/users") {
            get("/me") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asString()
                call.respond(mapOf("userId" to userId, "message" to "TODO: trả về user profile"))
            }
            put("/me") {
                call.respond(HttpStatusCode.OK, mapOf("message" to "TODO: cập nhật profile"))
            }
            get("/me/addresses") {
                call.respond(mapOf("message" to "TODO: danh sách địa chỉ"))
            }
            post("/me/addresses") {
                call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: thêm địa chỉ"))
            }
            put("/me/addresses/{id}") {
                call.respond(mapOf("message" to "TODO: sửa địa chỉ"))
            }
            delete("/me/addresses/{id}") {
                call.respond(mapOf("message" to "TODO: xoá địa chỉ"))
            }
        }
    }
}

// ─── Products ─────────────────────────────────────────────────
fun Route.productRoutes() {
    route("/products") {
        get {
            // ?category=&search=&page=&size=&sort=
            call.respond(mapOf("message" to "TODO: danh sách sản phẩm", "data" to emptyList<Any>()))
        }
        get("/flash-sale") {
            call.respond(mapOf("message" to "TODO: flash sale products"))
        }
        get("/best-sellers") {
            call.respond(mapOf("message" to "TODO: best seller products"))
        }
        get("/{id}") {
            val id = call.parameters["id"]
            call.respond(mapOf("message" to "TODO: chi tiết sản phẩm $id"))
        }

        // Shop only routes
        authenticate("auth-jwt") {
            post {
                call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: tạo sản phẩm (SHOP)"))
            }
            put("/{id}") {
                call.respond(mapOf("message" to "TODO: sửa sản phẩm (SHOP)"))
            }
            delete("/{id}") {
                call.respond(mapOf("message" to "TODO: xoá sản phẩm (SHOP)"))
            }
        }
    }
}

// ─── Categories ───────────────────────────────────────────────
fun Route.categoryRoutes() {
    route("/categories") {
        get { call.respond(mapOf("message" to "TODO: danh sách danh mục")) }
        get("/{id}/products") {
            val name = call.parameters["id"]
            call.respond(mapOf("message" to "TODO: sản phẩm theo danh mục $name"))
        }
    }
    route("/diseases") {
        get { call.respond(mapOf("message" to "TODO: danh sách bệnh lý")) }
        get("/{id}/products") {
            call.respond(mapOf("message" to "TODO: sản phẩm theo bệnh lý"))
        }
    }
}

// ─── Cart ─────────────────────────────────────────────────────
fun Route.cartRoutes() {
    authenticate("auth-jwt") {
        route("/cart") {
            get { call.respond(mapOf("message" to "TODO: giỏ hàng của user")) }
            post("/items") { call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: thêm vào giỏ hàng")) }
            put("/items/{id}") { call.respond(mapOf("message" to "TODO: cập nhật số lượng")) }
            delete("/items/{id}") { call.respond(mapOf("message" to "TODO: xoá khỏi giỏ hàng")) }
        }
    }
}

// ─── Orders ───────────────────────────────────────────────────
fun Route.orderRoutes() {
    authenticate("auth-jwt") {
        route("/orders") {
            post { call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: đặt hàng")) }
            get { call.respond(mapOf("message" to "TODO: lịch sử đơn hàng")) }
            get("/{id}") {
                val id = call.parameters["id"]
                call.respond(mapOf("message" to "TODO: chi tiết đơn $id"))
            }
            post("/{id}/cancel") { call.respond(mapOf("message" to "TODO: huỷ đơn")) }
            post("/{id}/reorder") { call.respond(mapOf("message" to "TODO: mua lại")) }
            post("/prescription") { call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: upload đơn thuốc")) }

            // Shop: cập nhật trạng thái đơn
            put("/{id}/status") { call.respond(mapOf("message" to "TODO: cập nhật trạng thái (SHOP)")) }
        }
    }
}

// ─── Rewards ──────────────────────────────────────────────────
fun Route.rewardRoutes() {
    route("/rewards") {
        get("/products") { call.respond(mapOf("message" to "TODO: sản phẩm đổi điểm")) }
        authenticate("auth-jwt") {
            get { call.respond(mapOf("message" to "TODO: điểm thưởng + lịch sử")) }
            post("/redeem") { call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: đổi quà")) }
        }
    }
}

// ─── Vaccines ─────────────────────────────────────────────────
fun Route.vaccineRoutes() {
    route("/vaccines") {
        get { call.respond(mapOf("message" to "TODO: danh sách vắc-xin")) }
        authenticate("auth-jwt") {
            post("/bookings") { call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: đặt lịch tiêm")) }
            get("/bookings/me") { call.respond(mapOf("message" to "TODO: lịch tiêm của tôi")) }
        }
    }
}

// ─── Chat ─────────────────────────────────────────────────────
fun Route.chatRoutes() {
    authenticate("auth-jwt") {
        route("/chat") {
            get("/sessions") { call.respond(mapOf("message" to "TODO: danh sách session chat")) }
            post("/sessions") { call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: tạo session")) }
            get("/sessions/{id}/messages") { call.respond(mapOf("message" to "TODO: lịch sử chat")) }
            post("/sessions/{id}/messages") { call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: gửi tin nhắn")) }
        }
        // WebSocket
        webSocket("/ws/chat/{sessionId}") {
            // TODO: implement WebSocket chat handler
        }
    }
}

// ─── Pharmacies ───────────────────────────────────────────────
fun Route.pharmacyRoutes() {
    route("/pharmacies") {
        // ?lat=&lng=&radius=&search=
        get { call.respond(mapOf("message" to "TODO: tìm nhà thuốc gần nhất")) }
        get("/{id}") {
            val id = call.parameters["id"]
            call.respond(mapOf("message" to "TODO: chi tiết nhà thuốc $id"))
        }
    }
}

// ─── Notifications ────────────────────────────────────────────
fun Route.notificationRoutes() {
    authenticate("auth-jwt") {
        route("/notifications") {
            get { call.respond(mapOf("message" to "TODO: danh sách thông báo")) }
            put("/{id}/read") { call.respond(mapOf("message" to "TODO: đánh dấu đã đọc")) }
            put("/read-all") { call.respond(mapOf("message" to "TODO: đọc tất cả")) }
        }
    }
}

// ─── Banners ──────────────────────────────────────────────────
fun Route.bannerRoutes() {
    route("/banners") {
        get { call.respond(mapOf("message" to "TODO: danh sách banner")) }
    }
}

// ─── Health Articles ──────────────────────────────────────────
fun Route.healthArticleRoutes() {
    route("/health-articles") {
        get { call.respond(mapOf("message" to "TODO: danh sách tin tức sức khoẻ")) }
        get("/{id}") { call.respond(mapOf("message" to "TODO: chi tiết bài viết")) }
    }
}

// ─── Admin ────────────────────────────────────────────────────
fun Route.adminRoutes() {
    authenticate("auth-jwt") {
        route("/admin") {
            // Dashboard
            get("/dashboard") { call.respond(mapOf("message" to "TODO: thống kê tổng quan")) }
            // Finance
            get("/finance") { call.respond(mapOf("message" to "TODO: doanh thu tài chính")) }
            get("/finance/export") { call.respond(mapOf("message" to "TODO: xuất báo cáo")) }
            // Users
            get("/users") { call.respond(mapOf("message" to "TODO: danh sách người dùng")) }
            put("/users/{id}/ban") { call.respond(mapOf("message" to "TODO: khoá tài khoản")) }
            // Shops
            get("/shops") { call.respond(mapOf("message" to "TODO: danh sách nhà thuốc")) }
            put("/shops/{id}/approve") { call.respond(mapOf("message" to "TODO: duyệt nhà thuốc")) }
            put("/shops/{id}/reject") { call.respond(mapOf("message" to "TODO: từ chối nhà thuốc")) }
            // All orders
            get("/orders") { call.respond(mapOf("message" to "TODO: tất cả đơn hàng")) }
            // Banners
            post("/banners") { call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: thêm banner")) }
            put("/banners/{id}") { call.respond(mapOf("message" to "TODO: sửa banner")) }
            delete("/banners/{id}") { call.respond(mapOf("message" to "TODO: xoá banner")) }
            // Rewards config
            get("/rewards/config") { call.respond(mapOf("message" to "TODO: cấu hình điểm thưởng")) }
            put("/rewards/config") { call.respond(mapOf("message" to "TODO: cập nhật cấu hình")) }
        }
    }
}
