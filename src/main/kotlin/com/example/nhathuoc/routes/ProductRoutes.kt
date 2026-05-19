package com.example.nhathuoc.routes

import com.example.nhathuoc.database.tables.CategoriesTable
import com.example.nhathuoc.database.tables.OrderItemsTable
import com.example.nhathuoc.database.tables.OrdersTable
import com.example.nhathuoc.service.*
import com.example.nhathuoc.util.AppRoles
import com.example.nhathuoc.util.CloudinaryHelper
import com.example.nhathuoc.util.getRole
import com.example.nhathuoc.util.getUserId
import com.example.nhathuoc.util.requireInternalAccess
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer

@Serializable
private data class ProductImagePayload(
    val url: String,
    val mediaType: String = "IMAGE",
    val publicId: String? = null,
    val sortOrder: Int = 0
)

@Serializable
private data class ProductCertificatePayload(
    val type: String = "MOH_LICENSE",
    val name: String,
    val fileUrl: String,
    val fileType: String = "IMAGE",
    val publicId: String? = null,
    val resourceType: String = "image",
    val thumbnailUrl: String? = null,
    val issuer: String? = null,
    val isActive: Boolean = true
)

@Serializable
private data class CreateProductPayload(
    val categoryId: String,
    val name: String,
    val shortDescription: String? = null,
    val description: String? = null,
    val brand: String? = null,
    val manufacturer: String? = null,
    val origin: String? = null,
    val sku: String? = null,
    val unit: String = "Cai",
    val price: Double,
    val originalPrice: Double? = null,
    val importPrice: Double? = null,
    val stock: Int = 0,
    val inventoryNote: String? = null,
    val mfgDate: String? = null,
    val expDate: String? = null,
    val discountPct: Int = 0,
    val rewardPoints: Int = 0,
    val productType: String = "MEDICAL_SUPPLY",
    val registrationNumber: String? = null,
    val riskClassification: String = "A",
    val requiresCertification: Boolean = false,
    val requiresConsultation: Boolean = false,
    val isActive: Boolean = true,
    val attributes: Map<String, String> = emptyMap(),
    val images: List<ProductImagePayload> = emptyList(),
    val certificates: List<ProductCertificatePayload> = emptyList()
)

@Serializable
private data class UpdateProductPayload(
    val categoryId: String? = null,
    val name: String? = null,
    val shortDescription: String? = null,
    val description: String? = null,
    val brand: String? = null,
    val manufacturer: String? = null,
    val origin: String? = null,
    val sku: String? = null,
    val unit: String? = null,
    val price: Double? = null,
    val originalPrice: Double? = null,
    val importPrice: Double? = null,
    val stock: Int? = null,
    val inventoryNote: String? = null,
    val mfgDate: String? = null,
    val expDate: String? = null,
    val discountPct: Int? = null,
    val rewardPoints: Int? = null,
    val productType: String? = null,
    val registrationNumber: String? = null,
    val riskClassification: String? = null,
    val requiresCertification: Boolean? = null,
    val requiresConsultation: Boolean? = null,
    val isActive: Boolean? = null,
    val attributes: Map<String, String>? = null,
    val images: List<ProductImagePayload>? = null,
    val certificates: List<ProductCertificatePayload>? = null
)

@Serializable
private data class ProductImageResponse(
    val id: String,
    val url: String,
    val mediaType: String = "IMAGE",
    val publicId: String? = null,
    val sortOrder: Int
)

@Serializable
private data class ProductListItemResponse(
    val id: String,
    val categoryId: String? = null,
    val name: String,
    val slug: String? = null,
    val shortDescription: String? = null,
    val description: String? = null,
    val brand: String? = null,
    val manufacturer: String? = null,
    val origin: String? = null,
    val sku: String? = null,
    val unit: String,
    val price: Double,
    val originalPrice: Double? = null,
    val importPrice: Double? = null,
    val discountPct: Int,
    val rewardPoints: Int,
    val stock: Int,
    val inventoryNote: String? = null,
    val mfgDate: String? = null,
    val expDate: String? = null,
    val productType: String,
    val registrationNumber: String? = null,
    val riskClassification: String,
    val requiresCertification: Boolean,
    val requiresConsultation: Boolean,
    val isActive: Boolean,
    val isFlashSale: Boolean,
    val flashSaleEnd: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val images: List<ProductImageResponse> = emptyList(),
    val certificates: List<ProductCertificateResponse> = emptyList()
)

@Serializable
private data class ProductCertificateResponse(
    val id: String? = null,
    val type: String = "MOH_LICENSE",
    val name: String,
    val fileUrl: String,
    val fileType: String = "IMAGE",
    val publicId: String? = null,
    val resourceType: String = "image",
    val thumbnailUrl: String? = null,
    val issuer: String? = null,
    val isActive: Boolean = true
)

@Serializable
private data class ProductPaginationResponse(
    val page: Int,
    val limit: Int,
    val total: Long,
    val totalPages: Int
)

@Serializable
private data class ProductListEnvelopeResponse(
    val data: List<ProductListItemResponse>,
    val pagination: ProductPaginationResponse,
    val message: String
)

private fun ProductDto.effectiveDiscountPct(): Int {
    val original = originalPrice ?: return discountPct.coerceIn(0, 99)
    if (original <= BigDecimal.ZERO || price <= BigDecimal.ZERO || original <= price) return 0
    return original
        .subtract(price)
        .multiply(BigDecimal(100))
        .divide(original, 0, RoundingMode.HALF_UP)
        .toInt()
        .coerceIn(0, 99)
}

@Serializable
private data class ProductIdResponse(
    val id: String
)

@Serializable
private data class ProductMutationResponse(
    val data: ProductIdResponse? = null,
    val message: String
)

@Serializable
private data class ProductDeleteRequestPayload(
    val reason: String? = null
)

private fun Any?.toJsonElementSafe(): JsonElement {
    return when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Map<*, *> -> buildJsonObject {
            this@toJsonElementSafe.forEach { (key, value) ->
                if (key is String) {
                    put(key, value.toJsonElementSafe())
                }
            }
        }
        is Iterable<*> -> buildJsonArray {
            this@toJsonElementSafe.forEach { item ->
                add(item.toJsonElementSafe())
            }
        }
        else -> JsonPrimitive(toString())
    }
}

private fun ProductDto.toDetailJson(): JsonObject {
    return buildJsonObject {
        put("id", id)
        put("categoryId", categoryId?.let(::JsonPrimitive) ?: JsonNull)
        put("name", name)
        put("slug", slug?.let(::JsonPrimitive) ?: JsonNull)
        put("shortDescription", shortDescription?.let(::JsonPrimitive) ?: JsonNull)
        put("description", description?.let(::JsonPrimitive) ?: JsonNull)
        put("brand", brand?.let(::JsonPrimitive) ?: JsonNull)
        put("manufacturer", manufacturer?.let(::JsonPrimitive) ?: JsonNull)
        put("origin", origin?.let(::JsonPrimitive) ?: JsonNull)
        put("sku", sku?.let(::JsonPrimitive) ?: JsonNull)
        put("unit", unit)
        put("price", JsonPrimitive(price.toDouble()))
        put("originalPrice", originalPrice?.let { JsonPrimitive(it.toDouble()) } ?: JsonNull)
        put("discountPct", effectiveDiscountPct())
        put("rewardPoints", rewardPoints)
        put("stock", stock)
        put("productType", productType)
        put("registrationNumber", registrationNumber?.let(::JsonPrimitive) ?: JsonNull)
        put("riskClassification", riskClassification)
        put("requiresCertification", requiresCertification)
        put("requiresConsultation", requiresConsultation)
        put("isActive", isActive)
        put("isFlashSale", isFlashSale)
        put("flashSaleEnd", flashSaleEnd?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("createdAt", createdAt.toString())
        put("updatedAt", updatedAt.toString())
        put("attributes", attributes.toJsonElementSafe())
        putJsonArray("images") {
            images.sortedBy { it.sortOrder }.forEach { image ->
                addJsonObject {
                    put("id", image.id)
                    put("url", image.url)
                    put("mediaType", image.mediaType)
                    put("publicId", image.publicId?.let(::JsonPrimitive) ?: JsonNull)
                    put("sortOrder", image.sortOrder)
                }
            }
        }
        putJsonArray("certificates") {
            certificates.forEach { certificate ->
                addJsonObject {
                    put("id", certificate.id)
                    put("type", certificate.type)
                    put("name", certificate.name)
                    put("fileUrl", certificate.fileUrl)
                    put("fileType", certificate.fileType)
                    put("publicId", certificate.publicId?.let(::JsonPrimitive) ?: JsonNull)
                    put("resourceType", certificate.resourceType)
                    put("thumbnailUrl", certificate.thumbnailUrl?.let(::JsonPrimitive) ?: JsonNull)
                    put("issuer", certificate.issuer?.let(::JsonPrimitive) ?: JsonNull)
                    put("isActive", certificate.isActive)
                }
            }
        }
    }
}

private fun ProductDto.attributesAsStringJson(): JsonObject {
    return buildJsonObject {
        attributes.forEach { (key, value) ->
            put(key, value?.toString() ?: "")
        }
    }
}

private fun ProductDto.toAndroidCategoryJson(): JsonObject {
    val now = createdAt.toString()
    val categoryKey = categoryId ?: "uncategorized"
    return buildJsonObject {
        put("id", categoryKey)
        put("parentId", JsonNull)
        put("name", "Vật tư y tế")
        put("slug", categoryKey)
        put("description", JsonNull)
        put("productTypeDefault", productType)
        put("iconUrl", JsonNull)
        put("sortOrder", 0)
        put("isActive", true)
        put("createdAt", now)
        put("updatedAt", updatedAt.toString())
        put("deletedAt", JsonNull)
    }
}

private fun ProductDto.toAndroidProductJson(): JsonObject {
    val firstImageUrl = images.sortedBy { it.sortOrder }.firstOrNull()?.url
    val effectiveBrand = brand ?: manufacturer ?: "Đang cập nhật"
    val effectiveOrigin = origin ?: "Đang cập nhật"
    return buildJsonObject {
        put("id", id)
        put("name", name)
        put("slug", slug?.let(::JsonPrimitive) ?: JsonNull)
        put("brand", effectiveBrand)
        put("origin", effectiveOrigin)
        put("price", JsonPrimitive(price.toDouble()))
        put("originalPrice", originalPrice?.let { JsonPrimitive(it.toDouble()) } ?: JsonNull)
        put("discountPct", effectiveDiscountPct())
        put("unit", unit)
        put("sku", sku?.let(::JsonPrimitive) ?: JsonNull)
        put("description", description ?: shortDescription ?: "")
        put("ingredients", JsonNull)
        put("indication", JsonNull)
        put("contraindication", JsonNull)
        put("sideEffects", JsonNull)
        put("dosage", JsonNull)
        put("storage", JsonNull)
        put("rewardPoints", rewardPoints)
        put("icon", JsonNull)
        put("iconTint", JsonNull)
        put("iconBg", JsonNull)
        put("imageUrl", firstImageUrl?.let(::JsonPrimitive) ?: JsonNull)
        put("category", toAndroidCategoryJson())
        put("attributes", attributesAsStringJson())
        put("productType", productType)
        put("registrationNumber", registrationNumber?.let(::JsonPrimitive) ?: JsonNull)
        put("riskClassification", riskClassification)
        put("requiresCertification", requiresCertification)
        put("isPrescription", requiresCertification)
        put("requiresConsultation", requiresConsultation)
        put("isFlashSale", isFlashSale)
        put("flashSaleEnd", flashSaleEnd?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("isBestSeller", false)
        put("stock", stock)
        put("isActive", isActive)
        put("createdAt", createdAt.toString())
        put("updatedAt", updatedAt.toString())
        put("deletedAt", JsonNull)
    }
}

private fun ProductCertificateDto.toAndroidCertificateJson(product: ProductDto): JsonObject {
    val deliveryUrl = CloudinaryHelper.signedDeliveryUrl(fileUrl, resourceType)
    return buildJsonObject {
        put("id", id)
        put("productId", product.id)
        put("name", name)
        put("issuer", issuer ?: "")
        put("certificateNumber", type)
        put("documentUrl", deliveryUrl)
        put("fileUrl", deliveryUrl)
        put("fileType", fileType)
        put("publicId", publicId?.let(::JsonPrimitive) ?: JsonNull)
        put("resourceType", resourceType)
        put("thumbnailUrl", thumbnailUrl?.let(::JsonPrimitive) ?: JsonNull)
        put("isActive", isActive)
        put("createdAt", product.createdAt.toString())
        put("updatedAt", product.updatedAt.toString())
    }
}

private val categoryDiacriticRegex = "\\p{Mn}+".toRegex()
private val categoryNonAlphaNumericRegex = "[^a-z0-9]+".toRegex()

private fun categoryKey(value: String): String {
    val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(categoryDiacriticRegex, "")
        .replace('đ', 'd')
        .replace('Đ', 'd')

    return normalized
        .lowercase()
        .replace(categoryNonAlphaNumericRegex, " ")
        .trim()
}

private val publicCategoryAliases = mapOf(
    categoryKey("Dụng cụ tiêm truyền") to listOf("cat-supplies", "cat-syringe", "cat-needle", "cat-infusion-set", "cat-tube"),
    categoryKey("Kim tiêm") to listOf("cat-needle", "cat-syringe"),
    categoryKey("Ống xi lanh") to listOf("cat-syringe"),
    categoryKey("Dây truyền dịch") to listOf("cat-infusion-set", "cat-tube"),
    categoryKey("Bơm tiêm") to listOf("cat-syringe"),
    categoryKey("Băng gạc - Cầm máu") to listOf("cat-bandage", "cat-sterile-gauze", "cat-medical-tape", "cat-bandage-roll", "cat-antimicrobial-dressing"),
    categoryKey("Băng dính y tế") to listOf("cat-medical-tape"),
    categoryKey("Gạc vô trùng") to listOf("cat-sterile-gauze"),
    categoryKey("Băng cuộn") to listOf("cat-bandage-roll"),
    categoryKey("Băng keo thấm tẩm kháng sinh") to listOf("cat-antimicrobial-dressing"),
    categoryKey("Thiết bị chẩn đoán") to listOf("cat-device", "cat-diagnostic", "cat-monitor", "cat-blood-pressure", "cat-thermometer", "cat-spo2", "cat-glucose-meter"),
    categoryKey("Máy đo huyết áp") to listOf("cat-blood-pressure", "cat-monitor"),
    categoryKey("Nhiệt kế y tế") to listOf("cat-thermometer"),
    categoryKey("Máy đo SpO2") to listOf("cat-spo2", "cat-monitor"),
    categoryKey("Máy đo đường huyết") to listOf("cat-glucose-meter", "cat-diagnostic"),
    categoryKey("Khẩu trang - PPE") to listOf("cat-protect", "cat-mask", "cat-n95-mask", "cat-gloves", "cat-protective-clothing", "cat-goggles"),
    categoryKey("Khẩu trang y tế") to listOf("cat-mask", "cat-protect"),
    categoryKey("Khẩu trang N95") to listOf("cat-n95-mask", "cat-protect"),
    categoryKey("Găng tay y tế") to listOf("cat-gloves", "cat-protect"),
    categoryKey("Quần áo bảo hộ") to listOf("cat-protective-clothing", "cat-protect"),
    categoryKey("Kính bảo hộ") to listOf("cat-goggles", "cat-protect"),
    categoryKey("Thiết bị phẫu thuật") to listOf("cat-instrument", "cat-surgical-tools", "cat-forceps", "cat-suture", "cat-hemostatic-valve"),
    categoryKey("Dụng cụ vi phẫu") to listOf("cat-surgical-tools", "cat-instrument"),
    categoryKey("Kẹp phẫu thuật") to listOf("cat-forceps", "cat-instrument"),
    categoryKey("Dây khâu") to listOf("cat-suture", "cat-instrument"),
    categoryKey("Van cầm máu") to listOf("cat-hemostatic-valve", "cat-instrument"),
    categoryKey("Chống nhiễm khuẩn") to listOf("cat-infection-control", "cat-sanitizer", "cat-disinfectant", "cat-sterilization"),
    categoryKey("Phục hồi chức năng") to listOf("cat-therapy", "cat-crutch-wheelchair", "cat-physio-tools", "cat-orthopedic-brace"),
    categoryKey("Nạng - Xe lăn") to listOf("cat-crutch-wheelchair", "cat-therapy"),
    categoryKey("Dụng cụ vật lý trị liệu") to listOf("cat-physio-tools", "cat-therapy"),
    categoryKey("Nẹp chỉnh hình") to listOf("cat-orthopedic-brace", "cat-therapy"),
    categoryKey("Vật tư xét nghiệm") to listOf("cat-lab", "cat-test-kit", "cat-lab-consumables", "cat-sample-container")
)

private fun resolvePublicCategoryIds(rawCategory: String?): List<String>? {
    val raw = rawCategory?.trim()?.takeIf { it.isNotBlank() } ?: return null
    publicCategoryAliases[categoryKey(raw)]?.let { return it }

    return transaction {
        data class CategoryLookup(
            val id: String,
            val parentId: String?,
            val aliases: List<String>
        )

        val categories = CategoriesTable
            .selectAll()
            .mapNotNull {
                if (!it[CategoriesTable.isActive] || it[CategoriesTable.deletedAt] != null) {
                    return@mapNotNull null
                }

                CategoryLookup(
                    it[CategoriesTable.id],
                    it[CategoriesTable.parentId],
                    listOfNotNull(it[CategoriesTable.slug], it[CategoriesTable.name]).map(::categoryKey)
                )
            }

        val rawKey = categoryKey(raw)
        val matchedIds = categories
            .filter { category ->
                category.id.equals(raw, ignoreCase = true) || rawKey in category.aliases
            }
            .map { it.id }

        if (matchedIds.isEmpty()) {
            return@transaction listOf(raw)
        }

        val result = linkedSetOf<String>()
        result.addAll(matchedIds)
        var added: Boolean
        do {
            added = false
            categories
                .filter { it.parentId in result }
                .forEach { category ->
                    if (result.add(category.id)) added = true
                }
        } while (added)
        result.toList()
    }
}

private fun ProductDto.toListItemResponse(): ProductListItemResponse {
    return ProductListItemResponse(
        id = id,
        categoryId = categoryId,
        name = name,
        slug = slug,
        shortDescription = shortDescription,
        description = description,
        brand = brand,
        manufacturer = manufacturer,
        origin = origin,
        sku = sku,
        unit = unit,
        price = price.toDouble(),
        originalPrice = originalPrice?.toDouble(),
        importPrice = importPrice?.toDouble(),
        discountPct = effectiveDiscountPct(),
        rewardPoints = rewardPoints,
        stock = stock,
        inventoryNote = inventoryNote,
        mfgDate = mfgDate?.toString(),
        expDate = expDate?.toString(),
        productType = productType,
        registrationNumber = registrationNumber,
        riskClassification = riskClassification,
        requiresCertification = requiresCertification,
        requiresConsultation = requiresConsultation,
        isActive = isActive,
        isFlashSale = isFlashSale,
        flashSaleEnd = flashSaleEnd?.toString(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        images = images.map {
            ProductImageResponse(
                id = it.id,
                url = it.url,
                mediaType = it.mediaType,
                publicId = it.publicId,
                sortOrder = it.sortOrder
            )
        },
        certificates = certificates.filter { it.isActive }.map {
            ProductCertificateResponse(
                id = it.id,
                type = it.type,
                name = it.name,
                fileUrl = it.fileUrl,
                fileType = it.fileType,
                publicId = it.publicId,
                resourceType = it.resourceType,
                thumbnailUrl = it.thumbnailUrl,
                issuer = it.issuer,
                isActive = it.isActive
            )
        }
    )
}

private fun CreateProductPayload.toServiceRequest(): CreateProductRequest {
    return CreateProductRequest(
        categoryId = categoryId,
        name = name,
        shortDescription = shortDescription,
        description = description,
        brand = brand,
        manufacturer = manufacturer,
        origin = origin,
        sku = sku,
        unit = unit,
        price = BigDecimal.valueOf(price),
        originalPrice = originalPrice?.let { BigDecimal.valueOf(it) },
        importPrice = importPrice?.let { BigDecimal.valueOf(it) },
        stock = stock,
        inventoryNote = inventoryNote,
        mfgDate = mfgDate?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
        expDate = expDate?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
        discountPct = discountPct,
        rewardPoints = rewardPoints,
        productType = productType,
        registrationNumber = registrationNumber,
        riskClassification = riskClassification,
        requiresCertification = requiresCertification,
        requiresConsultation = requiresConsultation,
        isActive = isActive,
        attributes = attributes,
        images = images.map {
            ProductImageInput(
                url = it.url,
                mediaType = it.mediaType,
                publicId = it.publicId,
                sortOrder = it.sortOrder
            )
        },
        certificates = certificates.map {
            ProductCertificateInput(
                type = it.type,
                name = it.name,
                fileUrl = it.fileUrl,
                fileType = it.fileType,
                publicId = it.publicId,
                resourceType = it.resourceType,
                thumbnailUrl = it.thumbnailUrl,
                issuer = it.issuer,
                isActive = it.isActive
            )
        }
    )
}

private fun UpdateProductPayload.toServiceRequest(): UpdateProductRequest {
    return UpdateProductRequest(
        categoryId = categoryId,
        name = name,
        shortDescription = shortDescription,
        description = description,
        brand = brand,
        manufacturer = manufacturer,
        origin = origin,
        sku = sku,
        unit = unit,
        price = price?.let { BigDecimal.valueOf(it) },
        originalPrice = originalPrice?.let { BigDecimal.valueOf(it) },
        importPrice = importPrice?.let { BigDecimal.valueOf(it) },
        stock = stock,
        inventoryNote = inventoryNote,
        mfgDate = mfgDate?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
        expDate = expDate?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
        discountPct = discountPct,
        rewardPoints = rewardPoints,
        productType = productType,
        registrationNumber = registrationNumber,
        riskClassification = riskClassification,
        requiresCertification = requiresCertification,
        requiresConsultation = requiresConsultation,
        isActive = isActive,
        attributes = attributes,
        images = images?.map {
            ProductImageInput(
                url = it.url,
                mediaType = it.mediaType,
                publicId = it.publicId,
                sortOrder = it.sortOrder
            )
        },
        certificates = certificates?.map {
            ProductCertificateInput(
                type = it.type,
                name = it.name,
                fileUrl = it.fileUrl,
                fileType = it.fileType,
                publicId = it.publicId,
                resourceType = it.resourceType,
                thumbnailUrl = it.thumbnailUrl,
                issuer = it.issuer,
                isActive = it.isActive
            )
        }
    )
}

fun Route.productRoutes() {
    val productService = ProductService()

    route("/products") {
        // GET /api/v1/products - Public endpoint
        get {
            try {
                val categoryIds = resolvePublicCategoryIds(call.parameters["category"])
                val brand = call.parameters["brand"]
                val search = call.parameters["search"]
                val minPrice = call.parameters["minPrice"]?.toBigDecimalOrNull()
                val maxPrice = call.parameters["maxPrice"]?.toBigDecimalOrNull()
                val sortBy = call.parameters["sortBy"] ?: "name"
                val page = call.parameters["page"]?.toIntOrNull() ?: 1
                val limit = call.parameters["limit"]?.toIntOrNull() ?: 20

                if (page < 1 || limit < 1 || limit > 100) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid page or limit. Page must be >= 1, limit must be 1-100")
                    )
                }

                val response = productService.getProducts(
                    categoryIds = categoryIds,
                    brand = brand,
                    search = search,
                    minPrice = minPrice,
                    maxPrice = maxPrice,
                    sortBy = sortBy,
                    page = page,
                    limit = limit,
                    onlyActive = true
                )
                call.application.log.info(
                    "Desktop/Public products request: category={}, brand={}, page={}, limit={}, returned={}",
                    categoryIds?.joinToString(",") ?: "ALL",
                    brand ?: "ALL",
                    page,
                    limit,
                    response.products.size
                )

                val responseJson = buildJsonObject {
                    putJsonArray("products") {
                        response.products.forEach { product ->
                            add(product.toAndroidProductJson())
                        }
                    }
                    putJsonObject("pagination") {
                        put("page", response.page)
                        put("limit", response.limit)
                        put("total", response.total.toInt())
                        put("totalPages", response.totalPages)
                        put("hasNext", response.page < response.totalPages)
                        put("hasPrev", response.page > 1)
                    }
                    putJsonObject("filters") {
                        put("category", categoryIds?.joinToString(",")?.let(::JsonPrimitive) ?: JsonNull)
                        put("brand", brand?.let(::JsonPrimitive) ?: JsonNull)
                        put("minPrice", minPrice?.toDouble()?.let(::JsonPrimitive) ?: JsonNull)
                        put("maxPrice", maxPrice?.toDouble()?.let(::JsonPrimitive) ?: JsonNull)
                        put("sortBy", sortBy)
                        put("page", page)
                        put("limit", limit)
                    }
                }
                call.respondText(responseJson.toString(), ContentType.Application.Json)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to get products: ${e.message}")
                )
            }
        }

        // GET /api/v1/products/flash-sale - Android home compatibility
        get("/flash-sale") {
            try {
                val response = productService.getProducts(
                    sortBy = "created_at",
                    page = 1,
                    limit = 100,
                    onlyActive = true
                )
                val products = response.products
                    .filter { it.stock > 0 }
                    .filter { it.isFlashSale || it.effectiveDiscountPct() > 0 }
                    .sortedWith(
                        compareByDescending<ProductDto> { it.isFlashSale }
                            .thenByDescending { it.effectiveDiscountPct() }
                            .thenByDescending { it.stock }
                    )
                    .take(20)

                val nowIso = Clock.System.now().toString()
                val endTime = products.firstOrNull()?.flashSaleEnd?.toString() ?: nowIso
                val responseJson = buildJsonObject {
                    putJsonArray("products") {
                        products.forEach { product ->
                            add(product.toAndroidProductJson())
                        }
                    }
                    put("startTime", nowIso)
                    put("endTime", endTime)
                    put("timeRemaining", 0)
                }

                call.respondText(responseJson.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    RouteErrorResponse("Failed to get flash sale products: ${e.message}")
                )
            }
        }

        // GET /api/v1/products/best-sellers - Android home compatibility
        get("/best-sellers") {
            try {
                val period = call.parameters["period"]?.takeIf { it.isNotBlank() } ?: "week"
                val soldQuantities = transaction {
                    val validOrderIds = OrdersTable
                        .selectAll()
                        .filter { row ->
                            row[OrdersTable.status] !in setOf("CANCELLED", "RETURNED")
                        }
                        .map { row -> row[OrdersTable.id] }
                        .toSet()

                    val sales = mutableMapOf<String, Int>()
                    OrderItemsTable.selectAll().forEach { row ->
                        val productId = row[OrderItemsTable.productId] ?: return@forEach
                        if (row[OrderItemsTable.orderId] in validOrderIds) {
                            sales[productId] = (sales[productId] ?: 0) + row[OrderItemsTable.quantity]
                        }
                    }
                    sales
                }
                val response = productService.getProducts(
                    sortBy = "created_at",
                    page = 1,
                    limit = 200,
                    onlyActive = true
                )
                val products = response.products
                    .filter { it.stock > 0 }
                    .sortedWith(
                        compareByDescending<ProductDto> { soldQuantities[it.id] ?: 0 }
                            .thenByDescending { it.stock }
                            .thenByDescending { it.effectiveDiscountPct() }
                    )
                    .take(20)
                val responseJson = buildJsonObject {
                    putJsonArray("products") {
                        products.forEach { product ->
                            add(product.toAndroidProductJson())
                        }
                    }
                    put("period", period)
                }

                call.respondText(responseJson.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    RouteErrorResponse("Failed to get best seller products: ${e.message}")
                )
            }
        }

        // GET /api/v1/products/{id} - Public endpoint
        get("/{id}") {
            val productId = call.parameters["id"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Product ID is required")
                )

            try {
                val product = productService.getProductById(productId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Product not found")
                    )

                val responseJson = buildJsonObject {
                    put("product", product.toAndroidProductJson())
                    putJsonArray("images") {
                        product.images.sortedBy { it.sortOrder }.forEach { image ->
                            addJsonObject {
                                put("id", image.id)
                                put("productId", product.id)
                                put("imageUrl", image.url)
                                put("altText", product.name)
                                put("sortOrder", image.sortOrder)
                                put("isPrimary", image.sortOrder == 0)
                                put("createdAt", product.createdAt.toString())
                            }
                        }
                    }
                    putJsonArray("certificates") {
                        product.certificates.forEach { certificate ->
                            add(certificate.toAndroidCertificateJson(product))
                        }
                    }
                    putJsonArray("relatedProducts") {}
                }
                call.respondText(responseJson.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Failed to get product: ${e.message}")
                )
            }
        }

        // Internal desktop routes: ADMIN / EMPLOYEE
        authenticate("auth-jwt") {
            // POST /api/v1/products - internal roles only
            post {
                try {
                    val (_, shopId) = call.requireInternalAccess()

                    val payload = call.receive<CreateProductPayload>()
                    val request = payload.toServiceRequest()
                    call.application.log.info(
                        "Desktop/Product create request: shopId={}, name={}, categoryId={}",
                        shopId,
                        request.name,
                        request.categoryId ?: "NULL"
                    )

                    val productId = productService.createProduct(shopId, request)
                    call.application.log.info("Desktop/Product create success: shopId={}, productId={}", shopId, productId)

                    call.respond(
                        HttpStatusCode.Created,
                        ProductMutationResponse(
                            data = ProductIdResponse(id = productId),
                            message = "Product created successfully"
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
                        mapOf("error" to "Failed to create product: ${e.message}")
                    )
                }
            }

            // PUT /api/v1/products/{id} - internal roles only
            put("/{id}") {
                try {
                    val (_, shopId) = call.requireInternalAccess()

                    val productId = call.parameters["id"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Product ID is required")
                        )

                    val payload = call.receive<UpdateProductPayload>()
                    val request = payload.toServiceRequest()

                    productService.updateProduct(productId, shopId, request)

                    call.respond(
                        HttpStatusCode.OK,
                        ProductMutationResponse(message = "Product updated successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to update product: ${e.message}")
                    )
                }
            }

            // DELETE /api/v1/products/{id} - internal roles only
            delete("/{id}") {
                try {
                    val (principal, shopId) = call.requireInternalAccess()
                    val role = principal.getRole()?.uppercase()
                    if (role != AppRoles.ADMIN) {
                        return@delete call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "Employee cannot delete directly. Please submit delete request for admin approval.")
                        )
                    }

                    val productId = call.parameters["id"]
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Product ID is required")
                        )

                    productService.deleteProduct(productId, shopId)

                    call.respond(
                        HttpStatusCode.OK,
                        ProductMutationResponse(message = "Product deleted successfully")
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to e.message)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to delete product: ${e.message}")
                    )
                }
            }
        }
    }

    route("/internal/products") {
        authenticate("auth-jwt") {
            get {
                try {
                    val (_, shopId) = call.requireInternalAccess()

                    val categoryId = call.parameters["category"]
                    val brand = call.parameters["brand"]
                    val minPrice = call.parameters["minPrice"]?.toBigDecimalOrNull()
                    val maxPrice = call.parameters["maxPrice"]?.toBigDecimalOrNull()
                    val sortBy = call.parameters["sortBy"] ?: "name"
                    val page = call.parameters["page"]?.toIntOrNull() ?: 1
                    val limit = call.parameters["limit"]?.toIntOrNull() ?: 50

                    if (page < 1 || limit < 1 || limit > 200) {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid page or limit. Page must be >= 1, limit must be 1-200")
                        )
                    }

                    val response = productService.getProducts(
                        shopId = shopId,
                        categoryId = categoryId,
                        brand = brand,
                        minPrice = minPrice,
                        maxPrice = maxPrice,
                        sortBy = sortBy,
                        page = page,
                        limit = limit,
                        onlyActive = false
                    )

                    call.application.log.info(
                        "Desktop/Internal products response: shopId={}, page={}, limit={}, returned={}",
                        shopId,
                        page,
                        limit,
                        response.products.size
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        ProductListEnvelopeResponse(
                            data = response.products.map { it.toListItemResponse() },
                            pagination = ProductPaginationResponse(
                                page = response.page,
                                limit = response.limit,
                                total = response.total,
                                totalPages = response.totalPages
                            ),
                            message = "Get internal products successfully"
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get internal products: ${e.message}")
                    )
                }
            }

            // POST /api/v1/products/{id}/delete-request - compatibility endpoint; product is archived directly
            post("/{id}/delete-request") {
                try {
                    val (principal, shopId) = call.requireInternalAccess()
                    val productId = call.parameters["id"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Product ID is required")
                        )
                    val requesterUserId = principal.getUserId()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid token user"))
                    val payload = runCatching { call.receive<ProductDeleteRequestPayload>() }.getOrDefault(ProductDeleteRequestPayload())

                    val requestId = productService.createDeleteRequest(
                        shopId = shopId,
                        productId = productId,
                        requestedByUserId = requesterUserId,
                        reason = payload.reason
                    )

                    call.respond(
                        HttpStatusCode.Created,
                        ProductMutationResponse(
                            data = ProductIdResponse(id = requestId),
                            message = "Product archived successfully"
                        )
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to archive product: ${e.message}")
                    )
                }
            }
        }
    }
}
