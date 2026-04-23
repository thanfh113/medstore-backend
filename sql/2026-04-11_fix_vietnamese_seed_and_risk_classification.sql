USE medical_supplies_db;

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET SQL_SAFE_UPDATES = 0;

START TRANSACTION;

-- Chuẩn hóa tiếng Việt cho dữ liệu mẫu danh mục
UPDATE categories SET name = 'Băng gạc', description = 'Băng gạc và vật liệu băng bó' WHERE id = 'cat-bandage';
UPDATE categories SET name = 'Thiết bị y tế', description = 'Các loại thiết bị y tế chuyên dụng' WHERE id = 'cat-device';
UPDATE categories SET name = 'Thiết bị chẩn đoán', description = 'Thiết bị hỗ trợ chẩn đoán bệnh' WHERE id = 'cat-diagnostic';
UPDATE categories SET name = 'Dụng cụ y tế', description = 'Các loại dụng cụ và công cụ y tế' WHERE id = 'cat-instrument';
UPDATE categories SET name = 'Máy theo dõi', description = 'Thiết bị theo dõi sinh hiệu bệnh nhân' WHERE id = 'cat-monitor';
UPDATE categories SET name = 'Đồ bảo hộ y tế', description = 'Thiết bị bảo hộ cá nhân cho ngành y tế' WHERE id = 'cat-protect';
UPDATE categories SET name = 'Vật tư tiêu hao', description = 'Vật tư y tế sử dụng một lần' WHERE id = 'cat-supplies';
UPDATE categories SET name = 'Bơm tiêm', description = 'Các loại bơm tiêm và kim' WHERE id = 'cat-syringe';
UPDATE categories SET name = 'Thiết bị điều trị', description = 'Thiết bị hỗ trợ điều trị' WHERE id = 'cat-therapy';
UPDATE categories SET name = 'Ống thông', description = 'Các loại ống thông y tế' WHERE id = 'cat-tube';

-- Chuẩn hóa tiếng Việt cho dữ liệu mẫu sản phẩm
UPDATE products
SET
    name = 'Máy đo huyết áp điện tử OMRON HEM-7120',
    description = 'Máy đo huyết áp bắp tay tự động, màn hình LCD lớn, bộ nhớ 30 lần đo',
    origin = 'Nhật Bản',
    unit = 'Cái'
WHERE id = 'prod-001';

UPDATE products
SET
    name = 'Nhiệt kế hồng ngoại không tiếp xúc BRAUN BNT400',
    description = 'Nhiệt kế hồng ngoại đo trán, độ chính xác cao, màn hình màu',
    origin = 'Đức',
    unit = 'Cái'
WHERE id = 'prod-002';

UPDATE products
SET
    name = 'Máy xông mũi họng OMRON CompAIR C28P',
    description = 'Máy nebulizer piston, tiếng ồn thấp, hiệu quả cao',
    origin = 'Nhật Bản',
    unit = 'Cái'
WHERE id = 'prod-003';

UPDATE products
SET
    name = 'Bơm tiêm 1ml Terumo',
    description = 'Bơm tiêm insulin 1ml, vô trùng, cảm biến thấp',
    origin = 'Malaysia',
    unit = 'Cái'
WHERE id = 'prod-004';

UPDATE products
SET
    name = 'Khẩu trang y tế 3 lớp KIMBERLY',
    description = 'Khẩu trang y tế 3 lớp, kháng khuẩn, thông thoáng',
    origin = 'Thái Lan',
    unit = 'Hộp 50 cái'
WHERE id = 'prod-005';

UPDATE products
SET
    name = 'Găng tay y tế latex không bột ANSELL',
    description = 'Găng tay latex không bột, size M, chống thấm',
    origin = 'Malaysia',
    unit = 'Hộp 100 cái'
WHERE id = 'prod-006';

UPDATE products
SET
    name = 'Kính bảo hộ y tế 3M 2890',
    description = 'Kính bảo hộ chống giọt bắn, chống trầy xước',
    origin = 'Mỹ',
    unit = 'Cái'
WHERE id = 'prod-007';

UPDATE products
SET
    name = 'Áo choàng phẫu thuật SMS',
    description = 'Áo choàng phẫu thuật SMS, không dệt, vô trùng, size L',
    origin = 'Canada',
    unit = 'Cái'
WHERE id = 'prod-008';

COMMIT;

-- Migrate từ is_prescription sang risk_classification
SET @sql_add_risk_classification = (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'products'
              AND column_name = 'risk_classification'
        ),
        'SELECT ''risk_classification already exists''',
        'ALTER TABLE products ADD COLUMN risk_classification VARCHAR(1) NOT NULL DEFAULT ''A'' AFTER registration_number'
    )
);
PREPARE stmt FROM @sql_add_risk_classification;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Demo data dùng mặc định A. Cần rà lại phân loại hồ sơ TTBYT thật trước khi dùng production.
UPDATE products
SET risk_classification = 'A'
WHERE risk_classification IS NULL
   OR TRIM(risk_classification) = ''
   OR UPPER(TRIM(risk_classification)) NOT IN ('A', 'B', 'C', 'D');

SET @sql_drop_is_prescription_index = (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'products'
              AND index_name = 'idx_products_is_prescription'
        ),
        'ALTER TABLE products DROP INDEX idx_products_is_prescription',
        'SELECT ''idx_products_is_prescription already removed'''
    )
);
PREPARE stmt FROM @sql_drop_is_prescription_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql_drop_is_prescription_column = (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'products'
              AND column_name = 'is_prescription'
        ),
        'ALTER TABLE products DROP COLUMN is_prescription',
        'SELECT ''is_prescription already removed'''
    )
);
PREPARE stmt FROM @sql_drop_is_prescription_column;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql_add_risk_classification_index = (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'products'
              AND index_name = 'idx_products_risk_classification'
        ),
        'SELECT ''idx_products_risk_classification already exists''',
        'ALTER TABLE products ADD INDEX idx_products_risk_classification (risk_classification)'
    )
);
PREPARE stmt FROM @sql_add_risk_classification_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET SQL_SAFE_UPDATES = 1;
