ALTER TABLE product_images
    ADD COLUMN cloudinary_public_id VARCHAR(255) NULL AFTER url;
