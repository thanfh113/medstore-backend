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
    val role        = varchar("role", 20).default("USER") // ADMIN | EMPLOYEE | USER
    val isActive             = bool("is_active").default(true)
    val failedLoginAttempts  = integer("failed_login_attempts").default(0)
    val lastLoginAt          = datetime("last_login_at").nullable()
    val fcmToken    = text("fcm_token").nullable()
    val fcmPlatform = varchar("fcm_platform", 20).nullable()
    val fcmUpdatedAt = datetime("fcm_updated_at").nullable()
    val createdAt   = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt   = datetime("updated_at").defaultExpression(CurrentDateTime)
    val deletedAt   = datetime("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object RefreshTokensTable : Table("refresh_tokens") {
    val id         = varchar("id", 36)
    val userId     = varchar("user_id", 36).references(UsersTable.id)
    val tokenHash  = varchar("token_hash", 64).nullable()
    val expiresAt  = datetime("expires_at")
    val revokedAt  = datetime("revoked_at").nullable()
    val createdAt  = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

// ─────────────────────────────────────────────────────────────
// EMPLOYEE PROFILES
// ─────────────────────────────────────────────────────────────

object EmployeeProfilesTable : Table("employee_profiles") {
    val id                            = varchar("id", 36)
    val userId                        = varchar("user_id", 36).references(UsersTable.id).uniqueIndex()
    val qualificationTitle            = varchar("qualification_title", 255)
    val qualificationInstitution      = varchar("qualification_institution", 255).nullable()
    val qualificationDocumentUrl      = text("qualification_document_url").nullable()
    val qualificationDocumentPublicId = varchar("qualification_document_public_id", 255).nullable()
    val qualificationDocumentType     = varchar("qualification_document_type", 20).nullable()
    val qualificationDocumentResourceType = varchar("qualification_document_resource_type", 20).nullable()
    val qualificationVerified         = bool("qualification_verified").default(false)
    val qualificationSubmittedAt      = datetime("qualification_submitted_at").nullable()
    val qualificationVerifiedBy       = varchar("qualification_verified_by", 36).references(UsersTable.id).nullable()
    val qualificationVerifiedAt       = datetime("qualification_verified_at").nullable()
    val qualificationNote             = text("qualification_note").nullable()
    val createdAt                     = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt                     = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, qualificationVerified)
    }
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
    val categoryId         = varchar("category_id", 36).references(CategoriesTable.id).nullable()
    val name               = varchar("name", 300)
    val slug               = varchar("slug", 300).uniqueIndex().nullable()
    val shortDescription   = varchar("short_description", 500).nullable()
    val description        = text("description").nullable()
    val brand              = varchar("brand", 100).nullable()
    val manufacturer       = varchar("manufacturer", 200).nullable()
    val origin             = varchar("origin", 100).nullable()
    val sku                = varchar("sku", 100).uniqueIndex().nullable()
    val unit               = varchar("unit", 50).default("Hộp")
    val price              = decimal("price", 15, 0)  // Match DB: decimal(15,0)
    val originalPrice      = decimal("original_price", 15, 0).nullable() // Match DB
    val importPrice        = decimal("import_price", 12, 2).nullable()
    val discountPct        = integer("discount_pct").default(0)
    val rewardPoints       = integer("reward_points").default(0)
    val stock              = integer("stock").default(0)
    val inventoryNote      = text("inventory_note").nullable()
    val mfgDate            = date("mfg_date").nullable()
    val expDate            = date("exp_date").nullable()
    // Số đăng ký lưu hành (ví dụ: CE Mark, FDA 510(k), ISO 13485)
    val registrationNumber = varchar("registration_number", 100).nullable()
    val riskClassification = varchar("risk_classification", 1).default("A")  // Phân loại A|B|C|D theo TTBYT
    val contactForPrice    = bool("contact_for_price").default(false)         // Hiển thị "Liên hệ" thay vì giá
    val isActive           = bool("is_active").default(true)
    val createdAt          = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt          = datetime("updated_at").defaultExpression(CurrentDateTime)
    val deletedAt          = datetime("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ProductImagesTable : Table("product_images") {
    val id        = varchar("id", 36)
    val productId = varchar("product_id", 36).references(ProductsTable.id)
    val url       = text("url")
    val mediaType = varchar("media_type", 20).default("IMAGE")
    val cloudinaryPublicId = varchar("cloudinary_public_id", 255).nullable()
    val thumbnailUrl = text("thumbnail_url").nullable()
    val sortOrder = integer("sort_order").default(0)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
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
    val fileType    = varchar("file_type", 20).default("IMAGE")
    val cloudinaryPublicId = varchar("cloudinary_public_id", 255).nullable()
    val cloudinaryResourceType = varchar("cloudinary_resource_type", 20).default("image")
    val thumbnailUrl = text("thumbnail_url").nullable()
    val issuer      = varchar("issuer", 200).nullable()  // Cơ quan cấp
    val isActive    = bool("is_active").default(true)
    val createdAt   = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt   = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, fileType)
        index(false, isActive)
    }
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
    val fullAddress   = text("full_address").nullable()
    val ward          = varchar("ward", 100).nullable()
    val wardCode      = varchar("ward_code", 20).nullable()
    val district      = varchar("district", 100).nullable()
    val province      = varchar("province", 100).nullable()
    val provinceCode  = varchar("province_code", 20).nullable()
    val latitude      = decimal("latitude", 10, 8).nullable()
    val longitude     = decimal("longitude", 11, 8).nullable()
    val locationSource = varchar("location_source", 20).default("MANUAL")
    val isDefault     = bool("is_default").default(false)
    val isDeleted     = bool("is_deleted").default(false)
    override val primaryKey = PrimaryKey(id)
}

object OrdersTable : Table("orders") {
    val id            = varchar("id", 36)
    val orderCode     = varchar("order_code", 20).uniqueIndex()
    val userId        = varchar("user_id", 36).references(UsersTable.id).nullable()
    val orderChannel  = varchar("order_channel", 20).default("ONLINE") // ONLINE | POS
    val addressId     = varchar("address_id", 36).references(UserAddressesTable.id).nullable()
    val shippingRecipientName = varchar("shipping_recipient_name", 100).nullable()
    val shippingPhone = varchar("shipping_phone", 20).nullable()
    val shippingAddressText = text("shipping_address_text").nullable()
    val shippingProvinceCode = varchar("shipping_province_code", 20).nullable()
    val shippingProvinceName = varchar("shipping_province_name", 100).nullable()
    val shippingWardCode = varchar("shipping_ward_code", 20).nullable()
    val shippingWardName = varchar("shipping_ward_name", 100).nullable()
    val shippingLatitude = decimal("shipping_latitude", 10, 8).nullable()
    val shippingLongitude = decimal("shipping_longitude", 11, 8).nullable()
    val shippingZone = varchar("shipping_zone", 30).nullable()
    // PENDING | PROCESSING | SHIPPING | DELIVERED | CANCELLED | RETURNED
    val status        = varchar("status", 30).default("PENDING")
    val pickupType    = varchar("pickup_type", 30).default("DELIVERY") // DELIVERY | PICKUP
    val branchId      = varchar("branch_id", 36).nullable()
    val cashierUserId = varchar("cashier_user_id", 36).references(UsersTable.id).nullable()
    val subtotal      = decimal("subtotal", 12, 2).nullable()
    val shippingFee   = decimal("shipping_fee", 12, 2).default(0.toBigDecimal())
    val discount      = decimal("discount", 12, 2).default(0.toBigDecimal())
    val couponId      = varchar("coupon_id", 36).nullable()
    val pointsUsed    = integer("points_used").default(0)
    val pointsEarned  = integer("points_earned").default(0)
    val total         = decimal("total", 12, 2).nullable()
    val cashReceived  = decimal("cash_received", 12, 2).nullable()
    val cashChange    = decimal("cash_change", 12, 2).nullable()
    val paymentMethod = varchar("payment_method", 30).nullable() // COD | MOMO | CARD
    val paymentStatus = varchar("payment_status", 20).default("UNPAID")
    val note          = text("note").nullable()
    val createdAt     = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt     = datetime("updated_at").defaultExpression(CurrentDateTime)
    val completedAt   = datetime("completed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object CouponsTable : Table("coupons") {
    val id                = varchar("id", 36)
    val code              = varchar("code", 50).uniqueIndex()
    val name              = varchar("name", 200)
    val description       = text("description").nullable()
    val discountType      = varchar("discount_type", 20) // PERCENT | FIXED_AMOUNT
    val discountValue     = decimal("discount_value", 12, 2)
    val minOrderTotal     = decimal("min_order_total", 12, 2).nullable()
    val maxDiscountAmount = decimal("max_discount_amount", 12, 2).nullable()
    val startsAt          = datetime("starts_at").nullable()
    val endsAt            = datetime("ends_at").nullable()
    val usageLimit        = integer("usage_limit").nullable()
    val usagePerUserLimit = integer("usage_per_user_limit").nullable()
    val usedCount         = integer("used_count").default(0)
    val isActive          = bool("is_active").default(true)
    val isRewardVoucherTemplate = bool("is_reward_voucher_template").default(false)
    val createdByUserId   = varchar("created_by_user_id", 36).references(UsersTable.id).nullable()
    val createdAt         = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt         = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

object CouponRedemptionsTable : Table("coupon_redemptions") {
    val id                    = varchar("id", 36)
    val couponId              = varchar("coupon_id", 36).references(CouponsTable.id)
    val orderId               = varchar("order_id", 36).references(OrdersTable.id)
    val userId                = varchar("user_id", 36).references(UsersTable.id).nullable()
    val appliedDiscountAmount = decimal("applied_discount_amount", 12, 2)
    val status                = varchar("status", 20).default("APPLIED") // APPLIED | REVERTED
    val appliedAt             = datetime("applied_at").defaultExpression(CurrentDateTime)
    val revertedAt            = datetime("reverted_at").nullable()
    val createdAt             = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(orderId)
    }
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
    val refType     = varchar("ref_type", 30).nullable()
    val refId       = varchar("ref_id", 36).nullable()
    val type        = varchar("type", 20)  // EARN | REDEEM | EXPIRE | ADJUST
    val points      = integer("points")    // >0 = cộng, <0 = trừ
    val description = text("description").nullable()
    val createdBy   = varchar("created_by", 36).references(UsersTable.id).nullable()
    val metadata    = text("metadata").nullable()
    val createdAt   = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, refType, refId)
        index(false, createdBy)
    }
}

object RewardProductsTable : Table("reward_products") {
    val id        = varchar("id", 36)
    val name      = varchar("name", 300)
    val description = text("description").nullable()
    val imageUrl  = text("image_url").nullable()
    val pointCost = integer("point_cost")
    val priceText = varchar("price_text", 50).nullable()
    val category  = varchar("category", 50).nullable()
    val rewardType = varchar("reward_type", 20).default("ITEM")
    val couponId   = varchar("coupon_id", 36).references(CouponsTable.id).nullable()
    val terms     = text("terms").nullable()
    val stock     = integer("stock").default(0)
    val isActive  = bool("is_active").default(true)
    val sortOrder = integer("sort_order").default(0)
    val usagePerUserLimit = integer("usage_per_user_limit").nullable()
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, isActive, pointCost)
        index(false, category)
    }
}

object RewardRedemptionsTable : Table("reward_redemptions") {
    val id              = varchar("id", 36)
    val userId          = varchar("user_id", 36).references(UsersTable.id)
    val rewardProductId = varchar("reward_product_id", 36).references(RewardProductsTable.id)
    val quantity        = integer("quantity").default(1)
    val pointsUsed      = integer("points_used")
    val status          = varchar("status", 20).default("PROCESSING")
    val issuedVoucherCode = varchar("issued_voucher_code", 50).nullable()
    val voucherIssuedAt = datetime("voucher_issued_at").nullable()
    val voucherUsedAt   = datetime("voucher_used_at").nullable()
    val redeemedOrderId = varchar("redeemed_order_id", 36).references(OrdersTable.id).nullable()
    val assignedTo      = varchar("assigned_to", 36).references(UsersTable.id).nullable()
    val handledBy       = varchar("handled_by", 36).references(UsersTable.id).nullable()
    val handledAt       = datetime("handled_at").nullable()
    val note            = text("note").nullable()
    val createdAt       = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt       = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, status)
        index(false, assignedTo)
        index(false, handledBy)
    }
}

// ─────────────────────────────────────────────────────────────
// AI CONVERSATIONS
// ─────────────────────────────────────────────────────────────

object AiConversationsTable : Table("ai_conversations") {
    val id                       = varchar("id", 36)
    val chatSessionId            = varchar("chat_session_id", 36).references(ChatSessionsTable.id).nullable()
    val userId                   = varchar("user_id", 36).references(UsersTable.id)
    val productId                = varchar("product_id", 36).references(ProductsTable.id).nullable()
    val conversationHistory      = text("conversation_history").nullable() // JSON as TEXT
    val status                   = varchar("status", 20).default("ACTIVE")
    val escalatedToConsultant    = bool("escalated_to_consultant").default(false)
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
    val productId = varchar("product_id", 36).references(ProductsTable.id).nullable()
    val status    = varchar("status", 20).default("PENDING") // PENDING | ASSIGNED | RESOLVED
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
    val orderItemId = varchar("order_item_id", 36).references(OrderItemsTable.id).nullable()
    val rating    = integer("rating")  // 1-5
    val title     = varchar("title", 150).nullable()
    val comment   = text("comment").nullable()
    val attachmentsJson = text("attachments_json").nullable()
    val status    = varchar("status", 20).default("VISIBLE")
    val isVerifiedPurchase = bool("is_verified_purchase").default(false)
    val helpfulCount = integer("helpful_count").default(0)
    val reportCount  = integer("report_count").default(0)
    val hiddenReason = text("hidden_reason").nullable()
    val moderatedBy  = varchar("moderated_by", 36).references(UsersTable.id).nullable()
    val moderatedAt  = datetime("moderated_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    val deletedAt = datetime("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(userId, orderId, productId)
        index(false, orderId)
        index(false, orderItemId)
        index(false, productId, status)
        index(false, createdAt)
        index(false, moderatedBy)
    }
}

object ReviewReportsTable : Table("review_reports") {
    val id             = varchar("id", 36)
    val reviewId       = varchar("review_id", 36).references(ReviewsTable.id)
    val reporterUserId = varchar("reporter_user_id", 36).references(UsersTable.id)
    val reason         = varchar("reason", 50)
    val note           = text("note").nullable()
    val status         = varchar("status", 20).default("OPEN")
    val handledBy      = varchar("handled_by", 36).references(UsersTable.id).nullable()
    val handledAt      = datetime("handled_at").nullable()
    val createdAt      = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt      = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(reviewId, reporterUserId)
        index(false, status)
        index(false, reporterUserId)
        index(false, handledBy)
    }
}

object OrderComplaintsTable : Table("order_complaints") {
    val id            = varchar("id", 36)
    val complaintCode = varchar("complaint_code", 30).uniqueIndex()
    val userId        = varchar("user_id", 36).references(UsersTable.id)
    val orderId       = varchar("order_id", 36).references(OrdersTable.id)
    val orderItemId   = varchar("order_item_id", 36).references(OrderItemsTable.id).nullable()
    val productId     = varchar("product_id", 36).references(ProductsTable.id).nullable()
    val type          = varchar("type", 50)
    val title         = varchar("title", 200)
    val description   = text("description")
    val attachmentsJson = text("attachments_json").nullable()
    val messagesJson  = text("messages_json").nullable()
    val eventsJson    = text("events_json").nullable()
    val status        = varchar("status", 30).default("OPEN")
    val priority      = varchar("priority", 20).default("NORMAL")
    val resolution    = text("resolution").nullable()
    val refundAmount  = decimal("refund_amount", 12, 2).nullable()
    val refundStatus  = varchar("refund_status", 30).default("NONE")
    val refundMethod  = varchar("refund_method", 30).nullable()
    val refundTransactionId = varchar("refund_transaction_id", 100).nullable()
    val refundedBy    = varchar("refunded_by", 36).references(UsersTable.id).nullable()
    val refundedAt    = datetime("refunded_at").nullable()
    val restoreStock  = bool("restore_stock").default(false)
    val handledBy     = varchar("handled_by", 36).references(UsersTable.id).nullable()
    val createdAt     = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt     = datetime("updated_at").defaultExpression(CurrentDateTime)
    val resolvedAt    = datetime("resolved_at").nullable()
    val closedAt      = datetime("closed_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId)
        index(false, orderId)
        index(false, orderItemId)
        index(false, productId)
        index(false, status)
        index(false, refundStatus)
        index(false, refundedBy)
        index(false, handledBy)
    }
}

object NotificationsTable : Table("notifications") {
    val id        = varchar("id", 36)
    val userId    = varchar("user_id", 36).references(UsersTable.id)
    val title     = varchar("title", 200)
    val body      = text("body").nullable()
    // ORDER | PROMOTION | CHAT | REWARD | COMPLAINT | REFUND | REVIEW | SYSTEM
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
    val description = text("description").nullable()
    val sortOrder = integer("sort_order").default(0)
    val isActive  = bool("is_active").default(true)
    val startDt   = datetime("start_dt").nullable()
    val endDt     = datetime("end_dt").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

// ─────────────────────────────────────────────────────────────
// PAYMENTS
// ─────────────────────────────────────────────────────────────

object PaymentsTable : Table("payments") {
    val id            = varchar("id", 36)
    val orderId       = varchar("order_id", 36).references(OrdersTable.id)
    val method        = varchar("method", 20)
    val amount        = decimal("amount", 12, 2)
    val transactionId = varchar("transaction_id", 200).nullable()
    val paymentGatewayResponse = text("payment_gateway_response").nullable()
    val status        = varchar("status", 20).default("PENDING")
    val paidAt        = datetime("paid_at").nullable()
    val createdAt     = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}

// ─────────────────────────────────────────────────────────────
// PASSWORD RESET
// ─────────────────────────────────────────────────────────────

object PasswordResetTokensTable : Table("password_reset_tokens") {
    val id        = varchar("id", 36)
    val userId    = varchar("user_id", 36).references(UsersTable.id)
    val otp       = varchar("otp", 6)
    val expiresAt = datetime("expires_at")
    val usedAt    = datetime("used_at").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId)
    }
}


