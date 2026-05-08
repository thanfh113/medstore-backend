package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.ChatMessagesTable
import com.example.nhathuoc.database.tables.ChatSessionsTable
import com.example.nhathuoc.database.tables.EmployeeProfilesTable
import com.example.nhathuoc.database.tables.ProductImagesTable
import com.example.nhathuoc.database.tables.ProductsTable
import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.util.AppRoles
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

@Serializable
data class ChatSessionDto(
    val id: String,
    val userId: String,
    val userName: String? = null,
    val userPhone: String? = null,
    val userEmail: String? = null,
    val productId: String? = null,
    val status: String,
    val createdAt: String,
    val lastMessage: ChatMessageDto? = null,
    val productName: String? = null,
    val productImageUrl: String? = null,
    val productPrice: Double? = null,
    val productUnit: String? = null,
    val consultantId: String? = null,
    val consultantName: String? = null,
    val consultantRole: String? = null,
    val consultantQualificationTitle: String? = null,
    val consultantQualificationSpecialty: String? = null,
    val consultantQualificationInstitution: String? = null,
    val consultantQualificationDocumentUrl: String? = null,
    val consultantQualificationDocumentType: String? = null,
    val consultantVerified: Boolean? = null
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val sessionId: String,
    val senderId: String,
    val senderName: String? = null,
    val senderRole: String? = null,
    val content: String? = null,
    val type: String,
    val metadata: String? = null,
    val createdAt: String
)

@Serializable
data class CreateChatSessionRequest(
    val productId: String? = null
)

@Serializable
data class SendChatMessageRequest(
    val content: String,
    val type: String = "TEXT",
    val metadata: String? = null
)

@Serializable
data class UpdateChatSessionStatusRequest(
    val status: String
)

@Serializable
data class ChatSessionDetailDto(
    val session: ChatSessionDto,
    val messages: List<ChatMessageDto>
)

class ChatService {
    private val notificationService = NotificationService()

    fun createOrResumeSession(userId: String, productId: String? = null): ChatSessionDto {
        return transaction {
            requireUser(userId)
            requireProductIfProvided(productId)

            val existingSession = ChatSessionsTable
                .selectAll()
                .where {
                    (ChatSessionsTable.userId eq userId) and
                        ((ChatSessionsTable.status eq SESSION_STATUS_PENDING) or
                            (ChatSessionsTable.status eq SESSION_STATUS_ASSIGNED)) and
                        if (productId != null) {
                            ChatSessionsTable.productId eq productId
                        } else {
                            ChatSessionsTable.productId.isNull()
                        }
                }
                .orderBy(ChatSessionsTable.createdAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()

            if (existingSession != null) {
                return@transaction mapSession(
                    existingSession,
                    getLastMessageInternal(existingSession[ChatSessionsTable.id])
                )
            }

            val sessionId = UUID.randomUUID().toString()
            ChatSessionsTable.insert {
                it[id] = sessionId
                it[ChatSessionsTable.userId] = userId
                it[ChatSessionsTable.productId] = productId
                it[status] = SESSION_STATUS_PENDING
            }

            val createdSession = ChatSessionsTable
                .selectAll()
                .where { ChatSessionsTable.id eq sessionId }
                .single()

            mapSession(createdSession)
        }
    }

    fun getSessionById(sessionId: String): ChatSessionDto? {
        return transaction {
            ChatSessionsTable
                .selectAll()
                .where { ChatSessionsTable.id eq sessionId }
                .singleOrNull()
                ?.let { mapSession(it, getLastMessageInternal(sessionId)) }
        }
    }

    fun getUserSessions(userId: String): List<ChatSessionDto> {
        return transaction {
            requireUser(userId)

            ChatSessionsTable
                .selectAll()
                .where { ChatSessionsTable.userId eq userId }
                .orderBy(ChatSessionsTable.createdAt, SortOrder.DESC)
                .map { row ->
                    val sessionId = row[ChatSessionsTable.id]
                    mapSession(row, getLastMessageInternal(sessionId))
                }
        }
    }

    fun getQueueSessions(status: String? = null): List<ChatSessionDto> {
        return transaction {
            status?.let(::requireValidSessionStatus)

            val query = if (status != null) {
                ChatSessionsTable.selectAll().where { ChatSessionsTable.status eq status }
            } else {
                ChatSessionsTable.selectAll()
            }

            query
                .orderBy(ChatSessionsTable.createdAt, SortOrder.DESC)
                .map { row ->
                    val sessionId = row[ChatSessionsTable.id]
                    mapSession(row, getLastMessageInternal(sessionId))
                }
        }
    }

    fun getMessages(sessionId: String, limit: Int = 50, offset: Long = 0): List<ChatMessageDto> {
        require(limit > 0) { "Limit must be greater than 0" }
        require(offset >= 0) { "Offset must be non-negative" }

        return transaction {
            getSessionRowOrThrow(sessionId)

            baseMessageQuery()
                .where { ChatMessagesTable.sessionId eq sessionId }
                .orderBy(ChatMessagesTable.createdAt, SortOrder.ASC)
                .limit(limit, offset)
                .map(::mapMessageWithSender)
        }
    }

    fun saveMessage(
        sessionId: String,
        senderId: String,
        content: String,
        type: String = MESSAGE_TYPE_TEXT,
        metadata: String? = null
    ): ChatMessageDto {
        require(content.isNotBlank()) { "Message content must not be blank" }

        return transaction {
            val session = requireActiveSession(sessionId)
            val sender = requireUser(senderId)
            val senderRole = sender[UsersTable.role]
            val normalizedType = normalizeMessageType(type)

            if (senderRole == AppRoles.ADMIN) {
                throw IllegalStateException("Admin chỉ được giám sát phiên tư vấn, không được trực tiếp trả lời khách.")
            }
            if (senderRole == AppRoles.EMPLOYEE) {
                val assignedConsultant = getAssignedConsultantInternal(sessionId)
                if (assignedConsultant != null && assignedConsultant.id != senderId) {
                    throw IllegalStateException("Phiên tư vấn đã được phụ trách bởi ${assignedConsultant.name}.")
                }
            }

            val messageId = UUID.randomUUID().toString()
            ChatMessagesTable.insert {
                it[id] = messageId
                it[ChatMessagesTable.sessionId] = sessionId
                it[ChatMessagesTable.senderId] = senderId
                it[ChatMessagesTable.content] = content.trim()
                it[ChatMessagesTable.type] = normalizedType
                it[ChatMessagesTable.metadata] = metadata
            }

            if (session[ChatSessionsTable.status] == SESSION_STATUS_PENDING && senderRole in AppRoles.internalRoles) {
                ChatSessionsTable.update({ ChatSessionsTable.id eq sessionId }) {
                    it[status] = SESSION_STATUS_ASSIGNED
                }
            }

            if (senderRole in AppRoles.internalRoles && session[ChatSessionsTable.userId] != senderId) {
                notificationService.createUserNotification(
                    userId = session[ChatSessionsTable.userId],
                    title = "Tư vấn viên đã trả lời",
                    body = content.trim().take(160),
                    type = "CHAT",
                    refId = sessionId
                )
            }

            baseMessageQuery()
                .where { ChatMessagesTable.id eq messageId }
                .single()
                .let(::mapMessageWithSender)
        }
    }

    fun updateSessionStatus(sessionId: String, status: String): ChatSessionDto {
        return transaction {
            requireValidSessionStatus(status)
            getSessionRowOrThrow(sessionId)

            ChatSessionsTable.update({ ChatSessionsTable.id eq sessionId }) {
                it[ChatSessionsTable.status] = status
            }

            val updated = ChatSessionsTable
                .selectAll()
                .where { ChatSessionsTable.id eq sessionId }
                .single()

            mapSession(updated, getLastMessageInternal(sessionId))
        }
    }

    fun assignSession(sessionId: String, staffId: String? = null): ChatSessionDto {
        return transaction {
            val session = getSessionRowOrThrow(sessionId)
            if (session[ChatSessionsTable.status] == SESSION_STATUS_RESOLVED) {
                throw IllegalStateException("Chat session is no longer active")
            }
            if (!staffId.isNullOrBlank()) {
                val staff = requireUser(staffId)
                val role = staff[UsersTable.role]
                if (role == AppRoles.ADMIN) {
                    throw IllegalStateException("Admin chỉ được xem và giám sát, không được nhận phiên tư vấn.")
                }
                if (role != AppRoles.EMPLOYEE) {
                    throw IllegalStateException("Chỉ nhân viên chuyên môn được nhận phiên tư vấn.")
                }
                val assignedConsultant = getAssignedConsultantInternal(sessionId)
                if (assignedConsultant != null && assignedConsultant.id != staffId) {
                    throw IllegalStateException("Phiên tư vấn đã được phụ trách bởi ${assignedConsultant.name}.")
                }
            }

            ChatSessionsTable.update({ ChatSessionsTable.id eq sessionId }) {
                it[status] = SESSION_STATUS_ASSIGNED
            }

            val updated = ChatSessionsTable
                .selectAll()
                .where { ChatSessionsTable.id eq sessionId }
                .single()

            mapSession(updated, getLastMessageInternal(sessionId))
        }
    }

    fun resolveSession(sessionId: String): ChatSessionDto = updateSessionStatus(sessionId, SESSION_STATUS_RESOLVED)

    fun closeSession(sessionId: String): ChatSessionDto = resolveSession(sessionId)

    fun getLastMessage(sessionId: String): ChatMessageDto? {
        return transaction {
            getLastMessageInternal(sessionId)
        }
    }

    private fun mapSession(row: ResultRow, lastMessage: ChatMessageDto? = null): ChatSessionDto {
        val product = row[ChatSessionsTable.productId]?.let(::getProductSnapshotInternal)
        val customer = getCustomerSnapshotInternal(row[ChatSessionsTable.userId])
        val consultant = getAssignedConsultantInternal(row[ChatSessionsTable.id])
        return ChatSessionDto(
            id = row[ChatSessionsTable.id],
            userId = row[ChatSessionsTable.userId],
            userName = customer?.name,
            userPhone = customer?.phone,
            userEmail = customer?.email,
            productId = row[ChatSessionsTable.productId],
            status = row[ChatSessionsTable.status],
            createdAt = row[ChatSessionsTable.createdAt].toString(),
            lastMessage = lastMessage,
            productName = product?.name,
            productImageUrl = product?.imageUrl,
            productPrice = product?.price,
            productUnit = product?.unit,
            consultantId = consultant?.id,
            consultantName = consultant?.name,
            consultantRole = consultant?.role,
            consultantQualificationTitle = consultant?.qualificationTitle,
            consultantQualificationSpecialty = consultant?.qualificationSpecialty,
            consultantQualificationInstitution = consultant?.qualificationInstitution,
            consultantQualificationDocumentUrl = consultant?.qualificationDocumentUrl,
            consultantQualificationDocumentType = consultant?.qualificationDocumentType,
            consultantVerified = consultant?.verified
        )
    }

    private fun mapMessageWithSender(row: ResultRow): ChatMessageDto {
        val fullName = row.getOrNull(UsersTable.fullName)
        val email = row.getOrNull(UsersTable.email)
        val phone = row.getOrNull(UsersTable.phone)

        return ChatMessageDto(
            id = row[ChatMessagesTable.id],
            sessionId = row[ChatMessagesTable.sessionId],
            senderId = row[ChatMessagesTable.senderId],
            senderName = fullName ?: email ?: phone,
            senderRole = row.getOrNull(UsersTable.role),
            content = row[ChatMessagesTable.content],
            type = row[ChatMessagesTable.type],
            metadata = row[ChatMessagesTable.metadata],
            createdAt = row[ChatMessagesTable.createdAt].toString()
        )
    }

    private fun normalizeMessageType(type: String): String {
        val normalized = type.trim().uppercase()
        return when (normalized) {
            "PRODUCT_RECOMMENDATION" -> MESSAGE_TYPE_PRODUCT_CARD
            "" -> MESSAGE_TYPE_TEXT
            else -> normalized.take(20)
        }
    }

    private fun getSessionRowOrThrow(sessionId: String): ResultRow {
        return ChatSessionsTable
            .selectAll()
            .where { ChatSessionsTable.id eq sessionId }
            .singleOrNull()
            ?: throw NoSuchElementException("Chat session not found")
    }

    private fun requireActiveSession(sessionId: String): ResultRow {
        val session = getSessionRowOrThrow(sessionId)
        if (session[ChatSessionsTable.status] !in ACTIVE_SESSION_STATUSES) {
            throw IllegalStateException("Chat session is no longer active")
        }
        return session
    }

    private fun requireUser(userId: String): ResultRow {
        return UsersTable
            .selectAll()
            .where { UsersTable.id eq userId }
            .singleOrNull()
            ?: throw NoSuchElementException("User not found")
    }

    private fun requireProductIfProvided(productId: String?) {
        if (productId == null) return

        ProductsTable
            .selectAll()
            .where { ProductsTable.id eq productId }
            .singleOrNull()
            ?: throw NoSuchElementException("Product not found")
    }

    private fun requireValidSessionStatus(status: String) {
        require(status in ALL_SESSION_STATUSES) {
            "Unsupported chat session status: $status"
        }
    }

    private fun getLastMessageInternal(sessionId: String): ChatMessageDto? {
        return baseMessageQuery()
            .where { ChatMessagesTable.sessionId eq sessionId }
            .orderBy(ChatMessagesTable.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let(::mapMessageWithSender)
    }

    private fun baseMessageQuery(): Query {
        return ChatMessagesTable.join(
            UsersTable,
            JoinType.INNER,
            additionalConstraint = { ChatMessagesTable.senderId eq UsersTable.id }
        ).selectAll()
    }

    private data class ChatProductSnapshot(
        val name: String,
        val imageUrl: String?,
        val price: Double,
        val unit: String
    )

    private data class ChatCustomerSnapshot(
        val name: String?,
        val phone: String?,
        val email: String?
    )

    private data class AssignedConsultant(
        val id: String,
        val name: String,
        val role: String,
        val qualificationTitle: String?,
        val qualificationSpecialty: String?,
        val qualificationInstitution: String?,
        val qualificationDocumentUrl: String?,
        val qualificationDocumentType: String?,
        val verified: Boolean?
    )

    private fun getProductSnapshotInternal(productId: String): ChatProductSnapshot? {
        return ProductsTable
            .join(
                ProductImagesTable,
                JoinType.LEFT,
                additionalConstraint = { ProductsTable.id eq ProductImagesTable.productId }
            )
            .selectAll()
            .where { ProductsTable.id eq productId }
            .orderBy(ProductImagesTable.sortOrder, SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.let { row ->
                ChatProductSnapshot(
                    name = row[ProductsTable.name],
                    imageUrl = row.getOrNull(ProductImagesTable.url),
                    price = row[ProductsTable.price].toDouble(),
                    unit = row[ProductsTable.unit]
                )
            }
    }

    private fun getCustomerSnapshotInternal(userId: String): ChatCustomerSnapshot? {
        return UsersTable
            .selectAll()
            .where { UsersTable.id eq userId }
            .singleOrNull()
            ?.let { row ->
                val fullName = row[UsersTable.fullName]?.takeIf { it.isNotBlank() }
                val phone = row[UsersTable.phone].takeIf { it.isNotBlank() }
                val email = row[UsersTable.email]?.takeIf { it.isNotBlank() }
                ChatCustomerSnapshot(
                    name = fullName ?: phone ?: email,
                    phone = phone,
                    email = email
                )
            }
    }

    private fun getAssignedConsultantInternal(sessionId: String): AssignedConsultant? {
        return ChatMessagesTable
            .join(
                UsersTable,
                JoinType.INNER,
                additionalConstraint = { ChatMessagesTable.senderId eq UsersTable.id }
            )
            .join(
                EmployeeProfilesTable,
                JoinType.LEFT,
                additionalConstraint = { UsersTable.id eq EmployeeProfilesTable.userId }
            )
            .selectAll()
            .where {
                (ChatMessagesTable.sessionId eq sessionId) and
                    (UsersTable.role eq AppRoles.EMPLOYEE)
            }
            .orderBy(ChatMessagesTable.createdAt, SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.let { row ->
                val fullName = row.getOrNull(UsersTable.fullName)
                val email = row.getOrNull(UsersTable.email)
                val phone = row.getOrNull(UsersTable.phone)
                AssignedConsultant(
                    id = row[UsersTable.id],
                    name = fullName ?: email ?: phone ?: "Nhân viên chuyên môn",
                    role = row[UsersTable.role],
                    qualificationTitle = row.getOrNull(EmployeeProfilesTable.qualificationTitle),
                    qualificationSpecialty = row.getOrNull(EmployeeProfilesTable.qualificationSpecialty),
                    qualificationInstitution = row.getOrNull(EmployeeProfilesTable.qualificationInstitution),
                    qualificationDocumentUrl = row.getOrNull(EmployeeProfilesTable.qualificationDocumentUrl),
                    qualificationDocumentType = row.getOrNull(EmployeeProfilesTable.qualificationDocumentType),
                    verified = row.getOrNull(EmployeeProfilesTable.qualificationVerified)
                )
            }
    }

    companion object {
        const val SESSION_STATUS_PENDING = "PENDING"
        const val SESSION_STATUS_ASSIGNED = "ASSIGNED"
        const val SESSION_STATUS_RESOLVED = "RESOLVED"
        const val MESSAGE_TYPE_TEXT = "TEXT"
        const val MESSAGE_TYPE_PRODUCT_CARD = "PRODUCT_CARD"

        val ACTIVE_SESSION_STATUSES = setOf(
            SESSION_STATUS_PENDING,
            SESSION_STATUS_ASSIGNED
        )

        val ALL_SESSION_STATUSES = setOf(
            SESSION_STATUS_PENDING,
            SESSION_STATUS_ASSIGNED,
            SESSION_STATUS_RESOLVED
        )
    }
}
