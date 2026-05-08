package com.example.nhathuoc.plugins

import com.example.nhathuoc.database.tables.*
import com.example.nhathuoc.util.Env
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

fun Application.configureDatabase() {
    val dbUrl      = Env.get("DB_URL") ?: environment.config.property("database.url").getString()
    val dbDriver   = Env.get("DB_DRIVER") ?: environment.config.property("database.driver").getString()
    val dbUser     = Env.get("DB_USER") ?: environment.config.property("database.user").getString()
    val dbPassword = Env.get("DB_PASSWORD") ?: environment.config.property("database.password").getString()
    val maxPool    = environment.config.property("database.max_pool_size").getString().toInt()

    val config = HikariConfig().apply {
        jdbcUrl         = dbUrl
        driverClassName = dbDriver
        username        = dbUser
        password        = dbPassword
        maximumPoolSize = maxPool
        isAutoCommit    = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    }

    val dataSource = HikariDataSource(config)
    Database.connect(dataSource)

    // Tạo các bảng nếu chưa tồn tại
    transaction {
        SchemaUtils.createMissingTablesAndColumns(
            // Core user & authentication tables
            UsersTable,
            RefreshTokensTable,
            UserVerificationTokensTable,
            EmployeeProfilesTable,

            // Product & category tables
            CategoriesTable,
            ProductsTable,
            ProductImagesTable,
            ProductCertificatesTable,

            // Order & cart tables
            CartItemsTable,
            UserAddressesTable,
            OrdersTable,
            OrderItemsTable,
            CouponsTable,
            CouponRedemptionsTable,

            // Reward system tables
            RewardAccountsTable,
            RewardTransactionsTable,
            RewardProductsTable,
            RewardRedemptionsTable,

            // Chat & AI tables
            ChatSessionsTable,
            ChatMessagesTable,
            ReviewsTable,
            ReviewReportsTable,
            OrderComplaintsTable,
            NotificationsTable,

            // CMS & content tables
            BannersTable,

            // Payment tables
            PaymentsTable
        )

        migrateRefreshTokensTable()
    }

    log.info("Database connected and tables created.")
}

private fun migrateRefreshTokensTable() {
    try {
        execQuietly("ALTER TABLE refresh_tokens ADD COLUMN token_hash VARCHAR(64) NULL AFTER token")
    } catch (e: Exception) {
        // Column already exists
    }
    try {
        execQuietly("ALTER TABLE refresh_tokens ADD COLUMN revoked_at DATETIME NULL AFTER expires_at")
    } catch (e: Exception) {
        // Column already exists
    }
    try {
        execQuietly("ALTER TABLE refresh_tokens ADD COLUMN device_info VARCHAR(255) NULL AFTER revoked_at")
    } catch (e: Exception) {
        // Column already exists
    }
    try {
        execQuietly("ALTER TABLE refresh_tokens ADD COLUMN ip_address VARCHAR(45) NULL AFTER device_info")
    } catch (e: Exception) {
        // Column already exists
    }
    try {
        execQuietly("UPDATE refresh_tokens SET token_hash = SHA2(token, 256) WHERE token_hash IS NULL AND token IS NOT NULL AND token <> ''")
    } catch (e: Exception) {
        // Update failed, continue anyway
    }
}

private fun execQuietly(sql: String) {
    TransactionManager.current().exec(sql)
}
