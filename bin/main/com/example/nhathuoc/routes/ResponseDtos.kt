package com.example.nhathuoc.routes

import kotlinx.serialization.Serializable

@Serializable
data class RouteDataMessageResponse<T>(
    val data: T,
    val message: String
)

@Serializable
data class RouteErrorResponse(
    val error: String
)

@Serializable
data class RoutePaginationResponse(
    val page: Int,
    val limit: Int,
    val total: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrev: Boolean
)

@Serializable
data class RoutePaginatedDataMessageResponse<T>(
    val data: List<T>,
    val pagination: RoutePaginationResponse,
    val message: String
)
