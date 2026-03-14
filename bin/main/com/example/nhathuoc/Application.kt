package com.example.nhathuoc

import io.ktor.server.application.*
import io.ktor.server.netty.*
import com.example.nhathuoc.plugins.*
import com.example.nhathuoc.util.JwtHelper

fun main(args: Array<String>) = EngineMain.main(args)

fun Application.module() {
    JwtHelper.init(this)             // init JWT sebelum dipakai di routes
    configureSerialization()
    configureDatabase()
    configureAuthentication()
    configureCORS()
    configureStatusPages()
    configureWebSockets()
    configureRateLimit()
    configureRouting()
}
