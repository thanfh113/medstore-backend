package com.example.nhathuoc.util

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import java.util.Date

object JwtHelper {
    private lateinit var secret: String
    private lateinit var issuer: String
    private lateinit var audience: String
    private var accessExpiry: Long = 900_000L
    private var refreshExpiry: Long = 604_800_000L

    fun init(application: Application) {
        val config = application.environment.config
        secret       = config.property("jwt.secret").getString()
        issuer       = config.property("jwt.issuer").getString()
        audience     = config.property("jwt.audience").getString()
        accessExpiry = config.property("jwt.access_token_expiry").getString().toLong()
        refreshExpiry = config.property("jwt.refresh_token_expiry").getString().toLong()
    }

    fun generateAccessToken(userId: String, role: String): String {
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("userId", userId)
            .withClaim("role", role)
            .withExpiresAt(Date(System.currentTimeMillis() + accessExpiry))
            .sign(Algorithm.HMAC256(secret))
    }

    fun generateRefreshToken(userId: String): String {
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("userId", userId)
            .withClaim("type", "refresh")
            .withExpiresAt(Date(System.currentTimeMillis() + refreshExpiry))
            .sign(Algorithm.HMAC256(secret))
    }
}
