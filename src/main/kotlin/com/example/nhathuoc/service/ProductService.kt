package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import kotlinx.datetime.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.util.*

data class ProductDto(
    val id: String,
    val shopId: String,
    val categoryId: String?,
    val name: String,
    val slug: String?,
    val description: String?,
    val brand: String?,
    val origin: String?,
    val sku: String?,
    val unit: String,
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val discountPct: Int,
    val rewardPoints: Int,
    val stock: Int,
    val productType: String,
    val registrationNumber: String?,
    val requiresCertification: Boolean,
    val requiresConsultation: Boolean,
    val isActive: Boolean,
    val isFlashSale: Boolean,
    val flashSaleEnd: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val attributes: Map<String, Any> = emptyMap(),
    val images: List<ProductImageDto> = emptyList(),
    val certificates: List<ProductCertificateDto> = emptyList()
)

data class ProductImageDto(
    val id: String,
    val url: String,
    val sortOrder: Int
)

data class ProductCertificateDto(
    val id: String,
    val type: String,
    val name: String,
    val fileUrl: String,
    val issueDate: String?,
    val expireDate: String?,
    val issuer: String?
)

data class CreateProductRequest(
    val categoryId: String,
    val name: String,
    val description: String?,
    val brand: String?,
    val origin: String?,
    val sku: String?,
    val unit: String = "Hộp",
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val discountPct: Int = 0,
    val rewardPoints: Int = 0,
    val productType: String = "MEDICAL_SUPPLY",
    val registrationNumber: String?,
    val requiresCertification: Boolean = false,
    val requiresConsultation: Boolean = false,
    val isActive: Boolean = true,
    val attributes: Map<String, Any> = emptyMap()
)

data class UpdateProductRequest(
    val categoryId: String?,
    val name: String?,
    val description: String?,
    val brand: String?,
    val origin: String?,
    val sku: String?,
    val unit: String?,
    val price: BigDecimal?,
    val originalPrice: BigDecimal?,
    val discountPct: Int?,
    val rewardPoints: Int?,
    val productType: String?,
    val registrationNumber: String?,
    val requiresCertification: Boolean?,
    val requiresConsultation: Boolean?,
    val isActive: Boolean?,
    val attributes: Map<String, Any>?
)

data class ProductListResponse(
    val products: List<ProductDto>,
    val total: Long,
    val page: Int,
    val limit: Int,
    val totalPages: Int
)

class ProductService(
    private val categoryAttributeService: CategoryAttributeService = CategoryAttributeService()
) {

    /**
     * Get products with filtering and pagination
     */
    fun getProducts(
        categoryId: String? = null,
        brand: String? = null,
        minPrice: BigDecimal? = null,
        maxPrice: BigDecimal? = null,
        sortBy: String = "name",
        page: Int = 1,
        limit: Int = 20
    ): ProductListResponse {
        return transaction {
            var query = ProductsTable.selectAll()

            // Apply filters
            if (categoryId != null) {
                query = query.andWhere { ProductsTable.categoryId eq categoryId }
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

            // Only show active products
            query = query.andWhere { ProductsTable.isActive eq true }

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
     * Create a new product (SHOP only)
     */
    fun createProduct(shopId: String, request: CreateProductRequest): String {
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

            // Insert product
            ProductsTable.insert {
                it[ProductsTable.id] = productId
                it[ProductsTable.shopId] = shopId
                it[ProductsTable.categoryId] = request.categoryId
                it[ProductsTable.name] = request.name
                it[ProductsTable.slug] = slug
                it[ProductsTable.description] = request.description
                it[ProductsTable.brand] = request.brand
                it[ProductsTable.origin] = request.origin
                it[ProductsTable.sku] = request.sku
                it[ProductsTable.unit] = request.unit
                it[ProductsTable.price] = request.price
                it[ProductsTable.originalPrice] = request.originalPrice
                it[ProductsTable.discountPct] = request.discountPct
                it[ProductsTable.rewardPoints] = request.rewardPoints
                it[ProductsTable.stock] = 0 // Initial stock is 0, updated via inventory
                it[ProductsTable.productType] = request.productType
                it[ProductsTable.registrationNumber] = request.registrationNumber
                it[ProductsTable.requiresCertification] = request.requiresCertification
                it[ProductsTable.requiresConsultation] = request.requiresConsultation
                it[ProductsTable.isActive] = request.isActive
            }

            // Save product attributes
            saveProductAttributes(productId, request.categoryId, request.attributes)

            productId
        }
    }

    /**
     * Update a product (SHOP only)
     */
    fun updateProduct(productId: String, shopId: String, request: UpdateProductRequest) {
        transaction {
            // Validate product exists and belongs to shop
            val product = ProductsTable
                .selectAll()
                .where {
                    (ProductsTable.id eq productId) and (ProductsTable.shopId eq shopId)
                }
                .singleOrNull()
                ?: throw IllegalArgumentException("Product not found or doesn't belong to shop")

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
                request.description?.let { value -> it[ProductsTable.description] = value }
                request.brand?.let { value -> it[ProductsTable.brand] = value }
                request.origin?.let { value -> it[ProductsTable.origin] = value }
                request.sku?.let { value -> it[ProductsTable.sku] = value }
                request.unit?.let { value -> it[ProductsTable.unit] = value }
                request.price?.let { value -> it[ProductsTable.price] = value }
                request.originalPrice?.let { value -> it[ProductsTable.originalPrice] = value }
                request.discountPct?.let { value -> it[ProductsTable.discountPct] = value }
                request.rewardPoints?.let { value -> it[ProductsTable.rewardPoints] = value }
                request.productType?.let { value -> it[ProductsTable.productType] = value }
                request.registrationNumber?.let { value -> it[ProductsTable.registrationNumber] = value }
                request.requiresCertification?.let { value -> it[ProductsTable.requiresCertification] = value }
                request.requiresConsultation?.let { value -> it[ProductsTable.requiresConsultation] = value }
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
        }
    }

    /**
     * Delete a product (SHOP only)
     */
    fun deleteProduct(productId: String, shopId: String) {
        transaction {
            // Validate product exists and belongs to shop
            val productExists = ProductsTable
                .selectAll()
                .where {
                    (ProductsTable.id eq productId) and (ProductsTable.shopId eq shopId)
                }
                .count() > 0

            if (!productExists) {
                throw IllegalArgumentException("Product not found or doesn't belong to shop")
            }

            // Check if product can be deleted (no pending orders, etc.)
            // This is a business rule - you might want to just mark as inactive instead

            // Delete related data
            ProductAttributeValuesTable.deleteWhere { ProductAttributeValuesTable.productId eq productId }
            ProductImagesTable.deleteWhere { ProductImagesTable.productId eq productId }
            ProductCertificatesTable.deleteWhere { ProductCertificatesTable.productId eq productId }

            // Delete product
            ProductsTable.deleteWhere { ProductsTable.id eq productId }
        }
    }

    private fun mapRowToProductDto(row: ResultRow): ProductDto {
        return ProductDto(
            id = row[ProductsTable.id],
            shopId = row[ProductsTable.shopId],
            categoryId = row[ProductsTable.categoryId],
            name = row[ProductsTable.name],
            slug = row[ProductsTable.slug],
            description = row[ProductsTable.description],
            brand = row[ProductsTable.brand],
            origin = row[ProductsTable.origin],
            sku = row[ProductsTable.sku],
            unit = row[ProductsTable.unit],
            price = row[ProductsTable.price],
            originalPrice = row[ProductsTable.originalPrice],
            discountPct = row[ProductsTable.discountPct],
            rewardPoints = row[ProductsTable.rewardPoints],
            stock = row[ProductsTable.stock],
            productType = row[ProductsTable.productType],
            registrationNumber = row[ProductsTable.registrationNumber],
            requiresCertification = row[ProductsTable.requiresCertification],
            requiresConsultation = row[ProductsTable.requiresConsultation],
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
                    issueDate = row[ProductCertificatesTable.issueDate],
                    expireDate = row[ProductCertificatesTable.expireDate],
                    issuer = row[ProductCertificatesTable.issuer]
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

    private fun generateSlug(name: String, productId: String): String {
        val baseSlug = name.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')

        return "$baseSlug-${productId.takeLast(8)}"
    }
}