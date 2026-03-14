package com.example.nhathuoc.util

import com.cloudinary.Cloudinary
import com.cloudinary.util.ObjectUtils
import io.ktor.server.application.*

/**
 * Helper upload/delete media lên Cloudinary.
 *
 * Folder structure trên Cloudinary:
 *   nhathuoc/product_images/   ← ảnh sản phẩm
 *   nhathuoc/product_videos/   ← video sản phẩm (< 10s)
 *   nhathuoc/certificates/     ← giấy tờ công bố
 *   nhathuoc/prescriptions/    ← đơn thuốc kê (private)
 *   nhathuoc/avatars/          ← ảnh đại diện user
 *   nhathuoc/banners/          ← banner quảng cáo
 *   nhathuoc/shop_logos/       ← logo nhà thuốc
 */
object CloudinaryHelper {

    private lateinit var cloudinary: Cloudinary
    private lateinit var folderPrefix: String

    // Các loại upload hợp lệ
    enum class UploadType(val folder: String, val resourceType: String, val maxBytes: Long) {
        PRODUCT_IMAGE  ("product_images",  "image", 5 * 1024 * 1024),   // 5 MB
        PRODUCT_VIDEO  ("product_videos",  "video", 20 * 1024 * 1024),  // 20 MB (< 10s)
        CERTIFICATE    ("certificates",    "image", 10 * 1024 * 1024),  // 10 MB (ảnh/PDF scan)
        PRESCRIPTION   ("prescriptions",   "image", 10 * 1024 * 1024),  // 10 MB
        AVATAR         ("avatars",         "image", 2 * 1024 * 1024),   // 2 MB
        BANNER         ("banners",         "image", 5 * 1024 * 1024),   // 5 MB
        SHOP_LOGO      ("shop_logos",      "image", 3 * 1024 * 1024),   // 3 MB
        REWARD_PRODUCT ("reward_products", "image", 5 * 1024 * 1024),   // 5 MB
    }

    fun init(application: Application) {
        val config = application.environment.config
        val cloudName = config.property("cloudinary.cloud_name").getString()
        val apiKey    = config.property("cloudinary.api_key").getString()
        val apiSecret = config.property("cloudinary.api_secret").getString()
        folderPrefix  = config.property("cloudinary.folder_prefix").getString()

        cloudinary = Cloudinary(
            ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key",    apiKey,
                "api_secret", apiSecret,
                "secure",     true
            )
        )

        application.log.info("Cloudinary initialized: cloud=$cloudName")
    }

    /**
     * Upload file lên Cloudinary.
     * @param bytes     Nội dung file
     * @param type      Loại upload (xác định folder + resource_type + giới hạn kích thước)
     * @param publicId  Tên file tùy chỉnh (optional) — nếu null Cloudinary tự sinh
     * @return          Secure URL của file đã upload
     */
    fun upload(bytes: ByteArray, type: UploadType, publicId: String? = null): UploadResult {
        if (bytes.size > type.maxBytes) {
            val maxMB = type.maxBytes / (1024 * 1024)
            throw IllegalArgumentException("File quá lớn. Tối đa ${maxMB}MB cho loại ${type.folder}")
        }

        val folder = "$folderPrefix/${type.folder}"

        val params = mutableMapOf<String, Any>(
            "folder",        folder,
            "resource_type", type.resourceType,
            "overwrite",     true
        )
        if (publicId != null) params["public_id"] = publicId

        @Suppress("UNCHECKED_CAST")
        val result = cloudinary.uploader().upload(bytes, params) as Map<String, Any>

        return UploadResult(
            url       = result["secure_url"] as String,
            publicId  = result["public_id"] as String,
            format    = result["format"] as? String ?: "",
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
        cloudinary.uploader().destroy(
            publicId,
            ObjectUtils.asMap("resource_type", resourceType)
        )
    }

    data class UploadResult(
        val url: String,
        val publicId: String,
        val format: String,
        val bytes: Int,
        val width: Int?,
        val height: Int?,
        val duration: Double?   // null nếu không phải video
    )
}
