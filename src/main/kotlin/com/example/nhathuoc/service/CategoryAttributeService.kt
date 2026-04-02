package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

data class CategoryAttributeDto(
    val id: String,
    val categoryId: String,
    val key: String,
    val label: String,
    val description: String?,
    val dataType: String,
    val unit: String?,
    val required: Boolean,
    val options: List<String>?,
    val sortOrder: Int,
    val isSearchable: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class CreateCategoryAttributeRequest(
    val key: String,
    val label: String,
    val description: String? = null,
    val dataType: String, // text, textarea, number, boolean, date, select, multiselect
    val unit: String? = null,
    val required: Boolean = false,
    val options: List<String>? = null, // for select/multiselect
    val sortOrder: Int = 0,
    val isSearchable: Boolean = false
)

data class UpdateCategoryAttributeRequest(
    val label: String,
    val description: String? = null,
    val required: Boolean,
    val options: List<String>? = null,
    val sortOrder: Int,
    val isSearchable: Boolean
)

class CategoryAttributeService {

    /**
     * Get all attributes for a category
     */
    fun getCategoryAttributes(categoryId: String): List<CategoryAttributeDto> {
        return transaction {
            CategoryAttributesTable
                .selectAll()
                .where { CategoryAttributesTable.categoryId eq categoryId }
                .orderBy(CategoryAttributesTable.sortOrder to SortOrder.ASC)
                .map { row ->
                    val optionsJson = row[CategoryAttributesTable.optionsJson]
                    val options = optionsJson?.let { parseOptionsJson(it) }

                    CategoryAttributeDto(
                        id = row[CategoryAttributesTable.id],
                        categoryId = row[CategoryAttributesTable.categoryId],
                        key = row[CategoryAttributesTable.attrKey],
                        label = row[CategoryAttributesTable.label],
                        description = row[CategoryAttributesTable.description],
                        dataType = row[CategoryAttributesTable.dataType],
                        unit = row[CategoryAttributesTable.unit],
                        required = row[CategoryAttributesTable.isRequired],  // Updated field name
                        options = options,
                        sortOrder = row[CategoryAttributesTable.sortOrder],
                        isSearchable = row[CategoryAttributesTable.isSearchable],
                        createdAt = row[CategoryAttributesTable.createdAt],
                        updatedAt = row[CategoryAttributesTable.updatedAt]
                    )
                }
        }
    }

    /**
     * Create a new attribute for a category (ADMIN only)
     */
    fun createCategoryAttribute(categoryId: String, request: CreateCategoryAttributeRequest): String {
        return transaction {
            // Validate category exists
            val categoryExists = CategoriesTable
                .selectAll()
                .where { CategoriesTable.id eq categoryId }
                .count() > 0

            if (!categoryExists) {
                throw IllegalArgumentException("Category not found")
            }

            // Validate data type
            validateDataType(request.dataType)

            // Validate options for select/multiselect
            if (request.dataType in listOf("select", "multiselect")) {
                if (request.options.isNullOrEmpty()) {
                    throw IllegalArgumentException("Options are required for select/multiselect data type")
                }
            }

            val attributeId = UUID.randomUUID().toString()
            val optionsJson = request.options?.let { createOptionsJson(it) }

            CategoryAttributesTable.insert {
                it[CategoryAttributesTable.id] = attributeId
                it[CategoryAttributesTable.categoryId] = categoryId
                it[CategoryAttributesTable.attrKey] = request.key
                it[CategoryAttributesTable.label] = request.label
                it[CategoryAttributesTable.description] = request.description
                it[CategoryAttributesTable.dataType] = request.dataType
                it[CategoryAttributesTable.unit] = request.unit
                it[CategoryAttributesTable.isRequired] = request.required
                it[CategoryAttributesTable.optionsJson] = optionsJson
                it[CategoryAttributesTable.sortOrder] = request.sortOrder
                it[CategoryAttributesTable.isSearchable] = request.isSearchable
            }

            attributeId
        }
    }

    /**
     * Update a category attribute (ADMIN only)
     */
    fun updateCategoryAttribute(
        categoryId: String,
        attributeId: String,
        request: UpdateCategoryAttributeRequest
    ) {
        transaction {
            // Validate attribute exists and belongs to category
            val attribute = CategoryAttributesTable
                .selectAll()
                .where {
                    (CategoryAttributesTable.id eq attributeId) and
                    (CategoryAttributesTable.categoryId eq categoryId)
                }
                .singleOrNull()
                ?: throw IllegalArgumentException("Attribute not found or doesn't belong to category")

            val dataType = attribute[CategoryAttributesTable.dataType]

            // Validate options for select/multiselect
            if (dataType in listOf("select", "multiselect")) {
                if (request.options.isNullOrEmpty()) {
                    throw IllegalArgumentException("Options are required for select/multiselect data type")
                }
            }

            val optionsJson = request.options?.let { createOptionsJson(it) }

            CategoryAttributesTable.update({ CategoryAttributesTable.id eq attributeId }) {
                it[CategoryAttributesTable.label] = request.label
                it[CategoryAttributesTable.description] = request.description
                it[CategoryAttributesTable.isRequired] = request.required
                it[CategoryAttributesTable.optionsJson] = optionsJson
                it[CategoryAttributesTable.sortOrder] = request.sortOrder
                it[CategoryAttributesTable.isSearchable] = request.isSearchable
            }
        }
    }

    /**
     * Delete a category attribute (ADMIN only)
     */
    fun deleteCategoryAttribute(categoryId: String, attributeId: String) {
        transaction {
            // Validate attribute exists and belongs to category
            val attributeExists = CategoryAttributesTable
                .selectAll()
                .where {
                    (CategoryAttributesTable.id eq attributeId) and
                    (CategoryAttributesTable.categoryId eq categoryId)
                }
                .count() > 0

            if (!attributeExists) {
                throw IllegalArgumentException("Attribute not found or doesn't belong to category")
            }

            // Delete all product attribute values first
            ProductAttributeValuesTable.deleteWhere {
                ProductAttributeValuesTable.attributeId eq attributeId
            }

            // Delete the attribute
            CategoryAttributesTable.deleteWhere {
                CategoryAttributesTable.id eq attributeId
            }
        }
    }

    /**
     * Validate product attribute values against category requirements
     */
    fun validateProductAttributes(
        categoryId: String,
        attributes: Map<String, Any>
    ) {
        transaction {
            val categoryAttributes = getCategoryAttributes(categoryId)
            val requiredAttributes = categoryAttributes.filter { it.required }

            // Check all required attributes are provided
            for (requiredAttr in requiredAttributes) {
                val value = attributes[requiredAttr.key]
                if (value == null || (value is String && value.isBlank())) {
                    throw IllegalArgumentException("Required attribute '${requiredAttr.label}' is missing")
                }

                validateAttributeValue(requiredAttr, value)
            }

            // Validate provided attributes
            for ((key, value) in attributes) {
                val attrDef = categoryAttributes.find { it.key == key }
                    ?: continue // Skip unknown attributes (could be legacy data)

                validateAttributeValue(attrDef, value)
            }
        }
    }

    private fun validateDataType(dataType: String) {
        val validTypes = setOf("text", "textarea", "number", "boolean", "date", "select", "multiselect")
        if (dataType !in validTypes) {
            throw IllegalArgumentException("Invalid data type. Must be one of: ${validTypes.joinToString(", ")}")
        }
    }

    private fun validateAttributeValue(attrDef: CategoryAttributeDto, value: Any) {
        when (attrDef.dataType) {
            "text", "textarea" -> {
                if (value !is String) {
                    throw IllegalArgumentException("Attribute '${attrDef.label}' must be a string")
                }
            }
            "number" -> {
                if (value !is Number && value !is String) {
                    throw IllegalArgumentException("Attribute '${attrDef.label}' must be a number")
                }
                // If string, try to parse as number
                if (value is String) {
                    try {
                        value.toDouble()
                    } catch (e: NumberFormatException) {
                        throw IllegalArgumentException("Attribute '${attrDef.label}' must be a valid number")
                    }
                }
            }
            "boolean" -> {
                if (value !is Boolean && value !is String) {
                    throw IllegalArgumentException("Attribute '${attrDef.label}' must be a boolean")
                }
                // If string, should be "true" or "false"
                if (value is String && value.lowercase() !in setOf("true", "false")) {
                    throw IllegalArgumentException("Attribute '${attrDef.label}' must be 'true' or 'false'")
                }
            }
            "date" -> {
                if (value !is String) {
                    throw IllegalArgumentException("Attribute '${attrDef.label}' must be a date string (YYYY-MM-DD)")
                }
                // Basic date format validation
                if (!value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    throw IllegalArgumentException("Attribute '${attrDef.label}' must be in YYYY-MM-DD format")
                }
            }
            "select" -> {
                if (value !is String) {
                    throw IllegalArgumentException("Attribute '${attrDef.label}' must be a string")
                }
                if (attrDef.options != null && value !in attrDef.options) {
                    throw IllegalArgumentException("Attribute '${attrDef.label}' must be one of: ${attrDef.options.joinToString(", ")}")
                }
            }
            "multiselect" -> {
                val valueList = when (value) {
                    is String -> value.split(",").map { it.trim() }
                    is List<*> -> value.map { it.toString() }
                    else -> throw IllegalArgumentException("Attribute '${attrDef.label}' must be a string or array")
                }
                if (attrDef.options != null) {
                    for (item in valueList) {
                        if (item !in attrDef.options) {
                            throw IllegalArgumentException("Attribute '${attrDef.label}' contains invalid option: $item. Valid options: ${attrDef.options.joinToString(", ")}")
                        }
                    }
                }
            }
        }
    }

    private fun createOptionsJson(options: List<String>): String {
        // Simple JSON array creation
        return "[" + options.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
    }

    private fun parseOptionsJson(json: String): List<String> {
        // Simple JSON array parsing - remove brackets and quotes
        return json.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { it.trim().removePrefix("\"").removeSuffix("\"") }
            .filter { it.isNotEmpty() }
    }
}