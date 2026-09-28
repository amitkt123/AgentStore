CREATE TABLE verification_check (
    id UUID PRIMARY KEY,
    verification_id UUID NOT NULL REFERENCES developer_verification(id),
    check_type VARCHAR(20) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    passed BOOLEAN NOT NULL,
    note TEXT,
    checked_by_user_id UUID NOT NULL REFERENCES app_user(id),
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_verification_check_verification_id ON verification_check(verification_id);
