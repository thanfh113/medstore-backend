package com.example.nhathuoc

import io.ktor.server.application.*
import io.ktor.server.netty.*
import com.example.nhathuoc.plugins.*
import com.example.nhathuoc.service.ComplaintService
import com.example.nhathuoc.service.FcmService
import com.example.nhathuoc.util.CloudinaryHelper
import com.example.nhathuoc.util.Env
import com.example.nhathuoc.util.JwtHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun main(args: Array<String>) = EngineMain.main(args)

fun Application.module() {
    Env.init()
    JwtHelper.init(this)
    CloudinaryHelper.init(this)
    FcmService.init()      // init Cloudinary
    configureMonitoring()
    configureSerialization()
    configureDatabase()
    configureAuthentication()
    configureCORS()
    configureStatusPages()
    configureWebSockets()
    configureRateLimit()
    configureRouting()
    startZaloPayRefundSyncJob()
}

private fun Application.startZaloPayRefundSyncJob() {
    val complaintService = ComplaintService()
    launch {
        // Initial delay so DB is ready before first sync
        delay(60_000L)
        while (isActive) {
            try {
                complaintService.syncZaloPayRefundStatuses()
            } catch (e: Exception) {
                println("WARN: ZaloPay refund sync job failed: ${e.message}")
            }
            delay(60_000L) // poll every 1 minute
        }
    }
}
