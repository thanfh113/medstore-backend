package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.AiConversationsTable
import com.example.nhathuoc.database.tables.CategoriesTable
import com.example.nhathuoc.database.tables.ProductImagesTable
import com.example.nhathuoc.database.tables.ProductsTable
import com.example.nhathuoc.util.Env
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.text.Normalizer
import java.util.UUID

// ── Gemini REST models ────────────────────────────────────────────────────────

@Serializable
data class GeminiPart(val text: String)

@Serializable
data class GeminiContent(val role: String, val parts: List<GeminiPart>)

@Serializable
data class GeminiSystemInstruction(val parts: List<GeminiPart>)

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerialName("system_instruction") val systemInstruction: GeminiSystemInstruction? = null
)

@Serializable
data class GeminiCandidate(val content: GeminiContent)

@Serializable
data class GeminiResponse(val candidates: List<GeminiCandidate> = emptyList())

@Serializable
private data class AiStoredMessage(
    val role: String,
    val text: String,
    val recommendations: List<AiProductRecommendationDto> = emptyList()
)

private data class AiProductContext(
    val id: String,
    val name: String,
    val categoryId: String?,
    val categoryName: String?,
    val shortDescription: String?,
    val brand: String?,
    val imageUrl: String?,
    val unit: String,
    val price: Double,
    val priceText: String,
    val stock: Int
)

private enum class SearchMode { NORMAL, ALTERNATIVE, CHEAPER, EXPENSIVE, MIDRANGE }

// ── DTOs returned to client ───────────────────────────────────────────────────

@Serializable
data class AiMessageDto(
    val role: String,   // "user" | "ai"
    val text: String,
    val recommendations: List<AiProductRecommendationDto> = emptyList()
)

@Serializable
data class AiProductRecommendationDto(
    val productId: String,
    val productName: String,
    val productImage: String? = null,
    val price: Double,
    val productUnit: String? = null,
    val categoryId: String? = null,
    val description: String = "",
    val reason: String = ""
)

@Serializable
data class AiConversationDto(
    val id: String,
    val status: String,
    val escalatedToConsultant: Boolean,
    val chatSessionId: String? = null,
    val createdAt: String? = null,
    val messages: List<AiMessageDto>
)

@Serializable
data class CreateAiConversationRequest(
    val productId: String? = null
)

@Serializable
data class AiSendMessageRequest(
    val message: String
)

@Serializable
data class AiSendMessageResponse(
    val reply: String,
    val conversationId: String,
    val status: String,
    val escalatedToConsultant: Boolean,
    val chatSessionId: String? = null,
    val recommendations: List<AiProductRecommendationDto> = emptyList()
)

// ─────────────────────────────────────────────────────────────────────────────

class AiChatService {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = HttpClient(CIO)

    private val geminiKey: String? get() = Env.get("API_GEMINI_CHATBOTAI")

    private val systemPrompt = """
Bạn là AI tư vấn vật tư y tế của Medstore – ứng dụng di động mua sắm thiết bị và vật tư y tế.

Quy tắc bắt buộc:
1. Trả lời tiếng Việt, TỐI ĐA 80 TỪ, đi thẳng vào vấn đề, không dài dòng
2. Không bắt đầu bằng "Chào bạn" hay câu mở đầu thừa ở mỗi tin nhắn
3. Chỉ tư vấn vật tư/thiết bị y tế – KHÔNG tư vấn thuốc điều trị bệnh
4. Câu hỏi về chẩn đoán/điều trị → trả lời ngắn 1 câu và khuyên gặp bác sĩ
5. KHÔNG đề cập đến "trang web", "thanh tìm kiếm", "danh mục" – người dùng đang trong ứng dụng
6. Khi sản phẩm đã được gợi ý qua card bên dưới → KHÔNG liệt kê lại tên/giá trong text, chỉ nhắc ngắn để người dùng xem card
7. Dùng gạch đầu dòng khi liệt kê, không viết thành đoạn văn dài
    """.trimIndent()

    // ── Create new conversation ───────────────────────────────────────────────

    fun createConversation(userId: String, productId: String? = null): AiConversationDto {
        val id = UUID.randomUUID().toString()
        val initialMessages = productId
            ?.let(::findProductById)
            ?.let { product ->
                listOf(
                    AiStoredMessage(
                        role = "ai",
                        text = buildProductIntro(product),
                        recommendations = listOf(product.toRecommendationDto())
                    )
                )
            }
            ?: emptyList()
        transaction {
            AiConversationsTable.insert {
                it[AiConversationsTable.id] = id
                it[AiConversationsTable.userId] = userId
                it[AiConversationsTable.productId] = productId
                it[AiConversationsTable.conversationHistory] = json.encodeToString(initialMessages)
                it[AiConversationsTable.status] = "ACTIVE"
                it[AiConversationsTable.escalatedToConsultant] = false
            }
        }
        return AiConversationDto(
            id = id,
            status = "ACTIVE",
            escalatedToConsultant = false,
            messages = initialMessages.map {
                AiMessageDto(
                    role = it.role,
                    text = it.text,
                    recommendations = it.recommendations
                )
            }
        )
    }

    // ── Send a user message and get AI reply ──────────────────────────────────

    suspend fun sendMessage(conversationId: String, userId: String, userMessage: String): AiSendMessageResponse {
        val row = transaction {
            AiConversationsTable.selectAll()
                .where { AiConversationsTable.id eq conversationId }
                .singleOrNull()
        } ?: throw NoSuchElementException("Conversation not found")

        if (row[AiConversationsTable.userId] != userId) throw IllegalAccessException("Not your conversation")
        if (row[AiConversationsTable.status] != "ACTIVE") throw IllegalStateException("Conversation is no longer active")

        val storedHistory = decodeStoredHistory(row[AiConversationsTable.conversationHistory])
        val historyForGemini = (storedHistory + AiStoredMessage(role = "user", text = userMessage))
            .toGeminiHistory()

        val conversationProductId = row[AiConversationsTable.productId]

        // Use last recommended product as context when conversation has no fixed product
        val lastRecommendedProductId = storedHistory
            .lastOrNull { it.role == "ai" && it.recommendations.isNotEmpty() }
            ?.recommendations?.firstOrNull()?.productId
        val contextProductId = conversationProductId ?: lastRecommendedProductId

        // Determine search intent
        val searchMode = when {
            isAskingForAlternative(userMessage) -> SearchMode.ALTERNATIVE
            isPriceSearch(userMessage)          -> SearchMode.CHEAPER
            isExpensiveSearch(userMessage)      -> SearchMode.EXPENSIVE
            isMidRangeSearch(userMessage)       -> SearchMode.MIDRANGE
            else                                -> SearchMode.NORMAL
        }

        // When the query contains explicit product-type keywords (e.g. "nhiệt kế đắt nhất"),
        // use global keyword+price scoring instead of category-restricted functions.
        // Category-restricted functions (findCheaper/findMostExpensive) are used only for
        // bare price queries like "rẻ hơn", "đắt hơn" where user refers to the current product.
        val priceOnlyTokens = setOf("re", "dat", "nhat", "tot", "hon", "gia")
        val nonPriceQueryTokens = extractQueryTokens(userMessage) - priceOnlyTokens
        val hasProductTypeKeywords = nonPriceQueryTokens.isNotEmpty()

        // Route to appropriate product finder; use contextProductId so general-chat follow-ups work
        val candidates = when {
            searchMode == SearchMode.ALTERNATIVE && contextProductId != null -> findAlternativeProducts(contextProductId)
            // Use category-scoped functions only when there are no explicit product keywords
            searchMode == SearchMode.CHEAPER   && contextProductId != null && !hasProductTypeKeywords -> findCheaperProducts(contextProductId)
            searchMode == SearchMode.EXPENSIVE && contextProductId != null && !hasProductTypeKeywords -> findMostExpensiveProducts(contextProductId)
            else -> findRelevantProducts(contextProductId, userMessage, searchMode)
        }

        // Only push a card when the product truly matches what the user asked for.
        // For price/alternative searches, always show. For NORMAL keyword searches,
        // require ALL query tokens to appear in the product (prevents e.g. "bộ test covid"
        // from showing an HIV test card). When queryTokens is empty (context-only question
        // like "cách sử dụng"), only show if the top candidate IS the context product.
        // Quality/attribute words that describe a product feature but never appear in product names.
        // Remove these before checking whether a product matches the user's query intent.
        val qualityDescriptors = setOf(
            "tot", "nhat", "dep", "an", "toan", "hieu",
            "chinh", "xac",   // chính xác (accurate)
            "an", "toan",     // an toàn (safe)
            "moi", "cu"       // new/old
        )

        val queryTokens = if (searchMode == SearchMode.NORMAL) extractQueryTokens(userMessage) else emptySet()
        val recommendedProducts = candidates.take(1).filter { product ->
            when {
                searchMode != SearchMode.NORMAL -> true
                queryTokens.isEmpty() -> product.id == contextProductId
                else -> {
                    val productWords = normalizeForSearch(
                        listOfNotNull(product.name, product.categoryName, product.brand).joinToString(" ")
                    ).split(' ').toSet()
                    // Only require product-type tokens to match (strip out quality adjectives).
                    // Use 75% match threshold (not 100%) to tolerate minor typos:
                    // e.g. "nhietj ke hong ngoai" (typo) → 3/4 = 75% → show RT101 card.
                    // But "test covid" → 1/2 = 50% < 75% → no HIV card.
                    val productTypeTokens = queryTokens - qualityDescriptors
                    if (productTypeTokens.isEmpty()) product.id == contextProductId
                    else {
                        val matched = productTypeTokens.count { it in productWords }
                        matched.toDouble() / productTypeTokens.size >= 0.75
                    }
                }
            }
        }

        val geminiReply = callGemini(
            history = historyForGemini,
            candidates = candidates.take(12),
            focusProduct = recommendedProducts.firstOrNull(),
            searchMode = searchMode
        )
        val isError = geminiReply == null
        val aiReply = geminiReply ?: errorFallback

        // On error: suppress all recommendations so no wrong card is shown and
        // no wrong product ID is persisted to history (prevents context corruption).
        val recommendations = if (isError) emptyList() else recommendedProducts.map { it.toRecommendationDto() }
        val updatedHistory = storedHistory +
            AiStoredMessage(role = "user", text = userMessage) +
            AiStoredMessage(role = "ai", text = aiReply, recommendations = recommendations)

        val updatedJson = json.encodeToString(updatedHistory)
        transaction {
            AiConversationsTable.update({ AiConversationsTable.id eq conversationId }) {
                it[AiConversationsTable.conversationHistory] = updatedJson
                it[AiConversationsTable.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            }
        }

        return AiSendMessageResponse(
            reply = aiReply,
            conversationId = conversationId,
            status = "ACTIVE",
            escalatedToConsultant = false,
            chatSessionId = null,
            recommendations = recommendations
        )
    }


    // ── Escalate to human consultant ──────────────────────────────────────────

    fun escalateToHuman(conversationId: String, userId: String): AiSendMessageResponse {
        val row = transaction {
            AiConversationsTable.selectAll()
                .where { AiConversationsTable.id eq conversationId }
                .singleOrNull()
        } ?: throw NoSuchElementException("Conversation not found")

        if (row[AiConversationsTable.userId] != userId) throw IllegalAccessException("Not your conversation")

        val productId = row[AiConversationsTable.productId]

        // Create a PENDING human chat session
        val humanSession = ChatService().createOrResumeSession(userId, productId)

        // Link and mark escalated
        transaction {
            AiConversationsTable.update({ AiConversationsTable.id eq conversationId }) {
                it[AiConversationsTable.escalatedToConsultant] = true
                it[AiConversationsTable.chatSessionId] = humanSession.id
                it[AiConversationsTable.status] = "ESCALATED"
                it[AiConversationsTable.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            }
        }

        return AiSendMessageResponse(
            reply = "Đã kết nối với chuyên viên tư vấn. Vui lòng chờ trong giây lát.",
            conversationId = conversationId,
            status = "ESCALATED",
            escalatedToConsultant = true,
            chatSessionId = humanSession.id
        )
    }

    // ── Get single conversation ───────────────────────────────────────────────

    fun getConversation(conversationId: String, userId: String): AiConversationDto {
        val row = transaction {
            AiConversationsTable.selectAll()
                .where { AiConversationsTable.id eq conversationId }
                .singleOrNull()
        } ?: throw NoSuchElementException("Conversation not found")

        if (row[AiConversationsTable.userId] != userId) throw IllegalAccessException("Not your conversation")

        return rowToDto(row)
    }

    // ── List conversations for a user ─────────────────────────────────────────

    fun getUserConversations(userId: String): List<AiConversationDto> {
        return transaction {
            AiConversationsTable.selectAll()
                .where { AiConversationsTable.userId eq userId }
                .orderBy(AiConversationsTable.createdAt, SortOrder.DESC)
                .map { rowToDto(it) }
        }
    }

    fun deleteConversation(conversationId: String, userId: String) {
        val row = transaction {
            AiConversationsTable.selectAll()
                .where { AiConversationsTable.id eq conversationId }
                .singleOrNull()
        } ?: throw NoSuchElementException("Conversation not found")
        if (row[AiConversationsTable.userId] != userId) throw IllegalAccessException("Not your conversation")
        transaction {
            AiConversationsTable.deleteWhere { AiConversationsTable.id eq conversationId }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    fun closeConversation(conversationId: String, userId: String): AiConversationDto {
        val row = transaction {
            AiConversationsTable.selectAll()
                .where { AiConversationsTable.id eq conversationId }
                .singleOrNull()
        } ?: throw NoSuchElementException("Conversation not found")

        if (row[AiConversationsTable.userId] != userId) throw IllegalAccessException("Not your conversation")

        transaction {
            AiConversationsTable.update({ AiConversationsTable.id eq conversationId }) {
                it[AiConversationsTable.status] = "CLOSED"
                it[AiConversationsTable.closedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                it[AiConversationsTable.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            }
        }

        val updated = transaction {
            AiConversationsTable.selectAll()
                .where { AiConversationsTable.id eq conversationId }
                .single()
        }
        return rowToDto(updated)
    }

    private fun rowToDto(row: org.jetbrains.exposed.sql.ResultRow): AiConversationDto {
        val messages = decodeStoredHistory(row[AiConversationsTable.conversationHistory]).map { content ->
            AiMessageDto(
                role = content.role,
                text = content.text,
                recommendations = content.recommendations
            )
        }
        return AiConversationDto(
            id = row[AiConversationsTable.id],
            status = row[AiConversationsTable.status],
            escalatedToConsultant = row[AiConversationsTable.escalatedToConsultant],
            chatSessionId = row[AiConversationsTable.chatSessionId],
            createdAt = row[AiConversationsTable.createdAt].toString(),
            messages = messages
        )
    }

    private fun decodeStoredHistory(historyJson: String?): List<AiStoredMessage> {
        val source = historyJson?.takeIf { it.isNotBlank() } ?: "[]"
        val storedHistory = runCatching {
            json.decodeFromString<List<AiStoredMessage>>(source)
        }.getOrNull()
        if (storedHistory != null) return storedHistory

        val geminiHistory = runCatching {
            json.decodeFromString<List<GeminiContent>>(source)
        }.getOrNull() ?: return emptyList()

        return geminiHistory.map { content ->
            AiStoredMessage(
                role = if (content.role == "model") "ai" else "user",
                text = content.parts.firstOrNull()?.text ?: ""
            )
        }
    }

    private fun List<AiStoredMessage>.toGeminiHistory(): List<GeminiContent> {
        return dropWhile { it.role == "ai" }.map { message ->
            GeminiContent(
                role = if (message.role == "ai") "model" else "user",
                parts = listOf(GeminiPart(message.text))
            )
        }
    }

    // Returns null when an error occurs so callers can suppress card recommendations on error.
    private suspend fun callGemini(
        history: List<GeminiContent>,
        candidates: List<AiProductContext>,
        focusProduct: AiProductContext?,
        searchMode: SearchMode = SearchMode.NORMAL
    ): String? {
        val key = geminiKey
        if (key.isNullOrBlank()) return null

        val trimmedHistory = if (history.size > 10) history.takeLast(10) else history
        val productContext = buildProductContext(candidates)
        val promptWithCatalog = if (productContext.isBlank()) {
            // No matching products found — explicitly forbid card references
            systemPrompt + """

Yeu cau trong tin nay: KHONG co san pham nao phu hop trong CSDL va KHONG co CARD nao duoc gui kem.
- TUYET DOI KHONG viet "xem ben duoi", "xem card", "xem san pham goi y", "ngay ben duoi".
- Neu user hoi ve san pham da de cap trong cuoc tro chuyen, co the nhac ten nhung khong noi 'xem ben duoi'.
- Neu user can xem/mua san pham cu the, goi y ket noi chuyen vien hoac mo lai cuoc tro chuyen moi."""
        } else {
            val focusSection = when {
                searchMode == SearchMode.ALTERNATIVE && focusProduct != null ->
                    """

San pham thay the duoc goi y (CARD da hien thi cho user): ${focusProduct.name} - ${focusProduct.priceText}/${focusProduct.unit}${focusProduct.shortDescription?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}
Yeu cau: User dang hoi ve san pham tuong tu/khac loai. Hay gioi thieu san pham nay nhu mot lua chon thay the, kem gia va cong dung ngan gon."""

                searchMode == SearchMode.ALTERNATIVE && focusProduct == null ->
                    """

Yeu cau: User dang hoi co san pham cung loai khac khong. Hien tai KHONG co san pham nao khac cung loai con hang trong CSDL. Hay thong bao ro rang la hien chua co va goi y user ket noi chuyen vien neu can tu van them."""

                searchMode == SearchMode.CHEAPER && focusProduct != null ->
                    """

San pham gia tot nhat phu hop (CARD da hien thi cho user): ${focusProduct.name} - ${focusProduct.priceText}/${focusProduct.unit}${focusProduct.shortDescription?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}
Yeu cau: Gioi thieu san pham gia re nay, nhan manh gia tot. KHONG liet lai ten/gia trong text vi da co card."""

                searchMode == SearchMode.CHEAPER && focusProduct == null ->
                    """

Yeu cau: Hien khong co san pham gia re hon hoac cung loai trong CSDL. Thong bao ngan gon va goi y ket noi chuyen vien de duoc ho tro them."""

                searchMode == SearchMode.EXPENSIVE && focusProduct != null ->
                    """

San pham cao cap nhat phu hop (CARD da hien thi cho user): ${focusProduct.name} - ${focusProduct.priceText}/${focusProduct.unit}${focusProduct.shortDescription?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}
Yeu cau: Gioi thieu san pham chat luong cao nay, nhan manh do ben va tinh nang. KHONG liet lai ten/gia trong text vi da co card."""

                searchMode == SearchMode.EXPENSIVE && focusProduct == null ->
                    """

Yeu cau: Hien khong tim duoc san pham chat luong cao phu hop trong CSDL. Thong bao ngan gon."""

                searchMode == SearchMode.MIDRANGE && focusProduct != null ->
                    """

San pham tam trung phu hop (CARD da hien thi cho user): ${focusProduct.name} - ${focusProduct.priceText}/${focusProduct.unit}${focusProduct.shortDescription?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}
Yeu cau: Gioi thieu san pham nay nhu lua chon gia ca phai chang, can doi chat luong va gia. KHONG liet lai ten/gia trong text vi da co card."""

                searchMode == SearchMode.MIDRANGE && focusProduct == null ->
                    """

Yeu cau: Hien khong tim duoc san pham gia tam trung phu hop trong CSDL. Thong bao ngan gon."""

                focusProduct != null ->
                    """

San pham duoc goi y chinh (CARD da hien thi cho user): ${focusProduct.name} - ${focusProduct.priceText}/${focusProduct.unit}${focusProduct.shortDescription?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}
Yeu cau: Phan hoi cua ban PHAI tap trung vao san pham nay. Neu co de cap san pham khac, chi kem phu de so sanh, khong thay the san pham chinh tren."""

                else ->
                    """

Yeu cau: KHONG co CARD san pham nao duoc gui kem trong tin nay. Neu muon goi y san pham tu danh sach tren, mo ta ngan gon TEN va GIA truc tiep trong text. TUYET DOI KHONG viet "xem san pham ben duoi", "xem card", "xem goi y ben duoi" vi khong co card nao ca."""
            }

            """
$systemPrompt

Du lieu san pham dang ban trong CSDL Medstore:
$productContext$focusSection

Quy tac san pham:
- Chi duoc nhac ten san pham CO TRONG danh sach tren, tuyet doi khong tu biet ten san pham ngoai danh sach.
- Neu co CARD (focus section ghi 'CARD da hien thi') → chi noi ngan "Xem san pham goi y ben duoi nhe", khong liet lai ten/gia.
- Neu KHONG co CARD → mo ta ngan san pham trong text neu can, KHONG de cap den 'ben duoi' hay 'card'.
- Neu khong co san pham phu hop → noi thang "Hien chua co san pham phu hop, ban co the ket noi chuyen vien de duoc tu van them."
            """.trimIndent()
        }

        val request = GeminiRequest(
            contents = trimmedHistory,
            systemInstruction = GeminiSystemInstruction(
                parts = listOf(GeminiPart(promptWithCatalog))
            )
        )

        return doGeminiRequest(json.encodeToString(request), key, retryOn429 = true)
    }

    private val errorFallback = "Xin lỗi, đã xảy ra lỗi kết nối. Bạn có thể kết nối nhân viên tư vấn để được hỗ trợ nhé."

    private fun buildProductContext(candidates: List<AiProductContext>): String {
        if (candidates.isEmpty()) return ""
        return candidates.joinToString(separator = "\n") { product ->
            val parts = mutableListOf(
                "ten=${product.name}",
                "gia=${product.priceText}/${product.unit}",
                "ton=${product.stock}"
            )
            product.categoryName?.takeIf { it.isNotBlank() }?.let { parts += "nhom=$it" }
            product.brand?.takeIf { it.isNotBlank() }?.let { parts += "hang=$it" }
            product.shortDescription?.takeIf { it.isNotBlank() }?.let { parts += "mo_ta=${it.take(160)}" }
            "- ${parts.joinToString("; ")}"
        }
    }

    private fun findProductById(productId: String): AiProductContext? {
        return transaction {
            ProductsTable
                .selectAll()
                .where {
                    (ProductsTable.id eq productId) and
                        (ProductsTable.isActive eq true) and
                        ProductsTable.deletedAt.isNull()
                }
                .singleOrNull()
                ?.let { row ->
                    val catId = row[ProductsTable.categoryId]
                    val categoryName = catId?.let { cid ->
                        CategoriesTable.selectAll()
                            .where { CategoriesTable.id eq cid }
                            .singleOrNull()?.get(CategoriesTable.name)
                    }
                    AiProductContext(
                        id = row[ProductsTable.id],
                        name = row[ProductsTable.name],
                        categoryId = catId,
                        categoryName = categoryName,
                        shortDescription = row[ProductsTable.shortDescription] ?: row[ProductsTable.description]?.take(140),
                        brand = row[ProductsTable.brand],
                        imageUrl = ProductImagesTable
                            .selectAll()
                            .where { ProductImagesTable.productId eq row[ProductsTable.id] }
                            .orderBy(ProductImagesTable.sortOrder to SortOrder.ASC)
                            .limit(1)
                            .singleOrNull()
                            ?.get(ProductImagesTable.url),
                        unit = row[ProductsTable.unit],
                        price = row[ProductsTable.price].toDouble(),
                        priceText = formatPrice(row[ProductsTable.price].toLong()),
                        stock = row[ProductsTable.stock]
                    )
                }
        }
    }

    private fun isPriceSearch(userMessage: String): Boolean {
        val n = normalizeForSearch(userMessage)
        val words = n.split(' ').toSet()
        if ("re" in words) return true
        return listOf(
            "re hon", "re nhat", "gia re", "tiet kiem", "gia thap",
            "gia tot", "re ti", "re chut", "re hon di", "re di",
            "kinh te hon", "phu hop tui tien", "binh dan"
        ).any { n.contains(it) }
    }

    private fun isExpensiveSearch(userMessage: String): Boolean {
        val n = normalizeForSearch(userMessage)
        val words = n.split(' ').toSet()
        if ("dat" in words) return true
        return listOf(
            "dat tien", "dat nhat", "hang tot nhat", "chat luong cao",
            "cao cap", "hang cao cap", "tot nhat", "chat luong nhat",
            "dat hon", "gia cao", "hang chinh hang cao"
        ).any { n.contains(it) }
    }

    private fun isMidRangeSearch(userMessage: String): Boolean {
        val n = normalizeForSearch(userMessage)
        return listOf(
            "vua tien", "tam trung", "phai chang", "hop ly",
            "tam duoc", "vua phai", "trung binh", "gia vua",
            "khong qua dat", "gia binh thuong", "gia hop ly"
        ).any { n.contains(it) }
    }

    private fun isAskingForAlternative(userMessage: String): Boolean {
        val n = normalizeForSearch(userMessage)
        return listOf(
            "khac", "tuong tu", "thay the", "bien the",
            "loai nao", "san pham nao khac", "hang khac",
            "nua khong", "nao nua", "gi nua", "them khong",
            "con loai", "con cai", "con san pham", "co gi khac",
            "nua ko", "nua k", "them ko", "them k"
        ).any { n.contains(it) }
    }

    private fun findCheaperProducts(currentProductId: String): List<AiProductContext> {
        val current = findProductById(currentProductId) ?: return emptyList()
        val catId = current.categoryId ?: return emptyList()
        return transaction {
            (ProductsTable leftJoin CategoriesTable)
                .selectAll()
                .where {
                    (ProductsTable.isActive eq true) and
                        ProductsTable.deletedAt.isNull() and
                        (ProductsTable.stock greater 0) and
                        (ProductsTable.id neq currentProductId) and
                        (ProductsTable.categoryId eq catId)
                }
                .orderBy(ProductsTable.price to SortOrder.ASC)
                .limit(12)
                .map { row -> rowToProductContext(row, catId) }
        }
    }

    private fun findMostExpensiveProducts(currentProductId: String): List<AiProductContext> {
        val current = findProductById(currentProductId) ?: return emptyList()
        val catId = current.categoryId ?: return emptyList()
        return transaction {
            (ProductsTable leftJoin CategoriesTable)
                .selectAll()
                .where {
                    (ProductsTable.isActive eq true) and
                        ProductsTable.deletedAt.isNull() and
                        (ProductsTable.stock greater 0) and
                        (ProductsTable.id neq currentProductId) and
                        (ProductsTable.categoryId eq catId)
                }
                .orderBy(ProductsTable.price to SortOrder.DESC)
                .limit(12)
                .map { row -> rowToProductContext(row, catId) }
        }
    }

    private fun findAlternativeProducts(currentProductId: String): List<AiProductContext> {
        val current = findProductById(currentProductId) ?: return emptyList()
        val catId = current.categoryId ?: return emptyList()
        return transaction {
            (ProductsTable leftJoin CategoriesTable)
                .selectAll()
                .where {
                    (ProductsTable.isActive eq true) and
                        ProductsTable.deletedAt.isNull() and
                        (ProductsTable.stock greater 0) and
                        (ProductsTable.id neq currentProductId) and
                        (ProductsTable.categoryId eq catId)
                }
                .orderBy(ProductsTable.discountPct to SortOrder.DESC)
                .limit(12)
                .map { row -> rowToProductContext(row, catId) }
        }
    }

    private fun rowToProductContext(
        row: org.jetbrains.exposed.sql.ResultRow,
        catId: String
    ): AiProductContext {
        val productId = row[ProductsTable.id]
        return AiProductContext(
            id = productId,
            name = row[ProductsTable.name],
            categoryId = catId,
            imageUrl = ProductImagesTable
                .selectAll()
                .where { ProductImagesTable.productId eq productId }
                .orderBy(ProductImagesTable.sortOrder to SortOrder.ASC)
                .limit(1)
                .singleOrNull()?.get(ProductImagesTable.url),
            categoryName = row.getOrNull(CategoriesTable.name),
            shortDescription = row[ProductsTable.shortDescription] ?: row[ProductsTable.description]?.take(140),
            brand = row[ProductsTable.brand],
            unit = row[ProductsTable.unit],
            price = row[ProductsTable.price].toDouble(),
            priceText = formatPrice(row[ProductsTable.price].toLong()),
            stock = row[ProductsTable.stock]
        )
    }

    private fun findRelevantProducts(
        contextProductId: String?,
        userMessage: String,
        searchMode: SearchMode = SearchMode.NORMAL
    ): List<AiProductContext> {
        val productId = contextProductId
        val products = transaction {
            (ProductsTable leftJoin CategoriesTable)
                .selectAll()
                .where {
                    (ProductsTable.isActive eq true) and
                        ProductsTable.deletedAt.isNull() and
                        (ProductsTable.stock greater 0)
                }
                .orderBy(ProductsTable.discountPct to SortOrder.DESC)
                .limit(80)
                .map { row ->
                    AiProductContext(
                        id = row[ProductsTable.id],
                        name = row[ProductsTable.name],
                        categoryId = row[ProductsTable.categoryId],
                        imageUrl = ProductImagesTable
                            .selectAll()
                            .where { ProductImagesTable.productId eq row[ProductsTable.id] }
                            .orderBy(ProductImagesTable.sortOrder to SortOrder.ASC)
                            .limit(1)
                            .singleOrNull()
                            ?.get(ProductImagesTable.url),
                        categoryName = row.getOrNull(CategoriesTable.name),
                        shortDescription = row[ProductsTable.shortDescription] ?: row[ProductsTable.description]?.take(140),
                        brand = row[ProductsTable.brand],
                        unit = row[ProductsTable.unit],
                        price = row[ProductsTable.price].toDouble(),
                        priceText = formatPrice(row[ProductsTable.price].toLong()),
                        stock = row[ProductsTable.stock]
                    )
                }
        }
        if (products.isEmpty()) return emptyList()

        val queryTokens = extractQueryTokens(userMessage)

        // Dùng word-level matching (không dùng substring) để tránh "co" khớp "cong", "cot"...
        val scored = products.map { product ->
            val searchableWords = normalizeForSearch(
                listOfNotNull(product.name, product.categoryName, product.brand)
                    .joinToString(" ")
            ).split(' ').toSet()
            val keywordScore = queryTokens.count { token -> searchableWords.contains(token) }
            product to keywordScore
        }

        val maxKeywordScore = scored.maxOfOrNull { it.second } ?: 0

        // Context bonus: prefer the last-seen product when keyword signals are weak.
        // maxScore==1 typically means an accidental single-word match (e.g. "su" from "sử dụng"
        // matching "cao su" in condom name). Give context product a stronger nudge in that case.
        val contextBonus = when {
            productId == null    -> 0
            maxKeywordScore == 0 -> 10  // pure context question, fully rely on last product
            maxKeywordScore == 1 -> 5   // weak/accidental match, prefer context product
            else                 -> 0   // strong keyword match, trust keywords
        }

        val withBonus = scored.map { (product, kw) ->
            val bonus = if (product.id == productId) contextBonus else 0
            product to (kw + bonus)
        }.filter { it.second > 0 }

        // Sort by price when user explicitly asks for price tier
        return when (searchMode) {
            SearchMode.CHEAPER   -> withBonus.sortedBy { it.first.price }.map { it.first }
            SearchMode.EXPENSIVE -> withBonus.sortedByDescending { it.first.price }.map { it.first }
            SearchMode.MIDRANGE  -> {
                val sorted = withBonus.sortedBy { it.first.price }
                if (sorted.isEmpty()) emptyList()
                else {
                    val mid = sorted.size / 2
                    listOf(sorted[mid].first) + sorted.mapIndexedNotNull { i, p -> if (i != mid) p.first else null }
                }
            }
            else -> withBonus
                .sortedWith(
                    compareByDescending<Pair<AiProductContext, Int>> { it.second }
                        // Prefer context product on ties (avoids Vietnamese Unicode sort issues)
                        .thenByDescending { if (productId != null && it.first.id == productId) 1 else 0 }
                        // Price ASC as final tiebreaker
                        .thenBy { it.first.price }
                )
                .map { it.first }
        }
    }

    private fun AiProductContext.toRecommendationDto(): AiProductRecommendationDto {
        return AiProductRecommendationDto(
            productId = id,
            productName = name,
            productImage = imageUrl,
            price = price,
            productUnit = unit,
            description = shortDescription ?: "",
            reason = "Phù hợp với nhu cầu bạn vừa hỏi và đang còn hàng."
        )
    }

    private fun buildProductIntro(product: AiProductContext): String {
        val stockText = if (product.stock > 0) "Hiện sản phẩm còn hàng." else "Hiện sản phẩm đang hết hàng."
        val categoryText = product.categoryName?.takeIf { it.isNotBlank() }?.let { " thuộc nhóm $it" } ?: ""
        val descriptionText = product.shortDescription
            ?.takeIf { it.isNotBlank() }
            ?.let { "\n- Mô tả ngắn: $it" }
            ?: ""
        return """
Bạn đang xem ${product.name}$categoryText.
- Giá tham khảo: ${product.priceText}/${product.unit}
- $stockText$descriptionText

Bạn có thể hỏi tôi về công dụng, cách dùng an toàn, lưu ý khi chọn mua hoặc sản phẩm thay thế phù hợp.
        """.trimIndent()
    }

    private val stopWords = setOf(
        "co", "khong", "la", "va", "de", "cho", "san", "pham", "loai", "hay",
        "the", "nay", "gi", "kh", "bi", "thi", "cac", "mot", "nhu", "duoc",
        "voi", "trong", "khi", "ban", "toi", "se", "da", "cua", "ra", "vao",
        "len", "xuong", "tu", "den", "sau", "truoc", "tren", "duoi", "cung",
        "nhung", "ma", "vi", "nen", "neu", "tuy", "dung", "biet", "that",
        "qua", "rat", "hon", "kia", "do", "ay", "nao", "bao", "noi", "tim",
        "muon", "can", "bj", "th", "mk", "mn", "ntn", "j", "nha", "bh",
        // Short function syllables that cause accidental product name matches
        "su", "cach", "te", "vs", "ok",
        // Vietnamese counter/generic words that don't distinguish product types
        "bo", "cai", "chiec", "hop", "goi", "tui", "lo"
    )

    private fun extractQueryTokens(userMessage: String): Set<String> =
        normalizeForSearch(userMessage)
            .split(' ')
            .filter { it.length >= 2 && it !in stopWords }
            .toSet()

    private fun normalizeForSearch(value: String): String {
        // 'đ' (U+0111) has no NFD decomposition → must be replaced with 'd' explicitly
        // before NFD; otherwise "đắt" → " at" (not "dat") and price detection breaks.
        val withD = value.lowercase().replace('đ', 'd')
        val withoutMarks = Normalizer.normalize(withD, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return withoutMarks.replace("[^a-z0-9\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun formatPrice(value: Long): String {
        return "%,d VND".format(value).replace(',', '.')
    }

    private suspend fun doGeminiRequest(requestJson: String, key: String, retryOn429: Boolean): String? {
        return try {
            val httpResponse = httpClient.post(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key"
            ) {
                contentType(ContentType.Application.Json)
                setBody(requestJson)
            }
            val responseText = httpResponse.body<String>()
            when (httpResponse.status.value) {
                200 -> json.decodeFromString<GeminiResponse>(responseText)
                    .candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: null  // empty response → treat as error
                429 -> {
                    println("[Gemini] HTTP 429 — rate limited, retryOn429=$retryOn429")
                    if (retryOn429) {
                        delay(5_000)
                        doGeminiRequest(requestJson, key, retryOn429 = false)
                    } else null
                }
                else -> {
                    println("[Gemini] HTTP ${httpResponse.status.value}: $responseText")
                    null
                }
            }
        } catch (e: Exception) {
            println("[Gemini] Exception: ${e.message}")
            null
        }
    }
}
