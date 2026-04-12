-- Foundation for Desktop POS + Finance dashboard + Order-level coupons
-- Safe to run multiple times on MySQL 8.x (uses IF NOT EXISTS clauses)

-- 1) Orders: support POS channel, walk-in customer (user_id nullable), cashier tracking, cash settlement
ALTER TABLE orders
    MODIFY COLUMN user_id VARCHAR(36) NULL;

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS order_channel VARCHAR(20) NOT NULL DEFAULT 'ONLINE' COMMENT 'ONLINE|POS' AFTER shop_id,
    ADD COLUMN IF NOT EXISTS cashier_user_id VARCHAR(36) NULL COMMENT 'Nhan vien tao don POS' AFTER branch_id,
    ADD COLUMN IF NOT EXISTS completed_at DATETIME NULL AFTER updated_at,
    ADD COLUMN IF NOT EXISTS cash_received DECIMAL(12,2) NULL COMMENT 'Tien khach dua (POS cash)' AFTER total,
    ADD COLUMN IF NOT EXISTS cash_change DECIMAL(12,2) NULL COMMENT 'Tien thoi lai (POS cash)' AFTER cash_received;

ALTER TABLE orders
    ADD INDEX IF NOT EXISTS idx_orders_channel (order_channel),
    ADD INDEX IF NOT EXISTS idx_orders_cashier (cashier_user_id),
    ADD CONSTRAINT fk_orders_cashier_user_id__id FOREIGN KEY (cashier_user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

-- 2) Coupons: order-level only (phase 1)
CREATE TABLE IF NOT EXISTS coupons (
    id VARCHAR(36) NOT NULL,
    shop_id VARCHAR(36) NOT NULL,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT NULL,
    discount_type VARCHAR(20) NOT NULL COMMENT 'PERCENT|FIXED_AMOUNT',
    discount_value DECIMAL(12,2) NOT NULL,
    min_order_total DECIMAL(12,2) NULL,
    max_discount_amount DECIMAL(12,2) NULL,
    starts_at DATETIME NULL,
    ends_at DATETIME NULL,
    usage_limit INT NULL,
    usage_per_user_limit INT NULL,
    used_count INT NOT NULL DEFAULT 0,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_by_user_id VARCHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_coupons_shop_code (shop_id, code),
    KEY idx_coupons_active_window (shop_id, is_active, starts_at, ends_at),
    KEY idx_coupons_created_by (created_by_user_id),
    CONSTRAINT fk_coupons_shop_id__id FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_coupons_created_by_user_id__id FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- one coupon per order (phase 1)
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS coupon_id VARCHAR(36) NULL AFTER discount,
    ADD INDEX IF NOT EXISTS idx_orders_coupon_id (coupon_id),
    ADD CONSTRAINT fk_orders_coupon_id__id FOREIGN KEY (coupon_id) REFERENCES coupons(id) ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE TABLE IF NOT EXISTS coupon_redemptions (
    id VARCHAR(36) NOT NULL,
    coupon_id VARCHAR(36) NOT NULL,
    order_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NULL,
    applied_discount_amount DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'APPLIED' COMMENT 'APPLIED|REVERTED',
    applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reverted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_coupon_redemptions_order_id (order_id),
    KEY idx_coupon_redemptions_coupon (coupon_id),
    KEY idx_coupon_redemptions_user (user_id),
    CONSTRAINT fk_coupon_redemptions_coupon_id__id FOREIGN KEY (coupon_id) REFERENCES coupons(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_coupon_redemptions_order_id__id FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_coupon_redemptions_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3) Finance (admin): operating costs for net-profit dashboard
CREATE TABLE IF NOT EXISTS expense_categories (
    id VARCHAR(36) NOT NULL,
    shop_id VARCHAR(36) NOT NULL,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_expense_categories_shop_code (shop_id, code),
    CONSTRAINT fk_expense_categories_shop_id__id FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS expenses (
    id VARCHAR(36) NOT NULL,
    shop_id VARCHAR(36) NOT NULL,
    category_id VARCHAR(36) NOT NULL,
    incurred_at DATETIME NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    payment_method VARCHAR(30) NULL COMMENT 'CASH|BANK_TRANSFER|CARD|EWALLET|OTHER',
    description TEXT NULL,
    reference_no VARCHAR(100) NULL,
    created_by_user_id VARCHAR(36) NULL,
    approved_by_user_id VARCHAR(36) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'APPROVED' COMMENT 'DRAFT|APPROVED|VOIDED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_expenses_shop_time (shop_id, incurred_at),
    KEY idx_expenses_category (category_id),
    KEY idx_expenses_status (status),
    CONSTRAINT fk_expenses_shop_id__id FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_expenses_category_id__id FOREIGN KEY (category_id) REFERENCES expense_categories(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_expenses_created_by_user_id__id FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_expenses_approved_by_user_id__id FOREIGN KEY (approved_by_user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed basic expense categories for shop-001
INSERT INTO expense_categories (id, shop_id, code, name, description, is_active)
SELECT 'exp-cat-001', 'shop-001', 'COGS', 'Gia von nhap hang', 'Chi phi nhap vat tu/thuoc', 1
WHERE NOT EXISTS (SELECT 1 FROM expense_categories WHERE id = 'exp-cat-001');

INSERT INTO expense_categories (id, shop_id, code, name, description, is_active)
SELECT 'exp-cat-002', 'shop-001', 'OPERATING', 'Chi phi van hanh', 'Dien nuoc, mat bang, van chuyen', 1
WHERE NOT EXISTS (SELECT 1 FROM expense_categories WHERE id = 'exp-cat-002');

INSERT INTO expense_categories (id, shop_id, code, name, description, is_active)
SELECT 'exp-cat-003', 'shop-001', 'PAYROLL', 'Luong nhan vien', 'Luong va phuc loi nhan su', 1
WHERE NOT EXISTS (SELECT 1 FROM expense_categories WHERE id = 'exp-cat-003');

