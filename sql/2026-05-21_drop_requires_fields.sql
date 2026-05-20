USE medical_supplies_db;

ALTER TABLE products
    DROP COLUMN requires_certification,
    DROP COLUMN requires_consultation;
