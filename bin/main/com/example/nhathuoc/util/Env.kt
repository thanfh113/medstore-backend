package com.example.nhathuoc.util

import java.io.File

object Env {
    private val dotEnv: Map<String, String> by lazy { loadDotEnv() }

    fun init() {
        dotEnv
    }

    fun get(name: String): String? {
        return System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: dotEnv[name]?.takeIf { it.isNotBlank() }
    }

    fun require(name: String): String {
        return get(name) ?: throw IllegalStateException("Missing required environment variable: $name")
    }

    private fun loadDotEnv(): Map<String, String> {
        val envFile = File(".env")
        if (!envFile.exists() || !envFile.isFile) return emptyMap()

        return envFile.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex <= 0) return@mapNotNull null

                val key = line.substring(0, separatorIndex).trim()
                if (key.isEmpty()) return@mapNotNull null

                val rawValue = line.substring(separatorIndex + 1).trim()
                key to stripQuotes(rawValue)
            }
            .toMap()
    }

    private fun stripQuotes(value: String): String {
        if (value.length < 2) return value

        val singleQuoted = value.startsWith('\'') && value.endsWith('\'')
        val doubleQuoted = value.startsWith('"') && value.endsWith('"')

        return if (singleQuoted || doubleQuoted) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }
}
