package com.example.nhathuoc.util

import com.cloudinary.Cloudinary
import io.ktor.server.application.*

/**
 * Helper upload/delete media lên Cloudinary.
 *
 * Folder structure trên Cloudinary:
 *   medstore/product_images/   ← ảnh sản phẩm
 *   medstore/product_videos/   ← video sản phẩm (< 10s)
 *   medstore/certificates/     ← giấy tờ chứng nhận (CE, ISO, FDA)
 *   medstore/prescriptions/    ← đơn thuốc scan
 *   medstore/avatars/          ← ảnh đại diện user
 *   medstore/banners/          ← banner quảng cáo
 *   medstore/supplier_logos/   ← logo nhà cung cấp vật tư y tế
 */
object CloudinaryHelper {

    private lateinit var cloudinary: Cloudinary
    private lateinit var folderPrefix: String

    // Các loại upload hợp lệ
    enum class UploadType(val folder: String, val resourceType: String, val maxBytes: Long) {
        PRODUCT_IMAGE  ("product_images",  "image", 5 * 1024 * 1024),   // 5 MB
        PRODUCT_VIDEO  ("product_videos",  "video", 20 * 1024 * 1024),  // 20 MB (< 10s)
        CERTIFICATE    ("certificates",    "image", 10 * 1024 * 1024),  // 10 MB (ảnh/PDF scan)
        PRESCRIPTION   ("prescriptions",   "image", 10 * 1024 * 1024),  // 10 MB (đơn thuốc scan)
        AVATAR         ("avatars",         "image", 2 * 1024 * 1024),   // 2 MB
        BANNER         ("banners",         "image", 5 * 1024 * 1024),   // 5 MB
        SUPPLIER_LOGO  ("supplier_logos",  "image", 3 * 1024 * 1024),   // 3 MB
        REWARD_PRODUCT ("reward_products", "image", 5 * 1024 * 1024),   // 5 MB
    }

    fun init(application: Application) {
        val config = application.environment.config
        val cloudName = Env.get("CLOUDINARY_CLOUD_NAME") ?: config.property("cloudinary.cloud_name").getString()
        val apiKey    = Env.get("CLOUDINARY_API_KEY") ?: config.property("cloudinary.api_key").getString()
        val apiSecret = Env.get("CLOUDINARY_API_SECRET") ?: config.property("cloudinary.api_secret").getString()
        folderPrefix  = Env.get("CLOUDINARY_FOLDER_PREFIX") ?: config.property("cloudinary.folder_prefix").getString()

        val configMap = mapOf(
            "cloud_name" to cloudName,
            "api_key" to apiKey,
            "api_secret" to apiSecret,
            "secure" to true
        )
        cloudinary = Cloudinary(configMap)

        application.log.info("Cloudinary initialized: cloud=$cloudName")
    }

    /**
     * Upload file lên Cloudinary.
     * @param bytes     Nội dung file
     * @param type      Loại upload (xác định folder + resource_type + giới hạn kích thước)
     * @param publicId  Tên file tùy chỉnh (optional) — nếu null Cloudinary tự sinh
     * @return          Secure URL của file đã upload
     */
    fun upload(
        bytes: ByteArray,
        type: UploadType,
        publicId: String? = null,
        fileExtension: String? = null
    ): UploadResult {
        if (bytes.size > type.maxBytes) {
            val maxMB = type.maxBytes / (1024 * 1024)
            throw IllegalArgumentException("File quá lớn. Tối đa ${maxMB}MB cho loại ${type.folder}")
        }

        val folder = "$folderPrefix/${type.folder}"
        val normalizedExtension = fileExtension
            ?.trim()
            ?.trimStart('.')
            ?.lowercase()
            .orEmpty()
        val resourceType = when {
            type in setOf(UploadType.CERTIFICATE, UploadType.PRESCRIPTION) && normalizedExtension == "pdf" -> "raw"
            else -> type.resourceType
        }

        val params = mutableMapOf<String, Any>(
            "folder" to folder,
            "resource_type" to resourceType,
            "overwrite" to true
        )
        if (resourceType == "raw" && normalizedExtension.isNotBlank()) {
            params["format"] = normalizedExtension
        }
        if (publicId != null) params["public_id"] = publicId

        @Suppress("UNCHECKED_CAST")
        val result = cloudinary.uploader().upload(bytes, params) as Map<String, Any>

        return UploadResult(
            url       = result["secure_url"] as String,
            publicId  = result["public_id"] as String,
            format    = result["format"] as? String ?: normalizedExtension,
            resourceType = resourceType,
            bytes     = (result["bytes"] as? Int) ?: bytes.size,
            width     = result["width"] as? Int,
            height    = result["height"] as? Int,
            duration  = result["duration"] as? Double   // seconds (video only)
        )
    }

    /**
     * Xóa file khỏi Cloudinary theo publicId.
     */
    fun delete(publicId: String, resourceType: String = "image") {
        val params = mapOf("resource_type" to resourceType)
        cloudinary.uploader().destroy(publicId, params)
    }

    fun signedDeliveryUrl(fileUrl: String): String {
        return signedDeliveryUrl(fileUrl, null)
    }

    fun signedDeliveryUrl(fileUrl: String, preferredResourceType: String?): String {
        if (fileUrl.isBlank()) return fileUrl

        val resourceType = preferredResourceType?.takeIf { it.isNotBlank() } ?: when {
            fileUrl.contains("/raw/upload/") -> "raw"
            fileUrl.contains("/video/upload/") -> "video"
            else -> "image"
        }
        val marker = Regex("/(image|raw|video)/upload/")
        val match = marker.find(fileUrl) ?: return fileUrl
        val sourceResourceType = match.groupValues[1]
        val sourceMarker = "/$sourceResourceType/upload/"
        val afterUpload = fileUrl.substringAfter(sourceMarker, missingDelimiterValue = "")
        if (afterUpload.isBlank()) return fileUrl
        val deliveryResourceType =
            if (
                resourceType == "raw" &&
                sourceResourceType == "image" &&
                fileUrl.substringBefore("?").lowercase().endsWith(".pdf")
            ) {
                // Old PDF certificates were uploaded under image/upload before raw PDF support.
                sourceResourceType
            } else {
                resourceType
            }

        val publicId = afterUpload
            .substringBefore("?")
            .split("/")
            .dropWhile { it.matches(Regex("[a-z_]+:.+")) }
            .dropWhile { it.startsWith("s--") }
            .dropWhile { it.matches(Regex("v\\d+")) }
            .joinToString("/")
            .ifBlank { return fileUrl }

        return runCatching {
            cloudinary.url()
                .secure(true)
                .resourceType(deliveryResourceType)
                .signed(true)
                .generate(publicId)
        }.getOrElse { fileUrl }
    }

    data class UploadResult(
        val url: String,
        val publicId: String,
        val format: String,
        val resourceType: String,
        val bytes: Int,
        val width: Int?,
        val height: Int?,
        val duration: Double?   // null nếu không phải video
    )
}
