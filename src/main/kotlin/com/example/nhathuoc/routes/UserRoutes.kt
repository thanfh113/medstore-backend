package com.example.nhathuoc.routes

import com.example.nhathuoc.service.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class AddressIdResponse(
    val addressId: String
)

// ─── User profile & addresses ─────────────────────────────────
fun Route.userRoutes() {
    val userService = UserService()

    authenticate("auth-jwt") {
        route("/users") {
            // GET /api/v1/users/me - Get user profile
            get("/me") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val userProfile = userService.getUserProfile(userId)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "User not found")
                        )

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = userProfile,
                            message = "User profile retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get user profile: ${e.message}")
                    )
                }
            }

            // PUT /api/v1/users/me - Update user profile
            put("/me") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@put call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val request = call.receive<UpdateUserProfileRequest>()
                    userService.updateUserProfile(userId, request)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "User profile updated successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update user profile: ${e.message}")
                    )
                }
            }

            // GET /api/v1/users/me/addresses - Get user addresses
            get("/me/addresses") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val addresses = userService.getUserAddresses(userId)
                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = addresses,
                            message = "Addresses retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get addresses: ${e.message}")
                    )
                }
            }

            // POST /api/v1/users/me/addresses - Create new address
            post("/me/addresses") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val request = call.receive<CreateAddressRequest>()
                    val addressId = userService.createAddress(userId, request)

                    call.respond(
                        HttpStatusCode.Created,
                        RouteDataMessageResponse(
                            data = AddressIdResponse(addressId),
                            message = "Address created successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to create address: ${e.message}")
                    )
                }
            }

            // GET /api/v1/users/me/addresses/{id} - Get specific address
            get("/me/addresses/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val addressId = call.parameters["id"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Address ID is required")
                        )

                    val address = userService.getAddressById(userId, addressId)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Address not found")
                        )

                    call.respond(
                        HttpStatusCode.OK,
                        RouteDataMessageResponse(
                            data = address,
                            message = "Address retrieved successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get address: ${e.message}")
                    )
                }
            }

            // PUT /api/v1/users/me/addresses/{id} - Update address
            put("/me/addresses/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@put call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val addressId = call.parameters["id"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Address ID is required")
                        )

                    val request = call.receive<UpdateAddressRequest>()
                    userService.updateAddress(userId, addressId, request)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Address updated successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update address: ${e.message}")
                    )
                }
            }

            // DELETE /api/v1/users/me/addresses/{id} - Delete address
            delete("/me/addresses/{id}") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@delete call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val addressId = call.parameters["id"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Address ID is required")
                        )

                    userService.deleteAddress(userId, addressId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Address deleted successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to delete address: ${e.message}")
                    )
                }
            }

            // POST /api/v1/users/me/addresses/{id}/default - Set default address
            post("/me/addresses/{id}/default") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@post call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val addressId = call.parameters["id"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Address ID is required")
                        )

                    userService.setDefaultAddress(userId, addressId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Default address set successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to set default address: ${e.message}")
                    )
                }
            }

            // GET /api/v1/users/me/addresses/default - Get default address
            get("/me/addresses/default") {
                try {
                    val principal = call.principal<JWTPrincipal>()
                    val userId = principal?.payload?.getClaim("userId")?.asString()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "User ID not found")
                        )

                    val defaultAddress = userService.getDefaultAddress(userId)

                    if (defaultAddress != null) {
                        call.respond(
                            HttpStatusCode.OK,
                            RouteDataMessageResponse(
                                data = defaultAddress,
                                message = "Default address retrieved successfully"
                            )
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "No default address found")
                        )
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get default address: ${e.message}")
                    )
                }
            }
        }
    }
}
