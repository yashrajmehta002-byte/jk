-- बचत समूह प्रणाली — Zero-Data-Loss Strict Migration: v2 -> v3 (V1.5 Part 2: Meetings, Disputes & Loan Reschedule)
-- नियम HR-09: सभी पुरानी 29 टेबल्स व डेटा 100% सुरक्षित रहेंगे।
PRAGMA foreign_keys = ON;

-- 30. group_meetings (साप्ताहिक/मासिक बैठक व प्रस्ताव लॉग)
CREATE TABLE IF NOT EXISTS group_meetings (
    meeting_id TEXT PRIMARY KEY NOT NULL,
    group_id TEXT NOT NULL REFERENCES groups(group_id),
    meeting_date_epoch INTEGER NOT NULL,
    conducted_by_user_id TEXT NOT NULL REFERENCES users(user_id),
    resolution_notes TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

-- 31. meeting_attendance (सदस्य उपस्थिति रजिस्टर)
CREATE TABLE IF NOT EXISTS meeting_attendance (
    attendance_id TEXT PRIMARY KEY NOT NULL,
    meeting_id TEXT NOT NULL REFERENCES group_meetings(meeting_id),
    member_id TEXT NOT NULL REFERENCES members(member_id),
    status TEXT NOT NULL CHECK(status IN ('PRESENT', 'ABSENT', 'EXCUSED')),
    UNIQUE(meeting_id, member_id)
);

-- 32. member_disputes (सदस्य विवाद / शिकायत टिकट — Append-Only / No-Delete)
CREATE TABLE IF NOT EXISTS member_disputes (
    dispute_id TEXT PRIMARY KEY NOT NULL,
    member_id TEXT NOT NULL REFERENCES members(member_id),
    group_id TEXT NOT NULL REFERENCES groups(group_id),
    category TEXT NOT NULL,
    description_text TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('OPEN', 'UNDER_REVIEW', 'RESOLVED', 'CLOSED')),
    resolution_note TEXT,
    resolved_by_admin_id TEXT REFERENCES users(user_id),
    created_at INTEGER NOT NULL,
    resolved_at INTEGER
);

-- 33. loan_reschedule_logs (F-125: ऋण पुनर्गठन इतिहास — पुरानी भरी किस्तें अपरिवर्तित)
CREATE TABLE IF NOT EXISTS loan_reschedule_logs (
    reschedule_id TEXT PRIMARY KEY NOT NULL,
    loan_id TEXT NOT NULL REFERENCES loans(loan_id),
    remaining_principal_paise INTEGER NOT NULL CHECK(remaining_principal_paise > 0),
    new_tenure_months INTEGER NOT NULL CHECK(new_tenure_months > 0),
    new_annual_rate_bps INTEGER NOT NULL CHECK(new_annual_rate_bps >= 0),
    reason_text TEXT NOT NULL,
    approved_by_admin_id TEXT NOT NULL REFERENCES users(user_id),
    rescheduled_at INTEGER NOT NULL
);

CREATE TRIGGER IF NOT EXISTS trg_no_delete_disputes BEFORE DELETE ON member_disputes
BEGIN SELECT RAISE(ABORT, 'V1.5: member_disputes cannot be deleted'); END;

CREATE TRIGGER IF NOT EXISTS trg_no_delete_loan_reschedule BEFORE DELETE ON loan_reschedule_logs
BEGIN SELECT RAISE(ABORT, 'V1.5: loan_reschedule_logs is immutable audit history'); END;
