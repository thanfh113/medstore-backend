USE medical_supplies_db;
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET SQL_SAFE_UPDATES = 0;
SET FOREIGN_KEY_CHECKS = 0;

-- ─────────────────────────────────────────────────────────────
-- STEP 0: Show current state (run SELECT first to verify)
-- ─────────────────────────────────────────────────────────────
-- SELECT id, name, parent_id, sort_order FROM categories ORDER BY parent_id IS NOT NULL, sort_order;
-- SELECT category_id, COUNT(*) cnt FROM products GROUP BY category_id;

-- ─────────────────────────────────────────────────────────────
-- STEP 1: Ensure the 8 core parent categories are correct
-- ─────────────────────────────────────────────────────────────
INSERT INTO categories (id, parent_id, name, slug, description, icon_url, sort_order, is_active)
VALUES
    ('cat-supplies',         NULL, 'Dụng cụ tiêm truyền',   'dung-cu-tiem-truyen',   'Bơm tiêm, kim tiêm, dây truyền dịch, ống thông', NULL, 1, 1),
    ('cat-bandage',          NULL, 'Băng gạc - Cầm máu',    'bang-gac-cam-mau',      'Băng gạc, băng dính, băng cuộn y tế',             NULL, 2, 1),
    ('cat-device',           NULL, 'Thiết bị chẩn đoán',    'thiet-bi-chan-doan',    'Máy đo huyết áp, nhiệt kế, máy SpO2',             NULL, 3, 1),
    ('cat-protect',          NULL, 'Khẩu trang - PPE',      'khau-trang-ppe',        'Khẩu trang y tế, găng tay, quần áo bảo hộ',      NULL, 4, 1),
    ('cat-instrument',       NULL, 'Thiết bị phẫu thuật',   'thiet-bi-phau-thuat',  'Dụng cụ vi phẫu, kẹp, dây khâu',                 NULL, 5, 1),
    ('cat-infection-control',NULL, 'Chống nhiễm khuẩn',     'chong-nhiem-khuan',    'Dung dịch sát khuẩn, khử khuẩn, tiệt khuẩn',    NULL, 6, 1),
    ('cat-therapy',          NULL, 'Phục hồi chức năng',    'phuc-hoi-chuc-nang',   'Nạng, xe lăn, nẹp chỉnh hình, vật lý trị liệu', NULL, 7, 1),
    ('cat-lab',              NULL, 'Vật tư xét nghiệm',     'vat-tu-xet-nghiem',    'Kit xét nghiệm, vật tư phòng lab, dụng cụ lấy mẫu', NULL, 8, 1)
ON DUPLICATE KEY UPDATE
    parent_id  = NULL,
    name       = VALUES(name),
    slug       = VALUES(slug),
    sort_order = VALUES(sort_order),
    is_active  = 1;

-- ─────────────────────────────────────────────────────────────
-- STEP 2: Ensure child categories exist and have correct parent IDs
-- ─────────────────────────────────────────────────────────────
INSERT INTO categories (id, parent_id, name, slug, sort_order, is_active)
VALUES
    -- cat-supplies children
    ('cat-syringe',             'cat-supplies', 'Bơm tiêm - Ống xi lanh',        'bom-tiem-ong-xi-lanh',      1, 1),
    ('cat-needle',              'cat-supplies', 'Kim tiêm',                        'kim-tiem',                  2, 1),
    ('cat-infusion-set',        'cat-supplies', 'Dây truyền dịch',                 'day-truyen-dich',           3, 1),
    ('cat-tube',                'cat-supplies', 'Ống thông',                        'ong-thong',                 4, 1),
    -- cat-bandage children
    ('cat-sterile-gauze',       'cat-bandage',  'Gạc vô trùng',                    'gac-vo-trung',              1, 1),
    ('cat-medical-tape',        'cat-bandage',  'Băng dính y tế',                  'bang-dinh-y-te',            2, 1),
    ('cat-bandage-roll',        'cat-bandage',  'Băng cuộn',                       'bang-cuon',                 3, 1),
    ('cat-antimicrobial-dressing','cat-bandage','Băng keo thấm tẩm kháng sinh',    'bang-keo-khang-sinh',       4, 1),
    -- cat-device children
    ('cat-monitor',             'cat-device',   'Máy theo dõi - Máy thở',          'may-theo-doi-may-tho',      1, 1),
    ('cat-blood-pressure',      'cat-device',   'Máy đo huyết áp',                 'may-do-huyet-ap',           2, 1),
    ('cat-thermometer',         'cat-device',   'Nhiệt kế y tế',                   'nhiet-ke-y-te',             3, 1),
    ('cat-spo2',                'cat-device',   'Máy đo SpO2',                     'may-do-spo2',               4, 1),
    ('cat-glucose-meter',       'cat-device',   'Máy đo đường huyết',              'may-do-duong-huyet',        5, 1),
    -- cat-protect children
    ('cat-mask',                'cat-protect',  'Khẩu trang y tế',                 'khau-trang-y-te',           1, 1),
    ('cat-n95-mask',            'cat-protect',  'Khẩu trang N95',                  'khau-trang-n95',            2, 1),
    ('cat-gloves',              'cat-protect',  'Găng tay y tế',                   'gang-tay-y-te',             3, 1),
    ('cat-protective-clothing', 'cat-protect',  'Quần áo bảo hộ',                  'quan-ao-bao-ho',            4, 1),
    ('cat-goggles',             'cat-protect',  'Kính bảo hộ',                     'kinh-bao-ho',               5, 1),
    -- cat-instrument children
    ('cat-surgical-tools',      'cat-instrument','Dụng cụ vi phẫu',                'dung-cu-vi-phau',           1, 1),
    ('cat-forceps',             'cat-instrument','Kẹp phẫu thuật',                 'kep-phau-thuat',            2, 1),
    ('cat-suture',              'cat-instrument','Dây khâu',                        'day-khau',                  3, 1),
    ('cat-hemostatic-valve',    'cat-instrument','Van cầm máu',                    'van-cam-mau',               4, 1),
    -- cat-infection-control children
    ('cat-sanitizer',           'cat-infection-control', 'Dung dịch sát khuẩn',   'dung-dich-sat-khuan',       1, 1),
    ('cat-disinfectant',        'cat-infection-control', 'Dung dịch khử khuẩn',   'dung-dich-khu-khuan',       2, 1),
    ('cat-sterilization',       'cat-infection-control', 'Vật tư tiệt khuẩn',     'vat-tu-tiet-khuan',         3, 1),
    -- cat-therapy children
    ('cat-crutch-wheelchair',   'cat-therapy',  'Nạng - Xe lăn',                   'nang-xe-lan',               1, 1),
    ('cat-physio-tools',        'cat-therapy',  'Dụng cụ vật lý trị liệu',         'dung-cu-vat-ly-tri-lieu',   2, 1),
    ('cat-orthopedic-brace',    'cat-therapy',  'Nẹp chỉnh hình',                  'nep-chinh-hinh',            3, 1),
    -- cat-lab children
    ('cat-test-kit',            'cat-lab',      'Kit xét nghiệm',                  'kit-xet-nghiem',            1, 1),
    ('cat-lab-consumables',     'cat-lab',      'Vật tư phòng xét nghiệm',         'vat-tu-phong-xet-nghiem',   2, 1),
    ('cat-sample-container',    'cat-lab',      'Dụng cụ lấy mẫu',                 'dung-cu-lay-mau',           3, 1)
ON DUPLICATE KEY UPDATE
    parent_id  = VALUES(parent_id),
    name       = VALUES(name),
    slug       = VALUES(slug),
    sort_order = VALUES(sort_order),
    is_active  = 1;

-- ─────────────────────────────────────────────────────────────
-- STEP 3: Reassign products from extra/unknown categories
--         to the nearest matching parent category
-- ─────────────────────────────────────────────────────────────

-- Products in old cat-diagnostic → cat-device
UPDATE products SET category_id = 'cat-device'
WHERE category_id = 'cat-diagnostic';

-- Products in old top-level cat-monitor → cat-device
-- (only if cat-monitor was a top-level category; now it's a child of cat-device)
-- No change needed for products—cat-monitor is still valid as a child category.

-- Catch-all: any product in a category NOT in our keep list → cat-supplies
UPDATE products
SET category_id = 'cat-supplies'
WHERE category_id IS NOT NULL
  AND category_id NOT IN (
    'cat-supplies', 'cat-bandage', 'cat-device', 'cat-protect',
    'cat-instrument', 'cat-infection-control', 'cat-therapy', 'cat-lab',
    'cat-syringe', 'cat-needle', 'cat-infusion-set', 'cat-tube',
    'cat-sterile-gauze', 'cat-medical-tape', 'cat-bandage-roll', 'cat-antimicrobial-dressing',
    'cat-monitor', 'cat-blood-pressure', 'cat-thermometer', 'cat-spo2', 'cat-glucose-meter',
    'cat-mask', 'cat-n95-mask', 'cat-gloves', 'cat-protective-clothing', 'cat-goggles',
    'cat-surgical-tools', 'cat-forceps', 'cat-suture', 'cat-hemostatic-valve',
    'cat-sanitizer', 'cat-disinfectant', 'cat-sterilization',
    'cat-crutch-wheelchair', 'cat-physio-tools', 'cat-orthopedic-brace',
    'cat-test-kit', 'cat-lab-consumables', 'cat-sample-container'
);

-- ─────────────────────────────────────────────────────────────
-- STEP 4: Delete extra categories not in our keep list
--         (children of extra parents first, then extra parents)
-- ─────────────────────────────────────────────────────────────

-- Delete children of extra parent categories first
DELETE FROM categories
WHERE parent_id IS NOT NULL
  AND parent_id NOT IN (
    'cat-supplies', 'cat-bandage', 'cat-device', 'cat-protect',
    'cat-instrument', 'cat-infection-control', 'cat-therapy', 'cat-lab'
  );

-- Delete extra parent categories (parentId IS NULL, not in the 8 core)
DELETE FROM categories
WHERE parent_id IS NULL
  AND id NOT IN (
    'cat-supplies', 'cat-bandage', 'cat-device', 'cat-protect',
    'cat-instrument', 'cat-infection-control', 'cat-therapy', 'cat-lab'
  );

-- ─────────────────────────────────────────────────────────────
-- STEP 5: Clean up orphaned category_attributes (if table exists)
-- ─────────────────────────────────────────────────────────────
DELETE FROM category_attributes
WHERE category_id NOT IN (SELECT id FROM categories);

SET FOREIGN_KEY_CHECKS = 1;
SET SQL_SAFE_UPDATES = 1;

-- ─────────────────────────────────────────────────────────────
-- VERIFY
-- ─────────────────────────────────────────────────────────────
SELECT id, name, parent_id, sort_order, is_active
FROM categories
ORDER BY parent_id IS NOT NULL, sort_order, name;

SELECT p.id, p.name, p.category_id, c.name AS category_name
FROM products p
LEFT JOIN categories c ON c.id = p.category_id
ORDER BY p.category_id;
