CREATE TABLE IF NOT EXISTS product_delete_requests (
    id VARCHAR(36) PRIMARY KEY,
    shop_id VARCHAR(36) NOT NULL,
    product_id VARCHAR(36) NOT NULL,
    requested_by_user_id VARCHAR(36) NOT NULL,
    reason LONGTEXT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewed_by_user_id VARCHAR(36) NULL,
    reviewed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_product_delete_requests_shop_status (shop_id, status),
    CONSTRAINT fk_product_delete_requests_shop FOREIGN KEY (shop_id) REFERENCES shops(id),
    CONSTRAINT fk_product_delete_requests_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_product_delete_requests_requested_by FOREIGN KEY (requested_by_user_id) REFERENCES users(id),
    CONSTRAINT fk_product_delete_requests_reviewed_by FOREIGN KEY (reviewed_by_user_id) REFERENCES users(id)
);

