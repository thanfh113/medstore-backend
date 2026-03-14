package com.example.nhathuoc.routes

import com.example.nhathuoc.util.CloudinaryHelper
import com.example.nhathuoc.util.CloudinaryHelper.UploadType
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class UploadResponse(
    val url: String,
    val publicId: String,
    val format: String,
    val bytes: Int,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Double? = null   // video duration in seconds
)

/**
 * POST /api/v1/upload?type=PRODUCT_IMAGE
 *
 * types: PRODUCT_IMAGE | PRODUCT_VIDEO | CERTIFICATE | PRESCRIPTION
 *        | AVATAR | BANNER | SHOP_LOGO | REWARD_PRODUCT
 *
 * Body: multipart/form-data
 *   file: <binary>
 *
 * Response: { url, publicId, format, bytes, width?, height?, duration? }
 */
fun Route.uploadRoutes() {
    authenticate("auth-jwt") {
        route("/upload") {

            post {
                // Lấy query param ?type=...
                val typeParam = call.request.queryParameters["type"]?.uppercase()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Thiếu query param 'type'. Giá trị hợp lệ: ${UploadType.entries.map { it.name }}")
                    )

                val uploadType = try {
                    UploadType.valueOf(typeParam)
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Loại upload không hợp lệ: $typeParam. Giá trị hợp lệ: ${UploadType.entries.map { it.name }}")
                    )
                }

                // Đọc multipart
                val multipart = call.receiveMultipart()
                var fileBytes: ByteArray? = null
                var originalFileName: String? = null

                multipart.forEachPart { part ->
                    if (part is PartData.FileItem && part.name == "file") {
                        originalFileName = part.originalFileName
                        fileBytes = part.streamProvider().readBytes()
                    }
                    part.dispose()
                }

                if (fileBytes == null) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Không tìm thấy file trong request. Key phải là 'file'")
                    )
                }

                // Kiểm tra loại file hợp lệ
                val allowedExtensions = when (uploadType) {
                    UploadType.PRODUCT_VIDEO -> setOf("mp4", "mov", "avi", "webm")
                    UploadType.CERTIFICATE, UploadType.PRESCRIPTION ->
                        setOf("jpg", "jpeg", "png", "pdf", "heic")
                    else -> setOf("jpg", "jpeg", "png", "webp", "heic")
                }
                val ext = originalFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
                if (ext.isNotBlank() && ext !in allowedExtensions) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Định dạng file không hỗ trợ: .$ext. Chấp nhận: $allowedExtensions")
                    )
                }

                // Upload lên Cloudinary
                val result = try {
                    CloudinaryHelper.upload(fileBytes!!, uploadType)
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.application.log.error("Cloudinary upload error", e)
                    return@post call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Upload thất bại: ${e.message}")
                    )
                }

                call.respond(
                    HttpStatusCode.Created,
                    UploadResponse(
                        url      = result.url,
                        publicId = result.publicId,
                        format   = result.format,
                        bytes    = result.bytes,
                        width    = result.width,
                        height   = result.height,
                        duration = result.duration
                    )
                )
            }

            // DELETE /api/v1/upload?publicId=nhathuoc/product_images/abc123&resourceType=image
            delete {
                val publicId     = call.request.queryParameters["publicId"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Thiếu publicId"))
                val resourceType = call.request.queryParameters["resourceType"] ?: "image"

                try {
                    CloudinaryHelper.delete(publicId, resourceType)
                    call.respond(mapOf("message" to "Đã xóa file: $publicId"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }
        }
    }
}
