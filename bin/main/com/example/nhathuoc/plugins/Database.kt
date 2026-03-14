package com.example.nhathuoc.plugins

import com.example.nhathuoc.database.tables.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

fun Application.configureDatabase() {
    val dbUrl      = environment.config.property("database.url").getString()
    val dbDriver   = environment.config.property("database.driver").getString()
    val dbUser     = environment.config.property("database.user").getString()
    val dbPassword = environment.config.property("database.password").getString()
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
            UsersTable,
            RefreshTokensTable,
            ShopsTable,
            PharmacyBranchesTable,
            CategoriesTable,
            ProductsTable,
            ProductImagesTable,
            ProductCertificatesTable,
            DiseaseCategoriesTable,
            ProductDiseasesTable,
            CartItemsTable,
            UserAddressesTable,
            OrdersTable,
            OrderItemsTable,
            PrescriptionsTable,
            RewardAccountsTable,
            RewardTransactionsTable,
            RewardProductsTable,
            RewardRedemptionsTable,
            VaccinesTable,
            VaccineBookingsTable,
            ChatSessionsTable,
            ChatMessagesTable,
            ReviewsTable,
            NotificationsTable,
            BannersTable,
            HealthArticlesTable,
            PaymentMethodsTable,
            PaymentsTable
        )
    }

    log.info("Database connected and tables created.")
}
