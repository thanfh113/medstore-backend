package com.example.nhathuoc.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime

// ─────────────────────────────────────────────────────────────
// AUTH
// ─────────────────────────────────────────────────────────────

object UsersTable : Table("users") {
    val id          = varchar("id", 36)           // UUID string
    val phone       = varchar("phone", 15).uniqueIndex()
    val email       = varchar("email", 255).nullable().uniqueIndex()
    val password    = varchar("password", 255)
    val fullName    = varchar("full_name", 100).nullable()
    val avatarUrl   = text("avatar_url").nullable()
    val gender      = varchar("gender", 10).nullable()     // Nam/Nữ/Khác
    val dateOfBirth = varchar("date_of_birth", 20).nullable()
    val role        = varchar("role", 20).default("USER") // ADMIN | SHOP | USER
    val isActive    = bool("is_active").default(true)
    val createdAt   = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt   = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object RefreshTokensTable : Table("refresh_tokens") {
    val id        = varchar("id", 36)
    val userId    = varchar("user_id", 36).references(UsersTable.id)
    val token     = text("token")
    val expiresAt = datetime("expires_at")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

// ─────────────────────────────────────────────────────────────
// SHOP & BRANCHES
// ─────────────────────────────────────────────────────────────

object ShopsTable : Table("shops") {
    val id                = varchar("id", 36)
    val ownerId           = varchar("owner_id", 36).references(UsersTable.id).uniqueIndex()  // unique constraint
    val name              = varchar("name", 200)
    val description       = text("description").nullable()
    val logoUrl           = text("logo_url").nullable()
    val licenseNumber     = varchar("license_number", 100).nullable()
    val isApproved        = bool("is_approved").default(false)
    val expiryAlertDays   = integer("expiry_alert_days").default(30)
    val createdAt         = datetime("created_at").defaultExpression(CurrentDateTime)
    val deletedAt         = datetime("deleted_at").nullable()  // Added from DB
    override val primaryKey = PrimaryKey(id)
}

object PharmacyBranchesTable : Table("pharmacy_branches") {
    val id        = varchar("id", 36)
    val shopId    = varchar("shop_id", 36).references(ShopsTable.id)
    val name      = varchar("name", 200)
    val address   = text("address")
    val latitude  = decimal("latitude", 10, 8).nullable()
    val longitude = decimal("longitude", 11, 8).nullable()
    val phone     = varchar("phone", 15).nullable()
    val openTime  = varchar("open_time", 8).nullable()   // "07:00"
    val closeTime = varchar("close_time", 8).nullable()  // "22:00"
    val isActive  = bool("is_active").default(true)
    override val primaryKey = PrimaryKey(id)
}

// ─────────────────────────────────────────────────────────────
// PRODUCTS & CATEGORIES
// ─────────────────────────────────────────────────────────────

object CategoriesTable : Table("categories") {
    val id                  = varchar("id", 36)
    val parentId            = varchar("parent_id", 36).nullable()
    val name                = varchar("name", 100)
    val slug                = varchar("slug", 150).nullable()  // Match DB: 150 chars
    val description         = text("description").nullable()
    val productTypeDefault  = varchar("product_type_default", 30).nullable() // Match DB: nullable
    val iconUrl             = text("icon_url").nullable()
    val sortOrder           = integer("sort_order").default(0)
    val isActive            = bool("is_active").default(true)
    val createdAt           = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt           = datetime("updated_at").defaultExpression(CurrentDateTime)
    val deletedAt           = datetime("deleted_at").nullable()  // Added from DB
    override val primaryKey = PrimaryKey(id)
}

object ProductsTable : Table("products") {
    val id                 = varchar("id", 36)
    val shopId             = varchar("shop_id", 36).references(ShopsTable.id)
    val categoryId         = varchar("category_id", 36).references(CategoriesTable.id).nullable()
    val name               = varchar("name", 300)
    val slug               = varchar("slug", 300).uniqueIndex().nullable()
    val description        = text("description").nullable()
    val brand              = varchar("brand", 100).nullable()
    val origin             = varchar("origin", 100).nullable()
    val sku                = varchar("sku", 100).uniqueIndex().nullable()
    val unit               = varchar("unit", 50).default("Hộp")
    val price              = decimal("price", 15, 0)  // Match DB: decimal(15,0)
    val originalPrice      = decimal("original_price", 15, 0).nullable() // Match DB
    val discountPct        = integer("discount_pct").default(0)
    val rewardPoints       = integer("reward_points").default(0)
    val stock              = integer("stock").default(0)
    // Phân loại sản phẩm: MEDICAL_SUPPLY | DIAGNOSTIC_DEVICE | SURGICAL_TOOL | BANDAGE_WOUND_CARE | PPE_PROTECTIVE | REHABILITATION | LAB_CONSUMABLE | DISINFECTION | OTHER
    val productType        = varchar("product_type", 30).default("MEDICAL_SUPPLY")
    // Số đăng ký lưu hành (ví dụ: CE Mark, FDA 510(k), ISO 13485)
    val registrationNumber = varchar("registration_number", 100).nullable()
    val isPrescription     = bool("is_prescription").default(false)  // Cần đơn thuốc
    val requiresCertification = bool("requires_certification").default(false)  // Cần chứng nhận CE/ISO/FDA
    val requiresConsultation = bool("requires_consultation").default(false)  // Cần tư vấn kỹ thuật trước khi sử dụng
    val isActive           = bool("is_active").default(true)
    val isFlashSale        = bool("is_flash_sale").default(false)
    val isBestSeller       = bool("is_best_seller").default(false)
    val flashSaleEnd       = datetime("flash_sale_end").nullable()
    val createdAt          = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt          = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object ProductImagesTable : Table("product_images") {
    val id        = varchar("id", 36)
    val productId = varchar("product_id", 36).references(ProductsTable.id)
    val url       = text("url")
    val sortOrder = integer("sort_order").default(0)
    override val primaryKey = PrimaryKey(id)
}

// Giấy tờ công bố / chứng nhận sản phẩm
object ProductCertificatesTable : Table("product_certificates") {
    val id          = varchar("id", 36)
    val productId   = varchar("product_id", 36).references(ProductsTable.id)
    // Công bố chất lượng | Giấy phép lưu hành | Giấy kiểm nghiệm | Chứng nhận ISO | Khác
    val type        = varchar("type", 100)
    val name        = varchar("name", 200)  // Tên giấy tờ
    val fileUrl     = text("file_url")      // Cloudinary URL (ảnh/PDF)
    val issueDate   = varchar("issue_date", 20).nullable()
    val expireDate  = varchar("expire_date", 20).nullable()
    val issuer      = varchar("issuer", 200).nullable()  // Cơ quan cấp
    val createdAt   = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}



// Junction table for products and disease categories
object ProductDiseasesTable : Table("product_diseases") {
    val productId = varchar("product_id", 36).references(ProductsTable.id)
    val diseaseId = varchar("disease_id", 36).references(DiseaseCategoriesTable.id)
    override val primaryKey = PrimaryKey(productId, diseaseId)
}

// ─────────────────────────────────────────────────────────────
// CART & ORDERS
// ─────────────────────────────────────────────────────────────

object CartItemsTable : Table("cart_items") {
    val id        = varchar("id", 36)
    val userId    = varchar("user_id", 36).references(UsersTable.id)
    val productId = varchar("product_id", 36).references(ProductsTable.id)
    val quantity  = integer("quantity").default(1)
    val unit      = varchar("unit", 50).default("Hộp")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object UserAddressesTable : Table("user_addresses") {
    val id            = varchar("id", 36)
    val userId        = varchar("user_id", 36).references(UsersTable.id)
    val label         = varchar("label", 50).nullable()   // Nhà, Cơ quan
    val recipientName = varchar("recipient_name", 100).nullable()
    val phone         = varchar("phone", 15).nullable()
    val address       = text("address")
    val ward          = varchar("ward", 100).nullable()
    val district      = varchar("district", 100).nullable()
    val province      = varchar("province", 100).nullable()
    val isDefault     = bool("is_default").default(false)
    override val primaryKey = PrimaryKey(id)
}

object OrdersTable : Table("orders") {
    val id            = varchar("id", 36)
    val orderCode     = varchar("order_code", 20).uniqueIndex()
    val userId        = varchar("user_id", 36).references(UsersTable.id)
    val shopId        = varchar("shop_id", 36).references(ShopsTable.id)
    val addressId     = varchar("address_id", 36).references(UserAddressesTable.id).nullable()
    // PENDING | PROCESSING | SHIPPING | DELIVERED | CANCELLED | RETURNED
    val status        = varchar("status", 30).default("PENDING")
    val pickupType    = varchar("pickup_type", 30).default("DELIVERY") // DELIVERY | PICKUP
    val branchId      = varchar("branch_id", 36).references(PharmacyBranchesTable.id).nullable()
    val subtotal      = decimal("subtotal", 12, 2).nullable()
    val shippingFee   = decimal("shipping_fee", 12, 2).default(0.toBigDecimal())
    val discount      = decimal("discount", 12, 2).default(0.toBigDecimal())
    val pointsUsed    = integer("points_used").default(0)
    val pointsEarned  = integer("points_earned").default(0)
    val total         = decimal("total", 12, 2).nullable()
    val paymentMethod = varchar("payment_method", 30).nullable() // COD | MOMO | CARD
    val paymentStatus = varchar("payment_status", 20).default("UNPAID")
    val note          = text("note").nullable()
    val createdAt     = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt     = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object OrderItemsTable : Table("order_items") {
    val id        = varchar("id", 36)
    val orderId   = varchar("order_id", 36).references(OrdersTable.id)
    val productId = varchar("product_id", 36).references(ProductsTable.id).nullable()
    val name      = varchar("name", 300)         // snapshot tên tại thời điểm đặt
    val price     = decimal("price", 12, 2)
    val quantity  = integer("quantity")
    val unit      = varchar("unit", 50)
    override val primaryKey = PrimaryKey(id)
}


// ─────────────────────────────────────────────────────────────
// REWARDS
// ─────────────────────────────────────────────────────────────

object RewardAccountsTable : Table("reward_accounts") {
    val id           = varchar("id", 36)
    val userId       = varchar("user_id", 36).uniqueIndex().references(UsersTable.id)
    val totalPoints  = integer("total_points").default(0)
    val usedPoints   = integer("used_points").default(0)
    override val primaryKey = PrimaryKey(id)
}

object RewardTransactionsTable : Table("reward_transactions") {
    val id          = varchar("id", 36)
    val userId      = varchar("user_id", 36).references(UsersTable.id)
    val orderId     = varchar("order_id", 36).references(OrdersTable.id).nullable()
    val type        = varchar("type", 20)  // EARN | REDEEM | EXPIRE | ADJUST
    val points      = integer("points")    // >0 = cộng, <0 = trừ
    val description = text("description").nullable()
    val createdAt   = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object RewardProductsTable : Table("reward_products") {
    val id        = varchar("id", 36)
    val name      = varchar("name", 300)
    val imageUrl  = text("image_url").nullable()
    val pointCost = integer("point_cost")
    val priceText = varchar("price_text", 50).nullable()
    val stock     = integer("stock").default(0)
    val isActive  = bool("is_active").default(true)
    override val primaryKey = PrimaryKey(id)
}

object RewardRedemptionsTable : Table("reward_redemptions") {
    val id              = varchar("id", 36)
    val userId          = varchar("user_id", 36).references(UsersTable.id)
    val rewardProductId = varchar("reward_product_id", 36).references(RewardProductsTable.id)
    val quantity        = integer("quantity").default(1)
    val pointsUsed      = integer("points_used")
    val status          = varchar("status", 20).default("PROCESSING")
    val createdAt       = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

// ─────────────────────────────────────────────────────────────
// PRESCRIPTIONS
// ─────────────────────────────────────────────────────────────

object PrescriptionsTable : Table("prescriptions") {
    val id                   = varchar("id", 36)
    val userId               = varchar("user_id", 36).references(UsersTable.id)
    val orderId              = varchar("order_id", 36).references(OrdersTable.id).nullable()
    val imageUrl             = text("image_url")
    val note                 = text("note").nullable()
    val status               = varchar("status", 20).default("PENDING")
    val cloudinaryPublicId   = varchar("cloudinary_public_id", 255).nullable()
    val createdAt            = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

// ─────────────────────────────────────────────────────────────
// AI CONVERSATIONS
// ─────────────────────────────────────────────────────────────

object AiConversationsTable : Table("ai_conversations") {
    val id                       = varchar("id", 36)
    val chatSessionId            = varchar("chat_session_id", 36).references(ChatSessionsTable.id).nullable()
    val userId                   = varchar("user_id", 36).references(UsersTable.id)
    val shopId                   = varchar("shop_id", 36).references(ShopsTable.id)
    val productId                = varchar("product_id", 36).references(ProductsTable.id).nullable()
    val conversationHistory      = text("conversation_history").nullable() // JSON as TEXT
    val intent                   = varchar("intent", 100).nullable()
    val sentiment                = varchar("sentiment", 20).nullable()
    val status                   = varchar("status", 20).default("ACTIVE")
    val escalatedToConsultant    = bool("escalated_to_consultant").default(false)
    val consultantId             = varchar("consultant_id", 36).references(UsersTable.id).nullable()
    val createdAt                = datetime("created_at").defaultExpression(CurrentDateTime)
    val closedAt                 = datetime("closed_at").nullable()
    val updatedAt                = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

// ─────────────────────────────────────────────────────────────
// CHAT
// ─────────────────────────────────────────────────────────────

object ChatSessionsTable : Table("chat_sessions") {
    val id        = varchar("id", 36)
    val userId    = varchar("user_id", 36).references(UsersTable.id)
    val shopId    = varchar("shop_id", 36).references(ShopsTable.id)
    val productId = varchar("product_id", 36).references(ProductsTable.id).nullable()
    val status    = varchar("status", 20).default("OPEN") // OPEN | CLOSED
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object ChatMessagesTable : Table("chat_messages") {
    val id        = varchar("id", 36)
    val sessionId = varchar("session_id", 36).references(ChatSessionsTable.id)
    val senderId  = varchar("sender_id", 36).references(UsersTable.id)
    val content   = text("content").nullable()
    val type      = varchar("type", 20).default("TEXT") // TEXT | IMAGE | PRODUCT_CARD
    val metadata  = text("metadata").nullable()          // JSON string
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

// ─────────────────────────────────────────────────────────────
// REVIEWS & NOTIFICATIONS
// ─────────────────────────────────────────────────────────────

object ReviewsTable : Table("reviews") {
    val id        = varchar("id", 36)
    val productId = varchar("product_id", 36).references(ProductsTable.id)
    val userId    = varchar("user_id", 36).references(UsersTable.id)
    val orderId   = varchar("order_id", 36).references(OrdersTable.id).nullable()
    val rating    = integer("rating")  // 1-5
    val comment   = text("comment").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object NotificationsTable : Table("notifications") {
    val id        = varchar("id", 36)
    val userId    = varchar("user_id", 36).references(UsersTable.id)
    val title     = varchar("title", 200)
    val body      = text("body").nullable()
    // ORDER_STATUS | PROMOTION | CHAT | REWARD | SYSTEM
    val type      = varchar("type", 30)
    val refId     = varchar("ref_id", 36).nullable()
    val isRead    = bool("is_read").default(false)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object BannersTable : Table("banners") {
    val id        = varchar("id", 36)
    val imageUrl  = text("image_url")
    val linkUrl   = text("link_url").nullable()
    val title     = varchar("title", 200).nullable()
    val sortOrder = integer("sort_order").default(0)
    val isActive  = bool("is_active").default(true)
    val startDt   = datetime("start_dt").nullable()
    val endDt     = datetime("end_dt").nullable()
    override val primaryKey = PrimaryKey(id)
}

object HealthArticlesTable : Table("health_articles") {
    val id           = varchar("id", 36)
    val title        = varchar("title", 300)
    val content      = text("content").nullable()
    val thumbnailUrl = text("thumbnail_url").nullable()
    val author       = varchar("author", 100).nullable()
    val category     = varchar("category", 100).nullable()
    val isPublished  = bool("is_published").default(false)
    val publishedAt  = datetime("published_at").nullable()
    val createdAt    = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt    = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object DiseaseCategoriesTable : Table("disease_categories") {
    val id          = varchar("id", 36)
    val name        = varchar("name", 100)
    val description = text("description").nullable()
    val iconUrl     = text("icon_url").nullable()
    val sortOrder   = integer("sort_order").default(0)
    val isActive    = bool("is_active").default(true)
    val createdAt   = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt   = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object AiChatbotSettingsTable : Table("ai_chatbot_settings") {
    val id           = varchar("id", 36)
    val shopId       = varchar("shop_id", 36).references(ShopsTable.id)
    val aiProvider   = varchar("ai_provider", 50)  // openai, anthropic, etc.
    val modelName    = varchar("model_name", 100)
    val temperature  = decimal("temperature", 3, 2)
    val maxTokens    = integer("max_tokens")
    val systemPrompt = text("system_prompt").nullable()
    val isActive     = bool("is_active").default(true)
    val createdAt    = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt    = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

// ─────────────────────────────────────────────────────────────
// PAYMENTS
// ─────────────────────────────────────────────────────────────

object PaymentMethodsTable : Table("payment_methods") {
    val id        = varchar("id", 36)
    val userId    = varchar("user_id", 36).references(UsersTable.id)
    val type      = varchar("type", 20)       // MOMO | CARD | BANK_TRANSFER
    val label     = varchar("label", 100).nullable()
    val last4     = varchar("last4", 4).nullable()
    val isDefault = bool("is_default").default(false)
    override val primaryKey = PrimaryKey(id)
}

object PaymentsTable : Table("payments") {
    val id            = varchar("id", 36)
    val orderId       = varchar("order_id", 36).references(OrdersTable.id)
    val method        = varchar("method", 20)
    val amount        = decimal("amount", 12, 2)
    val transactionId = varchar("transaction_id", 200).nullable()
    val status        = varchar("status", 20).default("PENDING")
    val paidAt        = datetime("paid_at").nullable()
    val createdAt     = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

// ─────────────────────────────────────────────────────────────
// DYNAMIC SCHEMA FOR CATEGORIES
// ─────────────────────────────────────────────────────────────

object CategoryAttributesTable : Table("category_attributes") {
    val id           = varchar("id", 36)
    val categoryId   = varchar("category_id", 36).references(CategoriesTable.id)
    val attrKey      = varchar("attr_key", 100)
    val label        = varchar("label", 200)
    val description  = text("description").nullable()
    val dataType     = varchar("data_type", 30)  // text, textarea, number, boolean, date, select, multiselect
    val unit         = varchar("unit", 50).nullable()
    val isRequired   = bool("is_required").default(false)  // Match DB column name exactly
    val isSearchable = bool("is_searchable").default(false)
    val sortOrder    = integer("sort_order").default(0)
    val optionsJson  = text("options_json").nullable()  // DB has JSON type but Exposed treats as TEXT
    val createdAt    = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt    = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(categoryId, attrKey)
    }
}

object ProductAttributeValuesTable : Table("product_attribute_values") {
    val id           = varchar("id", 36)
    val productId    = varchar("product_id", 36).references(ProductsTable.id)
    val attributeId  = varchar("attribute_id", 36).references(CategoryAttributesTable.id)
    val valueText    = text("value_text").nullable()
    val valueNumber  = decimal("value_number", 18, 6).nullable()  // Match DB: decimal(18,6)
    val valueBool    = bool("value_bool").nullable()  // Match DB column name exactly
    val valueDate    = date("value_date").nullable()
    val valueJson    = text("value_json").nullable()  // Added from DB schema
    val createdAt    = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt    = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(productId, attributeId)
    }
}

// ─────────────────────────────────────────────────────────────
// INVENTORY BATCHES
// ─────────────────────────────────────────────────────────────

object ProductBatchesTable : Table("product_batches") {
    val id              = varchar("id", 36)
    val productId       = varchar("product_id", 36).references(ProductsTable.id)
    // Note: shopId removed - not in actual DB schema, calculate from productId when needed
    val lotNumber       = varchar("lot_number", 100).nullable()
    val mfgDate         = date("mfg_date").nullable()
    val expDate         = date("exp_date").nullable()
    val quantityOnHand  = integer("quantity_on_hand").default(0)
    val importPrice     = decimal("import_price", 12, 2).nullable()
    val note            = text("note").nullable()
    val createdAt       = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt       = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object OrderItemBatchesTable : Table("order_item_batches") {
    val id           = varchar("id", 36)
    val orderItemId  = varchar("order_item_id", 36).references(OrderItemsTable.id)
    val batchId      = varchar("batch_id", 36).references(ProductBatchesTable.id)
    val quantity     = integer("quantity")
    val createdAt    = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(orderItemId, batchId)
    }
}

// ─────────────────────────────────────────────────────────────
// MEDICAL CONSULTATION SYSTEM
// ─────────────────────────────────────────────────────────────

object DoctorsTable : Table("doctors") {
    val id                  = varchar("id", 36)
    val name                = varchar("name", 100)
    val specialization      = varchar("specialization", 100)     // Nhi khoa, Tim mạch, Chuyên khoa, etc
    val yearsOfExperience   = integer("years_of_experience").default(0)
    val bio                 = text("bio").nullable()
    val avatarUrl           = varchar("avatar_url", 500).nullable()
    val phone               = varchar("phone", 20).nullable()
    val email               = varchar("email", 100).nullable()
    val licenseNumber       = varchar("license_number", 100).nullable()
    val qualificationsJson  = text("qualifications_json").nullable()  // JSON array
    val isActive            = bool("is_active").default(true)
    val consultationFee     = decimal("consultation_fee", 10, 2).default(50000.toBigDecimal())
    val averageRating       = decimal("average_rating", 3, 1).default(0.toBigDecimal())
    val totalConsultations  = integer("total_consultations").default(0)
    val createdAt           = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt           = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object ConsultationsTable : Table("consultations") {
    val id                  = varchar("id", 36)
    val consultationCode    = varchar("consultation_code", 20).uniqueIndex()
    val doctorId            = varchar("doctor_id", 36).references(DoctorsTable.id)
    val userId              = varchar("user_id", 36).references(UsersTable.id)
    val scheduledAt         = datetime("scheduled_at")
    val startedAt           = datetime("started_at").nullable()
    val endedAt             = datetime("ended_at").nullable()
    val status              = varchar("status", 20).default("SCHEDULED")  // SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED
    val sessionType         = varchar("session_type", 20).default("VIDEO")  // VIDEO, CHAT, AUDIO
    val videoCallUrl        = varchar("video_call_url", 500).nullable()   // Jitsi or Agora link
    val videoCallType       = varchar("video_call_type", 20).nullable()    // JITSI, AGORA, etc
    val notes               = text("notes").nullable()
    val doctorNotes         = text("doctor_notes").nullable()
    val duration            = integer("duration").nullable()              // minutes
    val fee                 = decimal("fee", 10, 2)
    val paymentStatus       = varchar("payment_status", 20).default("UNPAID")  // UNPAID, PAID, REFUNDED
    val orderId             = varchar("order_id", 36).references(OrdersTable.id).nullable()  // Link to order if paid
    val cancellationReason  = text("cancellation_reason").nullable()
    val createdAt           = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt           = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object ConsultationRatingsTable : Table("consultation_ratings") {
    val id                  = varchar("id", 36)
    val consultationId      = varchar("consultation_id", 36).references(ConsultationsTable.id)
    val userId              = varchar("user_id", 36).references(UsersTable.id)
    val doctorId            = varchar("doctor_id", 36).references(DoctorsTable.id)
    val rating              = integer("rating")                    // 1-5 stars
    val comment             = text("comment").nullable()
    val createdAt           = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(consultationId)  // One rating per consultation
    }
}

object ConsultationMessagesTable : Table("consultation_messages") {
    val id                  = varchar("id", 36)
    val consultationId      = varchar("consultation_id", 36).references(ConsultationsTable.id)
    val senderId            = varchar("sender_id", 36).references(UsersTable.id)  // Either doctor or patient
    val messageType         = varchar("message_type", 20).default("TEXT")         // TEXT, IMAGE, PRESCRIPTION, FILE
    val message             = text("message")
    val imageUrl            = varchar("image_url", 500).nullable()
    val fileUrl             = varchar("file_url", 500).nullable()
    val prescriptionId      = varchar("prescription_id", 36).nullable()  // Reference to external prescription if any
    val createdAt           = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}
