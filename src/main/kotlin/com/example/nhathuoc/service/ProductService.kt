package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import com.example.nhathuoc.util.CloudinaryHelper
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

data class ProductDto(
    val id: String,
    val categoryId: String?,
    val name: String,
    val slug: String?,
    val shortDescription: String?,
    val description: String?,
    val brand: String?,
    val manufacturer: String?,
    val origin: String?,
    val sku: String?,
    val unit: String,
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val importPrice: BigDecimal?,
    val discountPct: Int,
    val rewardPoints: Int,
    val stock: Int,
    val inventoryNote: String?,
    val mfgDate: LocalDate?,
    val expDate: LocalDate?,
    val productType: String = "MEDICAL_SUPPLY",
    val registrationNumber: String?,
    val riskClassification: String,
    val isActive: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val attributes: Map<String, Any> = emptyMap(),
    val images: List<ProductImageDto> = emptyList(),
    val certificates: List<ProductCertificateDto> = emptyList()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "categoryId" to categoryId,
        "name" to name,
        "slug" to slug,
        "shortDescription" to shortDescription,
        "description" to description,
        "brand" to brand,
        "manufacturer" to manufacturer,
        "origin" to origin,
        "sku" to sku,
        "unit" to unit,
        "price" to price.toString(),
        "originalPrice" to originalPrice?.toString(),
        "importPrice" to importPrice?.toString(),
        "discountPct" to discountPct,
        "rewardPoints" to rewardPoints,
        "stock" to stock,
        "inventoryNote" to inventoryNote,
        "mfgDate" to mfgDate?.toString(),
        "expDate" to expDate?.toString(),
        "registrationNumber" to registrationNumber,
        "riskClassification" to riskClassification,
        "isActive" to isActive,
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString(),
        "attributes" to attributes,
        "images" to images.map { it.toMap() },
        "certificates" to certificates.map { it.toMap() }
    )
}

data class ProductImageDto(
    val id: String,
    val url: String,
    val mediaType: String,
    val publicId: String?,
    val sortOrder: Int
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "url" to url,
        "mediaType" to mediaType,
        "publicId" to publicId,
        "sortOrder" to sortOrder
    )
}

data class ProductImageInput(
    val url: String,
    val mediaType: String = "IMAGE",
    val publicId: String? = null,
    val sortOrder: Int = 0
)

data class ProductCertificateInput(
    val type: String,
    val name: String,
    val fileUrl: String,
    val fileType: String = "IMAGE",
    val publicId: String? = null,
    val resourceType: String = "image",
    val thumbnailUrl: String? = null,
    val issuer: String? = null,
    val isActive: Boolean = true
)

data class ProductCertificateDto(
    val id: String,
    val type: String,
    val name: String,
    val fileUrl: String,
    val fileType: String,
    val publicId: String?,
    val resourceType: String,
    val thumbnailUrl: String?,
    val issuer: String?,
    val isActive: Boolean
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to type,
        "name" to name,
        "fileUrl" to fileUrl,
        "fileType" to fileType,
        "publicId" to publicId,
        "resourceType" to resourceType,
        "thumbnailUrl" to thumbnailUrl,
        "issuer" to issuer,
        "isActive" to isActive
    )
}

data class CreateProductRequest(
    val categoryId: String,
    val name: String,
    val shortDescription: String? = null,
    val description: String?,
    val brand: String?,
    val manufacturer: String? = null,
    val origin: String?,
    val sku: String?,
    val unit: String = "Hộp",
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val importPrice: BigDecimal? = null,
    val stock: Int = 0,
    val inventoryNote: String? = null,
    val mfgDate: LocalDate? = null,
    val expDate: LocalDate? = null,
    val discountPct: Int = 0,
    val rewardPoints: Int = 0,
    val productType: String = "MEDICAL_SUPPLY",
    val registrationNumber: String? = null,
    val riskClassification: String = "A",
    val isActive: Boolean = true,
    val attributes: Map<String, Any> = emptyMap(),
    val images: List<ProductImageInput> = emptyList(),
    val certificates: List<ProductCertificateInput> = emptyList()
)

data class UpdateProductRequest(
    val categoryId: String?,
    val name: String?,
    val shortDescription: String? = null,
    val description: String?,
    val brand: String?,
    val manufacturer: String? = null,
    val origin: String?,
    val sku: String?,
    val unit: String?,
    val price: BigDecimal?,
    val originalPrice: BigDecimal?,
    val importPrice: BigDecimal? = null,
    val stock: Int?,
    val stockQuantity: Int? = null,  // For Desktop compatibility
    val inventoryNote: String? = null,
    val mfgDate: LocalDate? = null,
    val expDate: LocalDate? = null,
    val discountPct: Int?,
    val rewardPoints: Int? = null,
    val productType: String? = null,
    val registrationNumber: String? = null,
    val riskClassification: String?,
    val isActive: Boolean?,
    val attributes: Map<String, Any>?,
    val images: List<ProductImageInput>?,
    val certificates: List<ProductCertificateInput>? = null
)

data class ProductListResponse(
    val products: List<ProductDto>,
    val total: Long,
    val page: Int,
    val limit: Int,
    val totalPages: Int
)

@Serializable
data class ProductDeleteRequestDto(
    val id: String,
    val productId: String,
    val productName: String,
    val status: String,
    val reason: String?,
    val requestedByUserId: String,
    val reviewedByUserId: String?,
    val reviewedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

class ProductService {
    private val allowedRiskClassifications = setOf("A", "B", "C", "D")

    private fun normalizeRiskClassification(value: String): String {
        val normalized = value.trim().uppercase()
        require(normalized in allowedRiskClassifications) {
            "Risk classification must be one of A, B, C, D"
        }
        return normalized
    }

    private fun calculateDiscountPct(originalPrice: BigDecimal?, price: BigDecimal): Int {
        if (originalPrice == null || originalPrice <= BigDecimal.ZERO || price <= BigDecimal.ZERO || originalPrice <= price) {
            return 0
        }
        return originalPrice
            .subtract(price)
            .multiply(BigDecimal(100))
            .divide(originalPrice, 0, RoundingMode.HALF_UP)
            .toInt()
            .coerceIn(0, 99)
    }

    /**
     * Get products with filtering and pagination
     */
    fun getProducts(
        shopId: String? = null,
        categoryId: String? = null,
        categoryIds: List<String>? = null,
        brand: String? = null,
        search: String? = null,
        minPrice: BigDecimal? = null,
        maxPrice: BigDecimal? = null,
        sortBy: String = "name",
        page: Int = 1,
        limit: Int = 20,
        onlyActive: Boolean = true
    ): ProductListResponse {
        return transaction {
            var query = ProductsTable.selectAll()

            // Apply filters
            if (categoryId != null) {
                query = query.andWhere { ProductsTable.categoryId eq categoryId }
            }
            if (!categoryIds.isNullOrEmpty()) {
                query = query.andWhere { ProductsTable.categoryId inList categoryIds }
            }
            if (brand != null) {
                query = query.andWhere { ProductsTable.brand eq brand }
            }
            if (!search.isNullOrBlank()) {
                val kw = "%${search.trim().lowercase()}%"
                query = query.andWhere {
                    (ProductsTable.name.lowerCase() like kw) or
                    (ProductsTable.brand.lowerCase() like kw)
                }
            }
            if (minPrice != null) {
                query = query.andWhere { ProductsTable.price greaterEq minPrice }
            }
            if (maxPrice != null) {
                query = query.andWhere { ProductsTable.price lessEq maxPrice }
            }

            if (onlyActive) {
                query = query.andWhere { ProductsTable.isActive eq true }
            }

            // Get total count
            val total = query.count()

            // Apply sorting
            val orderBy = when (sortBy) {
                "price_asc" -> ProductsTable.price to SortOrder.ASC
                "price_desc" -> ProductsTable.price to SortOrder.DESC
                "created_at" -> ProductsTable.createdAt to SortOrder.DESC
                else -> ProductsTable.name to SortOrder.ASC
            }

            // Apply pagination
            val offset = (page - 1) * limit
            val products = query
                .orderBy(orderBy)
                .limit(limit, offset.toLong())
                .map { row ->
                    mapRowToProductDto(row)
                }

            val productsWithImages = products.map { product ->
                product.copy(
                    images = getProductImages(product.id),
                    certificates = getProductCertificates(product.id)
                )
            }

            val totalPages = ((total + limit - 1) / limit).toInt()

            ProductListResponse(
                products = productsWithImages,
                total = total,
                page = page,
                limit = limit,
                totalPages = totalPages
            )
        }
    }

    /**
     * Get product by ID
     */
    fun getProductById(productId: String): ProductDto? {
        return transaction {
            ProductsTable
                .selectAll()
                .where { ProductsTable.id eq productId }
                .singleOrNull()
                ?.let { row ->
                    val product = mapRowToProductDto(row)
                    product.copy(
                        images = getProductImages(productId),
                        certificates = getProductCertificates(productId)
                    )
                }
        }
    }

    /**
     * Create a new product
     */
    fun createProduct(shopId: String, request: CreateProductRequest): String {
        return createProduct(request)
    }

    fun createProduct(request: CreateProductRequest): String {
        return transaction {
            // Validate category exists
            val categoryExists = CategoriesTable
                .selectAll()
                .where { CategoriesTable.id eq request.categoryId }
                .count() > 0

            if (!categoryExists) {
                throw IllegalArgumentException("Category not found")
            }

            val productId = UUID.randomUUID().toString()
            val slug = generateSlug(request.name, productId)
            val riskClassification = normalizeRiskClassification(request.riskClassification)
            val discountPct = calculateDiscountPct(request.originalPrice, request.price)

            // Stock is stored directly on products after simplifying inventory.
            ProductsTable.insert {
                it[ProductsTable.id] = productId
                it[ProductsTable.categoryId] = request.categoryId
                it[ProductsTable.name] = request.name
                it[ProductsTable.slug] = slug
                it[ProductsTable.shortDescription] = request.shortDescription
                it[ProductsTable.description] = request.description
                it[ProductsTable.brand] = request.brand
                it[ProductsTable.manufacturer] = request.manufacturer
                it[ProductsTable.origin] = request.origin
                it[ProductsTable.sku] = request.sku
                it[ProductsTable.unit] = request.unit
                it[ProductsTable.price] = request.price
                it[ProductsTable.originalPrice] = request.originalPrice
                it[ProductsTable.importPrice] = request.importPrice
                it[ProductsTable.discountPct] = discountPct
                it[ProductsTable.rewardPoints] = request.rewardPoints
                it[ProductsTable.stock] = request.stock.coerceAtLeast(0)
                it[ProductsTable.inventoryNote] = request.inventoryNote
                it[ProductsTable.mfgDate] = request.mfgDate
                it[ProductsTable.expDate] = request.expDate
                it[ProductsTable.registrationNumber] = request.registrationNumber
                it[ProductsTable.riskClassification] = riskClassification
                it[ProductsTable.isActive] = request.isActive
            }

            saveProductImages(productId, request.images)
            saveProductCertificates(productId, request.certificates)


            productId
        }
    }

    /**
     * Update a product
     */
    fun updateProduct(productId: String, shopId: String, request: UpdateProductRequest) {
        updateProduct(productId, request)
    }

    fun updateProduct(productId: String, request: UpdateProductRequest) {
        transaction {
            // Validate product exists
            val product = ProductsTable
                .selectAll()
                .where {
                    ProductsTable.id eq productId
                }
                .singleOrNull()
                ?: throw IllegalArgumentException("Product not found")

            val prevRisk = product[ProductsTable.riskClassification]
            val nextRiskClassification = request.riskClassification
                ?.let(::normalizeRiskClassification)
                ?: prevRisk

            val log = org.slf4j.LoggerFactory.getLogger("ProductService")
            log.info("updateProduct id={} riskClassification: {} -> {}", productId, prevRisk, nextRiskClassification)
            val nextPrice = request.price ?: product[ProductsTable.price]
            val nextOriginalPrice = request.originalPrice ?: product[ProductsTable.originalPrice]
            val nextDiscountPct = calculateDiscountPct(nextOriginalPrice, nextPrice)

            // Update product basic info
            ProductsTable.update({ ProductsTable.id eq productId }) {
                request.categoryId?.let { value -> it[ProductsTable.categoryId] = value }
                request.name?.let { value ->
                    it[ProductsTable.name] = value
                    it[ProductsTable.slug] = generateSlug(value, productId)
                }
                request.shortDescription?.let { value -> it[ProductsTable.shortDescription] = value }
                request.description?.let { value -> it[ProductsTable.description] = value }
                request.brand?.let { value -> it[ProductsTable.brand] = value }
                request.manufacturer?.let { value -> it[ProductsTable.manufacturer] = value }
                request.origin?.let { value -> it[ProductsTable.origin] = value }
                request.sku?.let { value -> it[ProductsTable.sku] = value }
                request.unit?.let { value -> it[ProductsTable.unit] = value }
                request.price?.let { value -> it[ProductsTable.price] = value }
                request.originalPrice?.let { value -> it[ProductsTable.originalPrice] = value }
                request.importPrice?.let { value -> it[ProductsTable.importPrice] = value }
                // Handle stock - prefer stockQuantity if provided (Desktop), else use stock
                val stockValue = request.stockQuantity ?: request.stock
                stockValue?.let { value -> it[ProductsTable.stock] = value.coerceAtLeast(0) }
                request.inventoryNote?.let { value -> it[ProductsTable.inventoryNote] = value }
                request.mfgDate?.let { value -> it[ProductsTable.mfgDate] = value }
                request.expDate?.let { value -> it[ProductsTable.expDate] = value }
                it[ProductsTable.discountPct] = nextDiscountPct
                request.rewardPoints?.let { value -> it[ProductsTable.rewardPoints] = value }
                request.registrationNumber?.let { value -> it[ProductsTable.registrationNumber] = value }
                it[ProductsTable.riskClassification] = nextRiskClassification
                request.isActive?.let { value -> it[ProductsTable.isActive] = value }
                it[ProductsTable.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            }

            if (request.images != null) {
                try {
                    replaceProductImages(productId, request.images)
                } catch (e: Exception) {
                    log.warn("updateProduct id={}: replaceProductImages failed (main update still committed): {}", productId, e.message)
                }
            }

            if (request.certificates != null) {
                try {
                    replaceProductCertificates(productId, request.certificates)
                } catch (e: Exception) {
                    log.warn("updateProduct id={}: replaceProductCertificates failed (main update still committed): {}", productId, e.message)
                }
            }
        }
    }

    /**
     * Delete a product
     */
    fun deleteProduct(productId: String, shopId: String) {
        deleteProduct(productId)
    }

    fun deleteProduct(productId: String) {
        transaction {
            val productExists = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq productId }
                .count() > 0

            if (!productExists) {
                throw IllegalArgumentException("Product not found")
            }

            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

            CartItemsTable.deleteWhere { CartItemsTable.productId eq productId }
            ProductsTable.update({ ProductsTable.id eq productId }) {
                it[ProductsTable.isActive] = false
                it[ProductsTable.stock] = 0
                it[ProductsTable.updatedAt] = now
                it[ProductsTable.deletedAt] = now
            }
        }
    }

    /**
     * Receive product stock into the simplified product-level inventory.
     */
    fun addStockReceipt(
        productId: String,
        mfgDate: LocalDate?,
        expDate: LocalDate?,
        quantity: Int,
        importPrice: BigDecimal?
    ): String {
        return transaction {
            // Validate product exists
            val productExists = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq productId }
                .count() > 0

            if (!productExists) {
                throw IllegalArgumentException("Product not found")
            }

            require(quantity > 0) { "Stock quantity must be greater than 0" }

            val currentStock = ProductsTable
                .select(ProductsTable.stock)
                .where { ProductsTable.id eq productId }
                .single()[ProductsTable.stock]

            ProductsTable.update({ ProductsTable.id eq productId }) {
                it[ProductsTable.stock] = currentStock + quantity
                importPrice?.let { value -> it[ProductsTable.importPrice] = value }
                mfgDate?.let { value -> it[ProductsTable.mfgDate] = value }
                expDate?.let { value -> it[ProductsTable.expDate] = value }
                it[ProductsTable.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            }

            productId
        }
    }

    fun replaceProductDiseases(productId: String, diseaseIds: List<String>) {
        transaction {
            val productExists = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq productId }
                .count() > 0
            if (!productExists) {
                throw IllegalArgumentException("Product not found")
            }
            // Disease mapping was removed from the simplified DATN schema.
            // Keep this method as a compatibility no-op for older desktop calls.
        }
    }

    fun createDeleteRequest(shopId: String, productId: String, requestedByUserId: String, reason: String?): String {
        return createDeleteRequest(productId, requestedByUserId, reason)
    }

    fun createDeleteRequest(productId: String, requestedByUserId: String, reason: String?): String {
        deleteProduct(productId)
        return productId
    }

    fun listDeleteRequests(status: String? = null): List<ProductDeleteRequestDto> {
        return emptyList()
    }

    fun reviewDeleteRequest(requestId: String, adminUserId: String, approve: Boolean): ProductDeleteRequestDto {
        throw IllegalArgumentException("Product delete approval flow was removed. Products are archived directly.")
    }

    private fun mapRowToProductDto(row: ResultRow): ProductDto {
        return ProductDto(
            id = row[ProductsTable.id],
            categoryId = row[ProductsTable.categoryId],
            name = row[ProductsTable.name],
            slug = row[ProductsTable.slug],
            shortDescription = row[ProductsTable.shortDescription],
            description = row[ProductsTable.description],
            brand = row[ProductsTable.brand],
            manufacturer = row[ProductsTable.manufacturer],
            origin = row[ProductsTable.origin],
            sku = row[ProductsTable.sku],
            unit = row[ProductsTable.unit],
            price = row[ProductsTable.price],
            originalPrice = row[ProductsTable.originalPrice],
            importPrice = row[ProductsTable.importPrice],
            discountPct = row[ProductsTable.discountPct],
            rewardPoints = row[ProductsTable.rewardPoints],
            stock = row[ProductsTable.stock],
            inventoryNote = row[ProductsTable.inventoryNote],
            mfgDate = row[ProductsTable.mfgDate],
            expDate = row[ProductsTable.expDate],
            registrationNumber = row[ProductsTable.registrationNumber],
            riskClassification = row[ProductsTable.riskClassification],
            isActive = row[ProductsTable.isActive],
            createdAt = row[ProductsTable.createdAt],
            updatedAt = row[ProductsTable.updatedAt]
        )
    }

    private fun getProductAttributes(productId: String): Map<String, Any> {
        return emptyMap()
    }

    private fun getProductImages(productId: String): List<ProductImageDto> {
        return ProductImagesTable
            .selectAll()
            .where { ProductImagesTable.productId eq productId }
            .orderBy(ProductImagesTable.sortOrder to SortOrder.ASC)
            .map { row ->
                ProductImageDto(
                    id = row[ProductImagesTable.id],
                    url = row[ProductImagesTable.url],
                    mediaType = row[ProductImagesTable.mediaType],
                    publicId = row[ProductImagesTable.cloudinaryPublicId],
                    sortOrder = row[ProductImagesTable.sortOrder]
                )
            }
    }

    private fun getProductCertificates(productId: String): List<ProductCertificateDto> {
        return ProductCertificatesTable
            .selectAll()
            .where { ProductCertificatesTable.productId eq productId }
            .map { row ->
                ProductCertificateDto(
                    id = row[ProductCertificatesTable.id],
                    type = row[ProductCertificatesTable.type],
                    name = row[ProductCertificatesTable.name],
                    fileUrl = row[ProductCertificatesTable.fileUrl],
                    fileType = row[ProductCertificatesTable.fileType],
                    publicId = row[ProductCertificatesTable.cloudinaryPublicId],
                    resourceType = row[ProductCertificatesTable.cloudinaryResourceType],
                    thumbnailUrl = row[ProductCertificatesTable.thumbnailUrl],
                    issuer = row[ProductCertificatesTable.issuer],
                    isActive = row[ProductCertificatesTable.isActive]
                )
            }
    }

    private fun saveProductAttributes(productId: String, categoryId: String, attributes: Map<String, Any>) {
        // Dynamic product attributes were removed from the simplified DATN schema.
    }

    private fun replaceProductImages(productId: String, images: List<ProductImageInput>) {
        val retainedPublicIds = images.mapNotNull { it.publicId }.toSet()
        val existingPublicIds = ProductImagesTable
            .selectAll()
            .where { ProductImagesTable.productId eq productId }
            .mapNotNull { it[ProductImagesTable.cloudinaryPublicId] }

        ProductImagesTable.deleteWhere { ProductImagesTable.productId eq productId }
        saveProductImages(productId, images)

        existingPublicIds
            .filterNot { it in retainedPublicIds }
            .forEach(::safeDeleteCloudinaryAsset)
    }

    private fun replaceProductCertificates(productId: String, certificates: List<ProductCertificateInput>) {
        ProductCertificatesTable.deleteWhere { ProductCertificatesTable.productId eq productId }
        saveProductCertificates(productId, certificates)
    }

    private fun saveProductImages(productId: String, images: List<ProductImageInput>) {
        images
            .filter { it.url.isNotBlank() }
            .sortedBy { it.sortOrder }
            .forEach { image ->
                ProductImagesTable.insert {
                    it[ProductImagesTable.id] = UUID.randomUUID().toString()
                    it[ProductImagesTable.productId] = productId
                    it[ProductImagesTable.url] = image.url
                    it[ProductImagesTable.mediaType] = image.mediaType.ifBlank { "IMAGE" }
                    it[ProductImagesTable.cloudinaryPublicId] = image.publicId
                    it[ProductImagesTable.sortOrder] = image.sortOrder
                }
            }
    }

    private fun saveProductCertificates(productId: String, certificates: List<ProductCertificateInput>) {
        certificates
            .filter { it.name.isNotBlank() && it.fileUrl.isNotBlank() }
            .forEach { certificate ->
                ProductCertificatesTable.insert {
                    it[ProductCertificatesTable.id] = UUID.randomUUID().toString()
                    it[ProductCertificatesTable.productId] = productId
                    it[ProductCertificatesTable.type] = certificate.type.ifBlank { "MOH_LICENSE" }
                    it[ProductCertificatesTable.name] = certificate.name
                    it[ProductCertificatesTable.fileUrl] = certificate.fileUrl
                    it[ProductCertificatesTable.fileType] = certificate.fileType.ifBlank { detectFileType(certificate.fileUrl) }
                    it[ProductCertificatesTable.cloudinaryPublicId] = certificate.publicId?.ifBlank { null }
                    it[ProductCertificatesTable.cloudinaryResourceType] =
                        certificate.resourceType.ifBlank { detectResourceType(certificate.fileUrl) }
                    it[ProductCertificatesTable.thumbnailUrl] = certificate.thumbnailUrl?.ifBlank { null }
                    it[ProductCertificatesTable.issuer] = certificate.issuer?.ifBlank { null }
                    it[ProductCertificatesTable.isActive] = certificate.isActive
                }
            }
    }

    private fun detectFileType(url: String): String {
        val normalized = url.substringBefore("?").lowercase()
        return when {
            normalized.endsWith(".pdf") -> "PDF"
            normalized.endsWith(".png") || normalized.endsWith(".jpg") ||
                normalized.endsWith(".jpeg") || normalized.endsWith(".webp") ||
                normalized.endsWith(".gif") || normalized.endsWith(".heic") -> "IMAGE"
            else -> "OTHER"
        }
    }

    private fun detectResourceType(url: String): String {
        return when {
            url.contains("/raw/upload/") -> "raw"
            url.substringBefore("?").lowercase().endsWith(".pdf") -> "raw"
            url.contains("/video/upload/") -> "video"
            else -> "image"
        }
    }

    private fun safeDeleteCloudinaryAsset(publicId: String) {
        runCatching {
            CloudinaryHelper.delete(publicId)
        }
    }

    private fun generateSlug(name: String, productId: String): String {
        val baseSlug = name.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')

        return "$baseSlug-${productId.takeLast(8)}"
    }
}
