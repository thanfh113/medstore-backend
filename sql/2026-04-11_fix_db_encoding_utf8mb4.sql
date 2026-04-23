-- ============================================================================
-- FIX DATABASE ENCODING CORRUPTION - Vietnamese characters
-- Problem: categories/products names stored as mojibake (Thiáº¿t bá»‹)
-- Solution: Alter DB/tables to UTF8MB4, restore proper Vietnamese data
-- ============================================================================

-- Step 1: Set session charset to UTF8MB4
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER DATABASE medical_supplies_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Step 2: Alter all tables to use UTF8MB4
ALTER TABLE categories CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE products CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE product_images CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE health_articles CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Step 3: Fix corrupted Vietnamese data in categories table
SET SQL_SAFE_UPDATES = 0;

UPDATE categories SET
    name = 'Băng gạc',
    description = 'Băng gạc và vật liệu băng bó',
    slug = 'bandage'
WHERE id = 'cat-bandage';

UPDATE categories SET
    name = 'Thiết bị y tế',
    description = 'Các loại thiết bị y tế chuyên dụng',
    slug = 'device'
WHERE id = 'cat-device';

UPDATE categories SET
    name = 'Thiết bị chẩn đoán',
    description = 'Thiết bị hỗ trợ chẩn đoán bệnh',
    slug = 'diagnostic'
WHERE id = 'cat-diagnostic';

UPDATE categories SET
    name = 'Dụng cụ y tế',
    description = 'Các loại dụng cụ và công cụ y tế',
    slug = 'instrument'
WHERE id = 'cat-instrument';

UPDATE categories SET
    name = 'Máy theo dõi',
    description = 'Thiết bị theo dõi sinh hiệu bệnh nhân',
    slug = 'monitor'
WHERE id = 'cat-monitor';

UPDATE categories SET
    name = 'Đồ bảo hộ y tế',
    description = 'Thiết bị bảo hộ cá nhân cho ngành y tế',
    slug = 'protect'
WHERE id = 'cat-protect';

UPDATE categories SET
    name = 'Vật tư tiêu hao',
    description = 'Vật tư y tế sử dụng một lần',
    slug = 'supplies'
WHERE id = 'cat-supplies';

UPDATE categories SET
    name = 'Bơm tiêm',
    description = 'Các loại bơm tiêm và kim',
    slug = 'syringe'
WHERE id = 'cat-syringe';

UPDATE categories SET
    name = 'Thiết bị điều trị',
    description = 'Thiết bị hỗ trợ điều trị',
    slug = 'therapy'
WHERE id = 'cat-therapy';

UPDATE categories SET
    name = 'Ống thông',
    description = 'Các loại ống thông y tế',
    slug = 'tube'
WHERE id = 'cat-tube';

-- Step 4: Fix corrupted Vietnamese data in products table
UPDATE products SET
    name = 'Máy đo huyết áp điện tử OMRON HEM-7120',
    description = 'Máy đo huyết áp bắp tay tự động, màn hình LCD lớn, bộ nhớ 30 lần đo',
    brand = 'OMRON',
    origin = 'Nhật Bản',
    unit = 'Cái'
WHERE id = 'prod-001';

UPDATE products SET
    name = 'Nhiệt kế hồng ngoại không tiếp xúc BRAUN BNT400',
    description = 'Nhiệt kế hồng ngoại đo trán, độ chính xác cao, màn hình màu',
    brand = 'BRAUN',
    origin = 'Đức',
    unit = 'Cái'
WHERE id = 'prod-002';

UPDATE products SET
    name = 'Máy xông mũi họng OMRON CompAIR C28P',
    description = 'Máy nebulizer piston, tiếng ồn thấp, hiệu quả cao',
    brand = 'OMRON',
    origin = 'Nhật Bản',
    unit = 'Cái'
WHERE id = 'prod-003';

UPDATE products SET
    name = 'Bơm tiêm 1ml Terumo',
    description = 'Bơm tiêm insulin 1ml, vô trùng, cảm biến thấp',
    brand = 'Terumo',
    origin = 'Malaysia',
    unit = 'Cái'
WHERE id = 'prod-004';

UPDATE products SET
    name = 'Khẩu trang y tế 3 lớp KIMBERLY',
    description = 'Khẩu trang y tế 3 lớp, kháng khuẩn, thông thoáng',
    brand = 'KIMBERLY',
    origin = 'Thái Lan',
    unit = 'Hộp 50 cái'
WHERE id = 'prod-005';

UPDATE products SET
    name = 'Găng tay y tế latex không bột ANSELL',
    description = 'Găng tay latex không bột, size M, chống thấm',
    brand = 'ANSELL',
    origin = 'Malaysia',
    unit = 'Hộp 100 cái'
WHERE id = 'prod-006';

UPDATE products SET
    name = 'Kính bảo hộ y tế 3M 2890',
    description = 'Kính bảo hộ chống giọt bắn, chống trầy xước',
    brand = '3M',
    origin = 'Mỹ',
    unit = 'Cái'
WHERE id = 'prod-007';

UPDATE products SET
    name = 'Áo choàng phẫu thuật SMS',
    description = 'Áo choàng phẫu thuật SMS, không dệt, vô trùng, size L',
    brand = 'SMS',
    origin = 'Canada',
    unit = 'Cái'
WHERE id = 'prod-008';

-- Step 5: Set risk_classification if not exists
SET @sql_add_risk_classification = (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'products'
              AND column_name = 'risk_classification'
        ),
        'SELECT 1',
        'ALTER TABLE products ADD COLUMN risk_classification VARCHAR(1) NOT NULL DEFAULT "A" AFTER registration_number'
    )
);
PREPARE stmt FROM @sql_add_risk_classification;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Set all products to classification A (default)
UPDATE products SET risk_classification = 'A' WHERE risk_classification IS NULL OR risk_classification = '';

-- Step 6: Drop old is_prescription index if exists
SET @sql_drop_index = (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'products'
              AND index_name = 'idx_products_is_prescription'
        ),
        'ALTER TABLE products DROP INDEX idx_products_is_prescription',
        'SELECT 1'
    )
);
PREPARE stmt FROM @sql_drop_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Step 7: Drop is_prescription column if exists
SET @sql_drop_column = (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'products'
              AND column_name = 'is_prescription'
        ),
        'ALTER TABLE products DROP COLUMN is_prescription',
        'SELECT 1'
    )
);
PREPARE stmt FROM @sql_drop_column;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Step 8: Add risk_classification index
SET @sql_add_risk_index = (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'products'
              AND index_name = 'idx_products_risk_classification'
        ),
        'SELECT 1',
        'ALTER TABLE products ADD INDEX idx_products_risk_classification (risk_classification)'
    )
);
PREPARE stmt FROM @sql_add_risk_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET SQL_SAFE_UPDATES = 1;

-- Step 9: Verify the data is fixed
SELECT 'Categories after fix:' AS status;
SELECT id, name, description FROM categories ORDER BY sort_order, id;

SELECT 'Products after fix:' AS status;
SELECT id, name, brand, origin FROM products ORDER BY id;

SELECT 'Risk classification status:' AS status;
SELECT id, name, risk_classification FROM products WHERE risk_classification IS NOT NULL ORDER BY id LIMIT 5;
