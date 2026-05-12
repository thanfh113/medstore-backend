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
import org.jetbrains.exposed.sql.and
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
    val categoryName: String?,
    val shortDescription: String?,
    val brand: String?,
    val imageUrl: String?,
    val unit: String,
    val price: Double,
    val priceText: String,
    val stock: Int,
    val requiresConsultation: Boolean
)

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
Bạn là trợ lý AI tư vấn vật tư y tế của Medstore – nền tảng mua sắm thiết bị và vật tư y tế chuyên nghiệp.

Vai trò của bạn:
- Tư vấn như một dược sĩ / chuyên viên vật tư y tế có kinh nghiệm
- Giải thích công dụng, cách sử dụng, lưu ý an toàn của thiết bị và vật tư y tế
- Gợi ý sản phẩm phù hợp với nhu cầu người dùng
- Giải thích thuật ngữ y tế một cách dễ hiểu cho người dùng phổ thông

Quy tắc bắt buộc:
1. Luôn trả lời bằng tiếng Việt, ngắn gọn, rõ ràng và thân thiện
2. Chỉ tư vấn về vật tư và thiết bị y tế – KHÔNG tư vấn thuốc điều trị bệnh
3. Nếu câu hỏi liên quan đến chẩn đoán bệnh hoặc cần bác sĩ thăm khám, giải thích giới hạn và khuyên gặp bác sĩ
4. Nếu câu hỏi về thiết bị chuyên dụng (máy thở, thiết bị phòng mổ, thiết bị cấy ghép), đề xuất kết nối chuyên viên kỹ thuật
5. Phản hồi tối đa 200 từ để dễ đọc trên điện thoại
6. Dùng gạch đầu dòng khi cần liệt kê để dễ đọc
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

        val recommendedProducts = findRelevantProducts(row[AiConversationsTable.productId], userMessage).take(3)
        val aiReply = callGemini(
            history = historyForGemini,
            productId = row[AiConversationsTable.productId],
            userMessage = userMessage
        )

        val recommendations = recommendedProducts.map { product ->
            product.toRecommendationDto()
        }
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

    /* 
            recommendations = recommendations.map {
                AiProductRecommendationDto(
                    productId = it.id,
                    productName = it.name,
                    productImage = it.imageUrl,
                    price = it.price,
                    productUnit = it.unit,
                    description = it.shortDescription ?: "",
                    reason = if (it.requiresConsultation) {
                        "Sản phẩm phù hợp nhưng nên trao đổi thêm với chuyên viên trước khi dùng."
                    } else {
                        "Phù hợp với nhu cầu bạn vừa hỏi và đang còn hàng."
                    }
                )
            }
            */

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

    // ── Internal helpers ──────────────────────────────────────────────────────

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

    private suspend fun callGemini(history: List<GeminiContent>, productId: String?, userMessage: String): String {
        val key = geminiKey
        if (key.isNullOrBlank()) return "Xin lỗi, dịch vụ AI tạm thời không khả dụng."

        // Keep only the last 10 messages to avoid bloating tokens on long conversations
        val trimmedHistory = if (history.size > 10) history.takeLast(10) else history
        val productContext = buildProductContext(productId, userMessage)
        val promptWithCatalog = if (productContext.isBlank()) {
            systemPrompt
        } else {
            """
$systemPrompt

Du lieu san pham dang ban trong CSDL Medstore:
$productContext

Quy tac goi y san pham:
- Chi goi y san pham co trong danh sach tren, khong tu bia ten san pham.
- Neu co san pham phu hop, neu toi da 3 san pham kem gia, don vi va ly do ngan gon.
- Neu khong co san pham phu hop trong danh sach, noi ro hien chua thay san pham phu hop va goi y ket noi chuyen vien.
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

    private fun buildProductContext(productId: String?, userMessage: String): String {
        val ranked = findRelevantProducts(productId, userMessage).take(12)
        if (ranked.isEmpty()) return ""

        return ranked.joinToString(separator = "\n") { product ->
            val parts = mutableListOf(
                "id=${product.id}",
                "ten=${product.name}",
                "gia=${product.priceText}/${product.unit}",
                "ton=${product.stock}"
            )
            product.categoryName?.takeIf { it.isNotBlank() }?.let { parts += "nhom=$it" }
            product.brand?.takeIf { it.isNotBlank() }?.let { parts += "hang=$it" }
            product.shortDescription?.takeIf { it.isNotBlank() }?.let { parts += "mo_ta=${it.take(160)}" }
            if (product.requiresConsultation) parts += "can_tu_van=true"
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
                    val categoryName = row[ProductsTable.categoryId]?.let { categoryId ->
                        CategoriesTable
                            .selectAll()
                            .where { CategoriesTable.id eq categoryId }
                            .singleOrNull()
                            ?.get(CategoriesTable.name)
                    }
                    AiProductContext(
                        id = row[ProductsTable.id],
                        name = row[ProductsTable.name],
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
                        stock = row[ProductsTable.stock],
                        requiresConsultation = row[ProductsTable.requiresConsultation]
                    )
                }
        }
    }

    private fun findRelevantProducts(productId: String?, userMessage: String): List<AiProductContext> {
        val products = transaction {
            (ProductsTable leftJoin CategoriesTable)
                .selectAll()
                .where {
                    (ProductsTable.isActive eq true) and
                        ProductsTable.deletedAt.isNull() and
                        (ProductsTable.stock greater 0)
                }
                .orderBy(ProductsTable.isBestSeller to SortOrder.DESC, ProductsTable.isFlashSale to SortOrder.DESC)
                .limit(80)
                .map { row ->
                    AiProductContext(
                        id = row[ProductsTable.id],
                        name = row[ProductsTable.name],
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
                        stock = row[ProductsTable.stock],
                        requiresConsultation = row[ProductsTable.requiresConsultation]
                    )
                }
        }
        if (products.isEmpty()) return emptyList()

        val queryTokens = normalizeForSearch(userMessage)
            .split(' ')
            .filter { it.length >= 2 }
            .toSet()

        return products
            .map { product ->
                val searchable = normalizeForSearch(
                    listOfNotNull(product.name, product.categoryName, product.shortDescription, product.brand)
                        .joinToString(" ")
                )
                val score = queryTokens.count { token -> searchable.contains(token) } +
                    if (product.id == productId) 10 else 0
                product to score
            }
            .sortedWith(
                compareByDescending<Pair<AiProductContext, Int>> { it.second }
                    .thenBy { it.first.name }
            )
            .let { scored ->
                val matched = scored.filter { it.second > 0 }.map { it.first }
                if (matched.isNotEmpty()) matched else scored.map { it.first }
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
            reason = if (requiresConsultation) {
                "Sản phẩm phù hợp nhưng nên trao đổi thêm với chuyên viên trước khi dùng."
            } else {
                "Phù hợp với nhu cầu bạn vừa hỏi và đang còn hàng."
            }
        )
    }

    private fun buildProductIntro(product: AiProductContext): String {
        val stockText = if (product.stock > 0) "Hiện sản phẩm còn hàng." else "Hiện sản phẩm đang hết hàng."
        val categoryText = product.categoryName?.takeIf { it.isNotBlank() }?.let { " thuộc nhóm $it" } ?: ""
        val descriptionText = product.shortDescription
            ?.takeIf { it.isNotBlank() }
            ?.let { "\n- Mô tả ngắn: $it" }
            ?: ""
        val consultationText = if (product.requiresConsultation) {
            "\n- Sản phẩm này nên được tư vấn thêm trước khi sử dụng."
        } else {
            ""
        }
        return """
Bạn đang xem ${product.name}$categoryText.
- Giá tham khảo: ${product.priceText}/${product.unit}
- $stockText$descriptionText$consultationText

Bạn có thể hỏi tôi về công dụng, cách dùng an toàn, lưu ý khi chọn mua hoặc sản phẩm thay thế phù hợp.
        """.trimIndent()
    }

    private fun normalizeForSearch(value: String): String {
        val withoutMarks = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return withoutMarks.replace("[^a-z0-9\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun formatPrice(value: Long): String {
        return "%,d VND".format(value).replace(',', '.')
    }

    private suspend fun doGeminiRequest(requestJson: String, key: String, retryOn429: Boolean): String {
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
                    ?: "Xin lỗi, tôi không thể tạo phản hồi lúc này. Vui lòng thử lại."
                429 -> {
                    println("[Gemini] HTTP 429 — rate limited, retryOn429=$retryOn429, body=$responseText")
                    if (retryOn429) {
                        delay(5_000)
                        doGeminiRequest(requestJson, key, retryOn429 = false)
                    } else {
                        "Xin lỗi, dịch vụ AI đang bận. Vui lòng thử lại sau ít phút."
                    }
                }
                else -> {
                    println("[Gemini] HTTP ${httpResponse.status.value}: $responseText")
                    "Xin lỗi, dịch vụ AI tạm thời không khả dụng."
                }
            }
        } catch (e: Exception) {
            println("[Gemini] Exception: ${e.message}")
            "Xin lỗi, đã xảy ra lỗi kết nối. Vui lòng thử lại sau."
        }
    }
}
