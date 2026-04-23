ALTER TABLE products
    ADD COLUMN short_description TEXT NULL AFTER slug,
    ADD COLUMN manufacturer VARCHAR(200) NULL AFTER brand,
    ADD COLUMN target_audience VARCHAR(50) NOT NULL DEFAULT 'ALL' AFTER requires_consultation;

ALTER TABLE product_images
    ADD COLUMN media_type VARCHAR(20) NOT NULL DEFAULT 'IMAGE' AFTER url;
