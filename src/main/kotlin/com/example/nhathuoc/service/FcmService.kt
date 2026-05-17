package com.example.nhathuoc.service

import com.example.nhathuoc.database.tables.UsersTable
import com.example.nhathuoc.util.Env
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream

object FcmService {

    private val log = LoggerFactory.getLogger(FcmService::class.java)
    private var messaging: FirebaseMessaging? = null

    fun init() {
        val serviceAccountPath = Env.get("FIREBASE_SERVICE_ACCOUNT_PATH")
        if (serviceAccountPath.isNullOrBlank()) {
            log.warn("FIREBASE_SERVICE_ACCOUNT_PATH not set — FCM push notifications disabled")
            return
        }
        val file = File(serviceAccountPath)
        if (!file.exists()) {
            log.warn("Firebase service account file not found at '$serviceAccountPath' — FCM disabled")
            return
        }
        try {
            val credentials = GoogleCredentials.fromStream(FileInputStream(file))
            val options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .build()
            val app = if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options)
            } else {
                FirebaseApp.getInstance()
            }
            messaging = FirebaseMessaging.getInstance(app)
            log.info("Firebase Admin SDK initialized — FCM push notifications enabled")
        } catch (e: Exception) {
            log.error("Failed to initialize Firebase Admin SDK: ${e.message}")
        }
    }

    /**
     * Send a push notification to a specific user by userId.
     * Looks up their FCM token from the database. No-ops if FCM is not configured
     * or the user has no registered token.
     */
    fun sendToUser(
        userId: String,
        title: String,
        body: String,
        type: String,
        refId: String? = null,
        notificationId: String? = null
    ) {
        val fcm = messaging ?: return

        val fcmToken = transaction {
            UsersTable.selectAll()
                .where { UsersTable.id eq userId }
                .firstOrNull()
                ?.get(UsersTable.fcmToken)
        }
        if (fcmToken.isNullOrBlank()) return

        try {
            val message = Message.builder()
                .setToken(fcmToken)
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build()
                )
                .putData("type", type)
                .apply { if (!refId.isNullOrBlank()) putData("refId", refId) }
                .apply { if (!notificationId.isNullOrBlank()) putData("notificationId", notificationId) }
                .build()

            fcm.send(message)
            log.debug("FCM sent to userId=$userId type=$type")
        } catch (e: Exception) {
            log.warn("FCM send failed for userId=$userId: ${e.message}")
        }
    }
}
