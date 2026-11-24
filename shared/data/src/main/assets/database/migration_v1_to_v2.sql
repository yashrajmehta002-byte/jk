-- बचत समूह प्रणाली — Zero-Data-Loss Strict Migration: v1 -> v2 (V1.5 Inventory, Warranty & Service Plugin)
-- नियम HR-09: कोई भी पुरानी V1 टेबल DROP या ALTER-TRUNCATE नहीं होगी; केवल नई टेबल्स जुड़ेंगी।
PRAGMA foreign_keys = ON;

-- 26. products (Admin-Only Registration; QR में कोई संवेदनशील लागत/मूल्य/मार्जिन नहीं)
CREATE TABLE IF NOT EXISTS products (
    product_id TEXT PRIMARY KEY NOT NULL,
    product_name TEXT NOT NULL,
    category TEXT NOT NULL,
    optional_lot_batch_id TEXT,
    cost_paise INTEGER NOT NULL CHECK(cost_paise >= 0),
    sale_price_paise INTEGER NOT NULL CHECK(sale_price_paise > 0),
    stock_qty INTEGER NOT NULL DEFAULT 0 CHECK(stock_qty >= 0),
    qr_token_hash TEXT NOT NULL UNIQUE,
    registered_by_admin_id TEXT NOT NULL REFERENCES users(user_id),
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL
);

-- 27. product_sales (बचत/ऋण से नियंत्रित समायोजन — केवल Admin मंजूरी से)
CREATE TABLE IF NOT EXISTS product_sales (
    sale_id TEXT PRIMARY KEY NOT NULL,
    product_id TEXT NOT NULL REFERENCES products(product_id),
    member_id TEXT REFERENCES members(member_id),
    customer_name TEXT NOT NULL,
    customer_mobile TEXT NOT NULL,
    qty INTEGER NOT NULL CHECK(qty > 0),
    total_amount_paise INTEGER NOT NULL CHECK(total_amount_paise > 0),
    settlement_mode TEXT NOT NULL CHECK(settlement_mode IN ('CASH', 'SAVINGS_OFFSET_APPROVED', 'LOAN_LINKED_APPROVED')),
    approved_by_admin_id TEXT REFERENCES users(user_id),
    journal_id TEXT NOT NULL,
    sold_at INTEGER NOT NULL
);

-- 28. warranty_records
CREATE TABLE IF NOT EXISTS warranty_records (
    warranty_id TEXT PRIMARY KEY NOT NULL,
    sale_id TEXT NOT NULL UNIQUE REFERENCES product_sales(sale_id),
    product_id TEXT NOT NULL REFERENCES products(product_id),
    warranty_start_epoch INTEGER NOT NULL,
    warranty_end_epoch INTEGER NOT NULL,
    coverage_terms TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1
);

-- 29. service_history_logs (Append-Only — पुरानी सर्विस हिस्ट्री कभी Delete/Overwrite नहीं होगी)
CREATE TABLE IF NOT EXISTS service_history_logs (
    service_log_id TEXT PRIMARY KEY NOT NULL,
    product_id TEXT NOT NULL REFERENCES products(product_id),
    sale_id TEXT NOT NULL REFERENCES product_sales(sale_id),
    complaint_text TEXT NOT NULL,
    diagnosis_text TEXT NOT NULL,
    repair_action TEXT NOT NULL,
    parts_replaced TEXT,
    technician_name TEXT NOT NULL,
    charges_paise INTEGER NOT NULL DEFAULT 0 CHECK(charges_paise >= 0),
    status TEXT NOT NULL CHECK(status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    logged_at INTEGER NOT NULL
);

-- Append-Only No-Delete Triggers for Sales & Service History
CREATE TRIGGER IF NOT EXISTS trg_no_delete_product_sales BEFORE DELETE ON product_sales
BEGIN SELECT RAISE(ABORT, 'V1.5: product_sales cannot be deleted'); END;

CREATE TRIGGER IF NOT EXISTS trg_no_delete_service_history BEFORE DELETE ON service_history_logs
BEGIN SELECT RAISE(ABORT, 'V1.5: service_history_logs is append-only'); END;
