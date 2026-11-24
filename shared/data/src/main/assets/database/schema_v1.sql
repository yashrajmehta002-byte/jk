-- बचत समूह प्रणाली — 25 कोर टेबल्स + No-Delete/No-Update ABORT ट्रिगर्स (V1.1)
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;

-- 1. users
CREATE TABLE IF NOT EXISTS users (
    user_id TEXT PRIMARY KEY NOT NULL,
    username TEXT NOT NULL UNIQUE,
    role TEXT NOT NULL CHECK(role IN ('ADMIN', 'AGENT')),
    password_hash TEXT NOT NULL,
    recovery_key_hash TEXT,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until_epoch INTEGER NOT NULL DEFAULT 0,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL
);

-- 2. agents
CREATE TABLE IF NOT EXISTS agents (
    agent_id TEXT PRIMARY KEY NOT NULL,
    user_id TEXT NOT NULL UNIQUE REFERENCES users(user_id),
    agent_code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    father_husband_name TEXT NOT NULL,
    phone TEXT NOT NULL,
    address TEXT NOT NULL,
    territory TEXT NOT NULL,
    photo_path TEXT,
    agent_qr_token_hash TEXT NOT NULL UNIQUE,
    bound_device_id TEXT,
    guarantor_name TEXT,
    security_deposit_paise INTEGER NOT NULL DEFAULT 0,
    cash_limit_paise INTEGER NOT NULL DEFAULT 3000000,
    status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'INACTIVE', 'BLOCKED', 'REMOVED')),
    is_frozen INTEGER NOT NULL DEFAULT 0,
    block_reason TEXT,
    removed_at INTEGER,
    last_handover_at INTEGER
);

-- 3. members (No Aadhaar, No Member Biometric — HR-02)
CREATE TABLE IF NOT EXISTS members (
    member_id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    search_phonetic TEXT NOT NULL,
    father_husband_name TEXT NOT NULL,
    phone TEXT NOT NULL,
    village TEXT NOT NULL,
    tehsil TEXT,
    district TEXT,
    pin TEXT,
    nominee_name TEXT,
    nominee_relation TEXT,
    photo_path TEXT,
    qr_token_hash TEXT NOT NULL UNIQUE,
    approval_status TEXT NOT NULL DEFAULT 'PROVISIONAL' CHECK(approval_status IN ('PROVISIONAL', 'ACTIVE', 'ARCHIVED')),
    created_at INTEGER NOT NULL
);

-- 4. groups (Max 100 active members — HR-06)
CREATE TABLE IF NOT EXISTS groups (
    group_id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    is_self_admin INTEGER NOT NULL DEFAULT 0,
    assigned_agent_id TEXT REFERENCES agents(agent_id),
    meeting_day TEXT,
    max_members INTEGER NOT NULL DEFAULT 100 CHECK(max_members BETWEEN 1 AND 100),
    is_archived INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

-- 5. memberships (1 active membership per member — HR-06)
CREATE TABLE IF NOT EXISTS memberships (
    membership_id TEXT PRIMARY KEY NOT NULL,
    member_id TEXT NOT NULL REFERENCES members(member_id),
    group_id TEXT NOT NULL REFERENCES groups(group_id),
    status TEXT NOT NULL CHECK(status IN ('ACTIVE', 'TRANSFERRED', 'EXITED')),
    joined_at INTEGER NOT NULL,
    left_at INTEGER,
    exit_settled_at INTEGER
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_one_active_membership
ON memberships(member_id) WHERE status = 'ACTIVE';

-- 6. group_transfer_requests
CREATE TABLE IF NOT EXISTS group_transfer_requests (
    request_id TEXT PRIMARY KEY NOT NULL,
    member_id TEXT NOT NULL REFERENCES members(member_id),
    from_group_id TEXT NOT NULL REFERENCES groups(group_id),
    to_group_id TEXT NOT NULL REFERENCES groups(group_id),
    requested_by_user_id TEXT NOT NULL REFERENCES users(user_id),
    status TEXT NOT NULL CHECK(status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by_admin_id TEXT REFERENCES users(user_id),
    rejection_reason TEXT,
    created_at INTEGER NOT NULL,
    reviewed_at INTEGER
);

-- 7. savings_transactions (Protected Immutable)
CREATE TABLE IF NOT EXISTS savings_transactions (
    tx_id TEXT PRIMARY KEY NOT NULL,
    member_id TEXT NOT NULL REFERENCES members(member_id),
    membership_id TEXT NOT NULL REFERENCES memberships(membership_id),
    type TEXT NOT NULL CHECK(type IN ('SAVINGS_DEPOSIT', 'SAVINGS_WITHDRAWAL', 'RD_DEPOSIT', 'DIVIDEND_CREDIT', 'REVERSAL')),
    amount_paise INTEGER NOT NULL CHECK(amount_paise > 0),
    idempotency_key TEXT NOT NULL UNIQUE,
    is_provisional INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

-- 8. loans
CREATE TABLE IF NOT EXISTS loans (
    loan_id TEXT PRIMARY KEY NOT NULL,
    member_id TEXT NOT NULL REFERENCES members(member_id),
    group_id TEXT NOT NULL REFERENCES groups(group_id),
    principal_paise INTEGER NOT NULL CHECK(principal_paise > 0),
    interest_type TEXT NOT NULL,
    rate_bps INTEGER NOT NULL CHECK(rate_bps >= 0),
    tenor_days INTEGER NOT NULL CHECK(tenor_days > 0),
    approved_by_admin_id TEXT REFERENCES users(user_id),
    second_approver_id TEXT REFERENCES users(user_id),
    status TEXT NOT NULL CHECK(status IN ('APPLIED', 'APPROVED', 'DISBURSED', 'CLOSED', 'WRITTEN_OFF', 'REJECTED')),
    created_at INTEGER NOT NULL
);

-- 9. loan_installments
CREATE TABLE IF NOT EXISTS loan_installments (
    installment_id TEXT PRIMARY KEY NOT NULL,
    loan_id TEXT NOT NULL REFERENCES loans(loan_id),
    installment_no INTEGER NOT NULL,
    due_date_epoch INTEGER NOT NULL,
    principal_paise INTEGER NOT NULL,
    interest_paise INTEGER NOT NULL,
    penalty_paise INTEGER NOT NULL DEFAULT 0,
    paid_paise INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL CHECK(status IN ('DUE', 'PARTIAL', 'PAID', 'OVERDUE'))
);

-- 10. wallet_transactions (Protected Immutable)
CREATE TABLE IF NOT EXISTS wallet_transactions (
    wallet_tx_id TEXT PRIMARY KEY NOT NULL,
    member_id TEXT NOT NULL REFERENCES members(member_id),
    direction TEXT NOT NULL CHECK(direction IN ('CREDIT', 'DEBIT')),
    amount_paise INTEGER NOT NULL CHECK(amount_paise > 0),
    linked_tx_id TEXT,
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL
);

-- 11. bank_transactions (Optional Module)
CREATE TABLE IF NOT EXISTS bank_transactions (
    bank_tx_id TEXT PRIMARY KEY NOT NULL,
    group_id TEXT NOT NULL REFERENCES groups(group_id),
    direction TEXT NOT NULL CHECK(direction IN ('CASH_TO_BANK', 'BANK_TO_CASH', 'BANK_INTEREST', 'BANK_CHARGE')),
    amount_paise INTEGER NOT NULL CHECK(amount_paise > 0),
    reference_no TEXT,
    journal_id TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

-- 12. group_expenses (Protected Immutable)
CREATE TABLE IF NOT EXISTS group_expenses (
    expense_id TEXT PRIMARY KEY NOT NULL,
    group_id TEXT NOT NULL REFERENCES groups(group_id),
    category TEXT NOT NULL,
    amount_paise INTEGER NOT NULL CHECK(amount_paise > 0),
    voucher_note TEXT NOT NULL,
    journal_id TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

-- 13. journal_entries (Protected Immutable Double-Entry Header)
CREATE TABLE IF NOT EXISTS journal_entries (
    journal_id TEXT PRIMARY KEY NOT NULL,
    transaction_id TEXT NOT NULL,
    local_seq_no INTEGER NOT NULL UNIQUE,
    idempotency_key TEXT NOT NULL UNIQUE,
    prev_hash TEXT NOT NULL,
    entry_hash TEXT NOT NULL,
    reversal_of_id TEXT REFERENCES journal_entries(journal_id),
    posted_at INTEGER NOT NULL
);

-- 14. journal_lines (Protected Immutable Double-Entry Lines)
CREATE TABLE IF NOT EXISTS journal_lines (
    line_id TEXT PRIMARY KEY NOT NULL,
    journal_id TEXT NOT NULL REFERENCES journal_entries(journal_id),
    account_code TEXT NOT NULL,
    debit_paise INTEGER NOT NULL DEFAULT 0 CHECK(debit_paise >= 0),
    credit_paise INTEGER NOT NULL DEFAULT 0 CHECK(credit_paise >= 0)
);

-- 15. receipts (Protected Immutable — Post-Commit + 6-digit HMAC)
CREATE TABLE IF NOT EXISTS receipts (
    receipt_id TEXT PRIMARY KEY NOT NULL,
    receipt_no TEXT NOT NULL UNIQUE,
    hmac_code TEXT NOT NULL,
    transaction_id TEXT NOT NULL,
    is_provisional INTEGER NOT NULL DEFAULT 0,
    gps_lat_lon_opt TEXT,
    issued_at INTEGER NOT NULL,
    print_count INTEGER NOT NULL DEFAULT 0
);

-- 16. period_locks (Protected Immutable)
CREATE TABLE IF NOT EXISTS period_locks (
    lock_id TEXT PRIMARY KEY NOT NULL,
    group_id TEXT NOT NULL REFERENCES groups(group_id),
    locked_until_epoch INTEGER NOT NULL,
    locked_by_admin_id TEXT NOT NULL REFERENCES users(user_id),
    locked_at INTEGER NOT NULL
);

-- 17. visit_logs
CREATE TABLE IF NOT EXISTS visit_logs (
    visit_id TEXT PRIMARY KEY NOT NULL,
    agent_id TEXT NOT NULL REFERENCES agents(agent_id),
    member_id TEXT NOT NULL REFERENCES members(member_id),
    check_in_mode TEXT NOT NULL CHECK(check_in_mode IN ('QR_SCAN', 'MANUAL')),
    verified_agent_code TEXT,
    reason_code TEXT,
    next_promised_date INTEGER,
    gps_lat_lon_opt TEXT,
    visited_at INTEGER NOT NULL
);

-- 18. audit_logs (Protected Immutable)
CREATE TABLE IF NOT EXISTS audit_logs (
    audit_id TEXT PRIMARY KEY NOT NULL,
    actor_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    action TEXT NOT NULL,
    prev_hash TEXT NOT NULL,
    entry_hash TEXT NOT NULL,
    timestamp INTEGER NOT NULL
);

-- 19. sync_outbox
CREATE TABLE IF NOT EXISTS sync_outbox (
    outbox_id TEXT PRIMARY KEY NOT NULL,
    scope_group_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    payload_type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    ack_status TEXT NOT NULL DEFAULT 'PENDING' CHECK(ack_status IN ('PENDING', 'RECEIVED', 'POSTED', 'CONFLICT')),
    created_at INTEGER NOT NULL
);

-- 20. sync_conflicts
CREATE TABLE IF NOT EXISTS sync_conflicts (
    conflict_id TEXT PRIMARY KEY NOT NULL,
    entity_id TEXT NOT NULL,
    local_json TEXT NOT NULL,
    remote_json TEXT NOT NULL,
    resolution_status TEXT NOT NULL DEFAULT 'OPEN' CHECK(resolution_status IN ('OPEN', 'RESOLVED_ADMIN', 'REVERSED')),
    created_at INTEGER NOT NULL
);

-- 21. agent_sync_snapshots (Admin Last-Sync Mirror)
CREATE TABLE IF NOT EXISTS agent_sync_snapshots (
    snapshot_id TEXT PRIMARY KEY NOT NULL,
    agent_id TEXT NOT NULL REFERENCES agents(agent_id),
    bound_device_id TEXT NOT NULL,
    last_sync_at INTEGER NOT NULL,
    last_seq_no INTEGER NOT NULL,
    last_cash_in_hand_paise INTEGER NOT NULL,
    snapshot_checksum TEXT NOT NULL,
    bundle_uri TEXT
);

-- 22. cash_handovers (Real Dashboard Gate — HR-12)
CREATE TABLE IF NOT EXISTS cash_handovers (
    handover_id TEXT PRIMARY KEY NOT NULL,
    agent_id TEXT NOT NULL REFERENCES agents(agent_id),
    amount_paise INTEGER NOT NULL CHECK(amount_paise > 0),
    denominations_json TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('PENDING_ADMIN', 'ACCEPTED', 'REJECTED')),
    requested_at INTEGER NOT NULL,
    reviewed_by_admin_id TEXT REFERENCES users(user_id),
    reviewed_at INTEGER,
    variance_paise INTEGER NOT NULL DEFAULT 0,
    journal_id TEXT
);

-- 23. app_settings
CREATE TABLE IF NOT EXISTS app_settings (
    setting_key TEXT PRIMARY KEY NOT NULL,
    setting_value TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 24. backup_records
CREATE TABLE IF NOT EXISTS backup_records (
    backup_id TEXT PRIMARY KEY NOT NULL,
    app_role TEXT NOT NULL CHECK(app_role IN ('ADMIN', 'AGENT')),
    target_medium TEXT NOT NULL CHECK(target_medium IN ('SAF', 'SD_CARD', 'USB_OTG')),
    encryption_alg TEXT NOT NULL DEFAULT 'AES-256-GCM',
    auto_sync_uri TEXT,
    sha256_hash TEXT NOT NULL,
    record_count INTEGER NOT NULL,
    last_auto_modified_at INTEGER NOT NULL
);

-- 25. db_integrity_journal (Targeted Hash-Chain on 9 Core Financial & Audit Tables — V1.1 Sec 10.2)
CREATE TABLE IF NOT EXISTS db_integrity_journal (
    journal_seq_id INTEGER PRIMARY KEY AUTOINCREMENT,
    target_table TEXT NOT NULL,
    record_pk TEXT NOT NULL,
    op_type TEXT NOT NULL CHECK(op_type IN ('INSERT', 'UPDATE')),
    before_hash TEXT NOT NULL,
    after_hash TEXT NOT NULL,
    prev_chain_hash TEXT NOT NULL,
    entry_hash TEXT NOT NULL,
    hmac_sig TEXT NOT NULL,
    actor_device_id TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

-- === NO-DELETE & NO-UPDATE ABORT TRIGGERS (HR-03 & HR-10) ===
CREATE TRIGGER IF NOT EXISTS trg_no_delete_journal_entries BEFORE DELETE ON journal_entries
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: journal_entries cannot be deleted'); END;
CREATE TRIGGER IF NOT EXISTS trg_no_update_journal_entries BEFORE UPDATE ON journal_entries
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: journal_entries are immutable'); END;

CREATE TRIGGER IF NOT EXISTS trg_no_delete_journal_lines BEFORE DELETE ON journal_lines
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: journal_lines cannot be deleted'); END;
CREATE TRIGGER IF NOT EXISTS trg_no_update_journal_lines BEFORE UPDATE ON journal_lines
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: journal_lines are immutable'); END;

CREATE TRIGGER IF NOT EXISTS trg_no_delete_savings BEFORE DELETE ON savings_transactions
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: savings_transactions cannot be deleted'); END;
CREATE TRIGGER IF NOT EXISTS trg_no_update_savings BEFORE UPDATE ON savings_transactions
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: savings_transactions are immutable'); END;

CREATE TRIGGER IF NOT EXISTS trg_no_delete_wallet BEFORE DELETE ON wallet_transactions
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: wallet_transactions cannot be deleted'); END;
CREATE TRIGGER IF NOT EXISTS trg_no_update_wallet BEFORE UPDATE ON wallet_transactions
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: wallet_transactions are immutable'); END;

CREATE TRIGGER IF NOT EXISTS trg_no_delete_expenses BEFORE DELETE ON group_expenses
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: group_expenses cannot be deleted'); END;

CREATE TRIGGER IF NOT EXISTS trg_no_delete_receipts BEFORE DELETE ON receipts
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: receipts cannot be deleted'); END;

CREATE TRIGGER IF NOT EXISTS trg_no_delete_period_locks BEFORE DELETE ON period_locks
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: period_locks cannot be deleted'); END;

CREATE TRIGGER IF NOT EXISTS trg_no_delete_audit_logs BEFORE DELETE ON audit_logs
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: audit_logs cannot be deleted'); END;
CREATE TRIGGER IF NOT EXISTS trg_no_update_audit_logs BEFORE UPDATE ON audit_logs
BEGIN SELECT RAISE(ABORT, 'HR-03/HR-10: audit_logs are immutable'); END;

CREATE TRIGGER IF NOT EXISTS trg_no_delete_integrity_journal BEFORE DELETE ON db_integrity_journal
BEGIN SELECT RAISE(ABORT, 'HR-10: db_integrity_journal cannot be deleted'); END;
CREATE TRIGGER IF NOT EXISTS trg_no_update_integrity_journal BEFORE UPDATE ON db_integrity_journal
BEGIN SELECT RAISE(ABORT, 'HR-10: db_integrity_journal is append-only'); END;

CREATE TRIGGER IF NOT EXISTS trg_no_delete_cash_handovers BEFORE DELETE ON cash_handovers
BEGIN SELECT RAISE(ABORT, 'HR-10: cash_handovers cannot be deleted'); END;
