package com.example.nhathuoc.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import org.slf4j.event.Level

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            val path = call.request.path()
            path == "/api/v1/auth/login" ||
                path == "/api/v1/internal/dashboard" ||
                path.startsWith("/api/v1/internal/orders") ||
                path == "/api/v1/internal/products" ||
                path.startsWith("/api/v1/internal/products/") ||
                path == "/api/v1/products" ||
                path.startsWith("/api/v1/products/")
        }
    }
}
