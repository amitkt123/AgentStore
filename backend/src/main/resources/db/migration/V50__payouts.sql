ALTER TABLE org ADD COLUMN money_frozen_at TIMESTAMPTZ,
                ADD COLUMN money_frozen_reason VARCHAR(500);

CREATE TABLE payout_account (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES org (id),
    type VARCHAR(8) NOT NULL,
    account_number_encrypted BYTEA,
    account_nonce BYTEA,
    account_last4 VARCHAR(4),
    ifsc VARCHAR(16),
    vpa VARCHAR(255),
    holder_name VARCHAR(255) NOT NULL,
    validated_name VARCHAR(255),
    name_match BOOLEAN,
    status VARCHAR(16) NOT NULL,
    provider_contact_id VARCHAR(128),
    provider_fund_account_id VARCHAR(128),
    provider_validation_id VARCHAR(128) UNIQUE,
    failure_reason TEXT,
    approved_by_user_id UUID,
    approval_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at TIMESTAMPTZ,
    replaced_at TIMESTAMPTZ,
    CONSTRAINT payout_account_type_chk CHECK (type IN ('BANK', 'VPA')),
    CONSTRAINT payout_account_status_chk CHECK (status IN ('PENDING', 'VERIFIED', 'FAILED')),
    CONSTRAINT payout_account_shape_chk CHECK (
        (type = 'BANK' AND account_number_encrypted IS NOT NULL AND account_nonce IS NOT NULL
            AND account_last4 IS NOT NULL AND ifsc IS NOT NULL AND vpa IS NULL)
        OR (type = 'VPA' AND vpa IS NOT NULL AND account_number_encrypted IS NULL AND account_nonce IS NULL))
);
CREATE UNIQUE INDEX payout_account_one_active_uq ON payout_account (org_id) WHERE replaced_at IS NULL;

CREATE TABLE withdrawal (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES org (id),
    payout_account_id UUID NOT NULL REFERENCES payout_account (id),
    request_id UUID NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    mode VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL,
    provider_payout_id VARCHAR(128) UNIQUE,
    utr VARCHAR(64),
    fees NUMERIC(19,2),
    requested_by_user_id UUID,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    CONSTRAINT withdrawal_amount_chk CHECK (amount > 0),
    CONSTRAINT withdrawal_mode_chk CHECK (mode IN ('IMPS', 'UPI')),
    CONSTRAINT withdrawal_status_chk CHECK (status IN ('REQUESTED', 'PROCESSING', 'PROCESSED', 'REVERSED', 'FAILED')),
    CONSTRAINT withdrawal_org_request_uq UNIQUE (org_id, request_id)
);
CREATE INDEX withdrawal_status_created_idx ON withdrawal (status, created_at);
