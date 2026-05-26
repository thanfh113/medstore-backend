USE medical_supplies_db;

ALTER TABLE products
    ADD COLUMN contact_for_price BOOLEAN NOT NULL DEFAULT FALSE;
