USE medical_supplies_db;
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET SQL_SAFE_UPDATES = 0;
SET FOREIGN_KEY_CHECKS = 0;

-- ─────────────────────────────────────────────────────────────
-- STEP 1: Move products from child categories → parent category
-- ─────────────────────────────────────────────────────────────
UPDATE products p
JOIN categories c ON c.id = p.category_id
SET p.category_id = c.parent_id
WHERE c.parent_id IS NOT NULL;

-- ─────────────────────────────────────────────────────────────
-- STEP 2: Delete all subcategories (parent_id IS NOT NULL)
-- ─────────────────────────────────────────────────────────────
DELETE FROM categories WHERE parent_id IS NOT NULL;

SET FOREIGN_KEY_CHECKS = 1;
SET SQL_SAFE_UPDATES = 1;

-- ─────────────────────────────────────────────────────────────
-- VERIFY: Should show exactly 8 rows, all parent_id = NULL
-- ─────────────────────────────────────────────────────────────
SELECT id, name, parent_id, sort_order, is_active
FROM categories
ORDER BY sort_order;

-- VERIFY products: all category_id should be one of the 8 parent IDs
SELECT p.id, p.name, p.category_id, c.name AS category_name
FROM products p
LEFT JOIN categories c ON c.id = p.category_id
ORDER BY p.category_id;
