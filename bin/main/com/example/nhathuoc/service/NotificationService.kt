package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.NotificationsTable
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.insert
import java.util.UUID

class NotificationService {
    fun createUserNotification(
        userId: String,
        title: String,
        body: String? = null,
        type: String,
        refId: String? = null
    ): String {
        val notificationId = UUID.randomUUID().toString()
        NotificationsTable.insert {
            it[id] = notificationId
            it[NotificationsTable.userId] = userId
            it[NotificationsTable.title] = title.take(200)
            it[NotificationsTable.body] = body
            it[NotificationsTable.type] = type.take(30)
            it[NotificationsTable.refId] = refId
            it[isRead] = false
            it[createdAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        }
        return notificationId
    }
}
