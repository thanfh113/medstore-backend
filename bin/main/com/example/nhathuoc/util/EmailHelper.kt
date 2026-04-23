package com.example.nhathuoc.util

object EmailHelper {
    private val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun normalize(email: String?): String? {
        return email?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    }

    fun isValid(email: String): Boolean = emailRegex.matches(email)
}
