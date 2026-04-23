package com.example.nhathuoc.routes

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * DEPRECATED - Legacy Route Functions
 * All active routes have been moved to individual route files
 */

@Deprecated("Use enhanced productRoutes() instead")
fun Route.productRoutesLegacy() {
    route("/products") {
        get { call.respond(mapOf("message" to "TODO: danh sách sản phẩm", "data" to emptyList<Any>())) }
        get("/flash-sale") { call.respond(mapOf("message" to "TODO: flash sale products")) }
        get("/best-sellers") { call.respond(mapOf("message" to "TODO: best seller products")) }
        get("/{id}") {
            val id = call.parameters["id"]
            call.respond(mapOf("message" to "TODO: chi tiết sản phẩm $id"))
        }
        get("/{id}/certificates") {
            val id = call.parameters["id"]
            call.respond(mapOf("message" to "TODO: danh sách giấy tờ công bố sản phẩm $id", "data" to emptyList<Any>()))
        }
    }
}

@Deprecated("Use enhanced categoryRoutes() instead")
fun Route.categoryRoutesLegacy() {
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

@Deprecated("Use orderFulfillmentRoutes() for enhanced order processing instead")
fun Route.orderRoutesLegacy() {
    authenticate("auth-jwt") {
        route("/orders") {
            post { call.respond(HttpStatusCode.Created, mapOf("message" to "TODO: đặt hàng")) }
            get { call.respond(mapOf("message" to "TODO: lịch sử đơn hàng")) }
            get("/{id}") { call.respond(mapOf("message" to "TODO: chi tiết đơn")) }
            post("/{id}/cancel") { call.respond(mapOf("message" to "TODO: huỷ đơn")) }
            post("/{id}/reorder") { call.respond(mapOf("message" to "TODO: mua lại")) }
            put("/{id}/status") { call.respond(mapOf("message" to "TODO: cập nhật trạng thái")) }
        }
    }
}
