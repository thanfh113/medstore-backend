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
    val discountPct: Int,
    val rewardPoints: Int,
    val stock: Int,
    val productType: String = "MEDICAL_SUPPLY",
    val registrationNumber: String?,
    val riskClassification: String,
    val requiresCertification: Boolean,
    val requiresConsultation: Boolean,
    val targetAudience: String,
    val isActive: Boolean,
    val isFlashSale: Boolean,
    val flashSaleEnd: LocalDateTime?,
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
        "discountPct" to discountPct,
        "rewardPoints" to rewardPoints,
        "stock" to stock,
        "registrationNumber" to registrationNumber,
        "riskClassification" to riskClassification,
        "requiresCertification" to requiresCertification,
        "requiresConsultation" to requiresConsultation,
        "targetAudience" to targetAudience,
        "isActive" to isActive,
        "isFlashSale" to isFlashSale,
        "flashSaleEnd" to flashSaleEnd?.toString(),
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
    val issueDate: String? = null,
    val expireDate: String? = null,
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
    val issueDate: String?,
    val expireDate: String?,
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
        "issueDate" to issueDate,
        "expireDate" to expireDate,
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
    val stock: Int = 0,
    val discountPct: Int = 0,
    val rewardPoints: Int = 0,
    val productType: String = "MEDICAL_SUPPLY",
    val registrationNumber: String? = null,
    val riskClassification: String = "A",
    val requiresCertification: Boolean = false,
    val requiresConsultation: Boolean = false,
    val targetAudience: String = "ALL",
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
    val stock: Int?,
    val stockQuantity: Int? = null,  // For Desktop compatibility
    val discountPct: Int?,
    val rewardPoints: Int? = null,
    val productType: String? = null,
    val registrationNumber: String? = null,
    val riskClassification: String?,
    val requiresCertification: Boolean?,
    val requiresConsultation: Boolean?,
    val targetAudience: String? = null,
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

class ProductService(
    private val categoryAttributeService: CategoryAttributeService = CategoryAttributeService()
) {
    private val allowedRiskClassifications = setOf("A", "B", "C", "D")

    private fun normalizeRiskClassification(value: String): String {
        val normalized = value.trim().uppercase()
        require(normalized in allowedRiskClassifications) {
            "Risk classification must be one of A, B, C, D"
        }
        return normalized
    }

    /**
     * Get products with filtering and pagination
     */
    fun getProducts(
        shopId: String? = null,
        categoryId: String? = null,
        categoryIds: List<String>? = null,
        brand: String? = null,
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

            // Load attributes for each product
            val productsWithAttributes = products.map { product ->
                product.copy(
                    attributes = getProductAttributes(product.id),
                    images = getProductImages(product.id)
                )
            }

            val totalPages = ((total + limit - 1) / limit).toInt()

            ProductListResponse(
                products = productsWithAttributes,
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
                        attributes = getProductAttributes(productId),
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

            // Validate attributes against category requirements
            categoryAttributeService.validateProductAttributes(request.categoryId, request.attributes)

            val productId = UUID.randomUUID().toString()
            val slug = generateSlug(request.name, productId)
            val riskClassification = normalizeRiskClassification(request.riskClassification)

            // Insert product - stock will be managed through batches
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
                it[ProductsTable.discountPct] = request.discountPct
                it[ProductsTable.rewardPoints] = request.rewardPoints
                it[ProductsTable.stock] = 0  // Will be updated by batch operations
                it[ProductsTable.registrationNumber] = request.registrationNumber
                it[ProductsTable.riskClassification] = riskClassification
                it[ProductsTable.requiresCertification] = request.requiresCertification
                it[ProductsTable.requiresConsultation] = request.requiresConsultation
                it[ProductsTable.targetAudience] = request.targetAudience.ifBlank { "ALL" }
                it[ProductsTable.isActive] = request.isActive
            }

            // Save product attributes
            saveProductAttributes(productId, request.categoryId, request.attributes)
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

            val currentCategoryId = product[ProductsTable.categoryId]
            val newCategoryId = request.categoryId ?: currentCategoryId

            // If category changed or attributes updated, validate attributes
            if (request.attributes != null) {
                if (newCategoryId != null) {
                    categoryAttributeService.validateProductAttributes(newCategoryId, request.attributes)
                }
            }

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
                // Handle stock - prefer stockQuantity if provided (Desktop), else use stock
                val stockValue = request.stockQuantity ?: request.stock
                stockValue?.let { value -> it[ProductsTable.stock] = value }
                request.discountPct?.let { value -> it[ProductsTable.discountPct] = value }
                request.rewardPoints?.let { value -> it[ProductsTable.rewardPoints] = value }
                request.registrationNumber?.let { value -> it[ProductsTable.registrationNumber] = value }
                request.riskClassification?.let { value ->
                    it[ProductsTable.riskClassification] = normalizeRiskClassification(value)
                }
                request.requiresCertification?.let { value -> it[ProductsTable.requiresCertification] = value }
                request.requiresConsultation?.let { value -> it[ProductsTable.requiresConsultation] = value }
                request.targetAudience?.let { value -> it[ProductsTable.targetAudience] = value.ifBlank { "ALL" } }
                request.isActive?.let { value -> it[ProductsTable.isActive] = value }
                it[ProductsTable.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            }

            // Update attributes if provided
            if (request.attributes != null && newCategoryId != null) {
                // Delete existing attributes
                ProductAttributeValuesTable.deleteWhere {
                    ProductAttributeValuesTable.productId eq productId
                }

                // Save new attributes
                saveProductAttributes(productId, newCategoryId, request.attributes)
            }

            if (request.images != null) {
                replaceProductImages(productId, request.images)
            }

            if (request.certificates != null) {
                replaceProductCertificates(productId, request.certificates)
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
            // Validate product exists
            val productExists = ProductsTable
                .selectAll()
                .where {
                    ProductsTable.id eq productId
                }
                .count() > 0

            if (!productExists) {
                throw IllegalArgumentException("Product not found")
            }

            // Check if product can be deleted (no pending orders, etc.)
            // This is a business rule - you might want to just mark as inactive instead

            val cloudinaryPublicIds = ProductImagesTable
                .selectAll()
                .where { ProductImagesTable.productId eq productId }
                .mapNotNull { it[ProductImagesTable.cloudinaryPublicId] }

            // Delete related data
            ProductAttributeValuesTable.deleteWhere { ProductAttributeValuesTable.productId eq productId }
            ProductImagesTable.deleteWhere { ProductImagesTable.productId eq productId }
            ProductCertificatesTable.deleteWhere { ProductCertificatesTable.productId eq productId }

            // Delete product
            ProductsTable.deleteWhere { ProductsTable.id eq productId }

            cloudinaryPublicIds.forEach(::safeDeleteCloudinaryAsset)
        }
    }

    /**
     * Add a batch/lot to a product
     */
    fun addBatch(
        productId: String,
        lotNumber: String?,
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

            require(quantity > 0) { "Batch quantity must be greater than 0" }

            val batchId = UUID.randomUUID().toString()

            // Insert batch
            ProductBatchesTable.insert {
                it[ProductBatchesTable.id] = batchId
                it[ProductBatchesTable.productId] = productId
                it[ProductBatchesTable.lotNumber] = lotNumber
                it[ProductBatchesTable.mfgDate] = mfgDate
                it[ProductBatchesTable.expDate] = expDate
                it[ProductBatchesTable.quantityOnHand] = quantity
                it[ProductBatchesTable.importPrice] = importPrice
            }

            // Update product stock
            val currentStock = ProductsTable
                .select(ProductsTable.stock)
                .where { ProductsTable.id eq productId }
                .single()[ProductsTable.stock]

            ProductsTable.update({ ProductsTable.id eq productId }) {
                it[ProductsTable.stock] = currentStock + quantity
                it[ProductsTable.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            }

            batchId
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

            val normalizedIds = diseaseIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            if (normalizedIds.isNotEmpty()) {
                val validIds = DiseaseCategoriesTable
                    .selectAll()
                    .where { DiseaseCategoriesTable.id inList normalizedIds }
                    .map { it[DiseaseCategoriesTable.id] }
                    .toSet()
                val missingIds = normalizedIds.filterNot { it in validIds }
                if (missingIds.isNotEmpty()) {
                    throw IllegalArgumentException("Invalid disease IDs: ${missingIds.joinToString(", ")}")
                }
            }

            ProductDiseasesTable.deleteWhere { ProductDiseasesTable.productId eq productId }
            normalizedIds.forEach { diseaseId ->
                ProductDiseasesTable.insert {
                    it[ProductDiseasesTable.productId] = productId
                    it[ProductDiseasesTable.diseaseId] = diseaseId
                }
            }
        }
    }

    fun createDeleteRequest(shopId: String, productId: String, requestedByUserId: String, reason: String?): String {
        return createDeleteRequest(productId, requestedByUserId, reason)
    }

    fun createDeleteRequest(productId: String, requestedByUserId: String, reason: String?): String {
        return transaction {
            val exists = ProductsTable
                .selectAll()
                .where { ProductsTable.id eq productId }
                .count() > 0
            if (!exists) {
                throw IllegalArgumentException("Product not found")
            }

            val pending = ProductDeleteRequestsTable
                .selectAll()
                .where {
                    (ProductDeleteRequestsTable.productId eq productId) and
                    (ProductDeleteRequestsTable.status eq "PENDING")
                }
                .count() > 0
            if (pending) {
                throw IllegalArgumentException("Delete request is already pending for this product")
            }

            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val requestId = UUID.randomUUID().toString()
            ProductDeleteRequestsTable.insert {
                it[id] = requestId
                it[ProductDeleteRequestsTable.productId] = productId
                it[ProductDeleteRequestsTable.requestedByUserId] = requestedByUserId
                it[ProductDeleteRequestsTable.reason] = reason?.trim()?.ifBlank { null }
                it[status] = "PENDING"
                it[createdAt] = now
                it[updatedAt] = now
            }
            requestId
        }
    }

    fun listDeleteRequests(status: String? = null): List<ProductDeleteRequestDto> {
        return transaction {
            val normalizedStatus = status?.trim()?.uppercase()?.ifBlank { null }
            ProductDeleteRequestsTable
                .join(ProductsTable, JoinType.INNER, ProductDeleteRequestsTable.productId, ProductsTable.id)
                .selectAll()
                .where {
                    if (normalizedStatus == null) ProductDeleteRequestsTable.status neq "ARCHIVED"
                    else ProductDeleteRequestsTable.status eq normalizedStatus
                }
                .orderBy(ProductDeleteRequestsTable.createdAt, SortOrder.DESC)
                .map { row ->
                    ProductDeleteRequestDto(
                        id = row[ProductDeleteRequestsTable.id],
                        productId = row[ProductDeleteRequestsTable.productId],
                        productName = row[ProductsTable.name],
                        status = row[ProductDeleteRequestsTable.status],
                        reason = row[ProductDeleteRequestsTable.reason],
                        requestedByUserId = row[ProductDeleteRequestsTable.requestedByUserId],
                        reviewedByUserId = row[ProductDeleteRequestsTable.reviewedByUserId],
                        reviewedAt = row[ProductDeleteRequestsTable.reviewedAt],
                        createdAt = row[ProductDeleteRequestsTable.createdAt],
                        updatedAt = row[ProductDeleteRequestsTable.updatedAt]
                    )
                }
        }
    }

    fun reviewDeleteRequest(requestId: String, adminUserId: String, approve: Boolean): ProductDeleteRequestDto {
        return transaction {
            val existing = ProductDeleteRequestsTable
                .selectAll()
                .where {
                    ProductDeleteRequestsTable.id eq requestId
                }
                .singleOrNull()
                ?: throw IllegalArgumentException("Delete request not found")

            if (existing[ProductDeleteRequestsTable.status] != "PENDING") {
                throw IllegalArgumentException("Delete request is already processed")
            }

            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val finalStatus = if (approve) "APPROVED" else "REJECTED"

            ProductDeleteRequestsTable.update({ ProductDeleteRequestsTable.id eq requestId }) {
                it[status] = finalStatus
                it[reviewedByUserId] = adminUserId
                it[reviewedAt] = now
                it[updatedAt] = now
            }

            val productId = existing[ProductDeleteRequestsTable.productId]
            val productName = ProductsTable
                .select(ProductsTable.name)
                .where { ProductsTable.id eq productId }
                .singleOrNull()
                ?.get(ProductsTable.name)
                ?: "Deleted product"

            val responseDto = ProductDeleteRequestDto(
                id = requestId,
                productId = productId,
                productName = productName,
                status = finalStatus,
                reason = existing[ProductDeleteRequestsTable.reason],
                requestedByUserId = existing[ProductDeleteRequestsTable.requestedByUserId],
                reviewedByUserId = adminUserId,
                reviewedAt = now,
                createdAt = existing[ProductDeleteRequestsTable.createdAt],
                updatedAt = now
            )

            if (approve) {
                // FK is RESTRICT from product_delete_requests -> products, so clear related requests first.
                ProductDeleteRequestsTable.deleteWhere { ProductDeleteRequestsTable.productId eq productId }
                deleteProduct(productId)
                return@transaction responseDto
            }

            responseDto
        }
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
            discountPct = row[ProductsTable.discountPct],
            rewardPoints = row[ProductsTable.rewardPoints],
            stock = row[ProductsTable.stock],
            registrationNumber = row[ProductsTable.registrationNumber],
            riskClassification = row[ProductsTable.riskClassification],
            requiresCertification = row[ProductsTable.requiresCertification],
            requiresConsultation = row[ProductsTable.requiresConsultation],
            targetAudience = row[ProductsTable.targetAudience],
            isActive = row[ProductsTable.isActive],
            isFlashSale = row[ProductsTable.isFlashSale],
            flashSaleEnd = row[ProductsTable.flashSaleEnd],
            createdAt = row[ProductsTable.createdAt],
            updatedAt = row[ProductsTable.updatedAt]
        )
    }

    private fun getProductAttributes(productId: String): Map<String, Any> {
        return ProductAttributeValuesTable
            .join(CategoryAttributesTable, JoinType.INNER) {
                ProductAttributeValuesTable.attributeId eq CategoryAttributesTable.id
            }
            .selectAll()
            .where { ProductAttributeValuesTable.productId eq productId }
            .associate { row ->
                val key = row[CategoryAttributesTable.attrKey]
                val dataType = row[CategoryAttributesTable.dataType]

                val value: Any = when (dataType) {
                    "text", "textarea", "select" -> row[ProductAttributeValuesTable.valueText] ?: ""
                    "number" -> row[ProductAttributeValuesTable.valueNumber] ?: 0
                    "boolean" -> row[ProductAttributeValuesTable.valueBool] ?: false
                    "date" -> row[ProductAttributeValuesTable.valueDate]?.toString() ?: ""
                    "multiselect" -> row[ProductAttributeValuesTable.valueText]?.split(",") ?: emptyList<String>()
                    else -> row[ProductAttributeValuesTable.valueText] ?: ""
                }

                key to value
            }
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
                    issueDate = row[ProductCertificatesTable.issueDate],
                    expireDate = row[ProductCertificatesTable.expireDate],
                    issuer = row[ProductCertificatesTable.issuer],
                    isActive = row[ProductCertificatesTable.isActive]
                )
            }
    }

    private fun saveProductAttributes(productId: String, categoryId: String, attributes: Map<String, Any>) {
        // Get category attributes to determine data types
        val categoryAttributes = categoryAttributeService.getCategoryAttributes(categoryId)

        for ((key, value) in attributes) {
            val attrDef = categoryAttributes.find { it.key == key } ?: continue

            val attributeValueId = UUID.randomUUID().toString()

            ProductAttributeValuesTable.insert {
                it[ProductAttributeValuesTable.id] = attributeValueId
                it[ProductAttributeValuesTable.productId] = productId
                it[ProductAttributeValuesTable.attributeId] = attrDef.id

                // Store value in appropriate column based on data type
                when (attrDef.dataType) {
                    "text", "textarea", "select" -> {
                        it[ProductAttributeValuesTable.valueText] = value.toString()
                    }
                    "number" -> {
                        it[ProductAttributeValuesTable.valueNumber] = when (value) {
                            is Number -> BigDecimal.valueOf(value.toDouble())
                            is String -> BigDecimal(value)
                            else -> throw IllegalArgumentException("Invalid number value for attribute ${attrDef.key}")
                        }
                    }
                    "boolean" -> {
                        it[ProductAttributeValuesTable.valueBool] = when (value) {
                            is Boolean -> value
                            is String -> value.lowercase() == "true"
                            else -> throw IllegalArgumentException("Invalid boolean value for attribute ${attrDef.key}")
                        }
                    }
                    "date" -> {
                        it[ProductAttributeValuesTable.valueDate] = LocalDate.parse(value.toString())
                    }
                    "multiselect" -> {
                        val valueList = when (value) {
                            is String -> value.split(",").map { it.trim() }
                            is List<*> -> value.map { it.toString() }
                            else -> listOf(value.toString())
                        }
                        it[ProductAttributeValuesTable.valueText] = valueList.joinToString(",")
                    }
                }
            }
        }
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
                    it[ProductCertificatesTable.issueDate] = certificate.issueDate?.ifBlank { null }
                    it[ProductCertificatesTable.expireDate] = certificate.expireDate?.ifBlank { null }
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
