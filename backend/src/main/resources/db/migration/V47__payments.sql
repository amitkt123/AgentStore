CREATE TABLE buyer_bill (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL,
    period CHAR(7) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT buyer_bill_status_chk CHECK (status IN ('OPEN', 'PAID', 'OVERDUE')),
    CONSTRAINT buyer_bill_org_period_uq UNIQUE (org_id, period)
);

CREATE TABLE payment_order (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL,
    purpose VARCHAR(8) NOT NULL,
    bill_id UUID REFERENCES buyer_bill (id),
    method VARCHAR(16) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_order_id VARCHAR(128) NOT NULL UNIQUE,
    provider_payment_id VARCHAR(128) UNIQUE,
    refunded_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    created_by_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at TIMESTAMPTZ,
    CONSTRAINT payment_order_purpose_chk CHECK (purpose IN ('TOPUP', 'BILL')),
    CONSTRAINT payment_order_status_chk CHECK (status IN ('CREATED', 'PAID', 'FAILED', 'EXPIRED')),
    CONSTRAINT payment_order_amount_chk CHECK (amount > 0),
    CONSTRAINT payment_order_refunded_chk CHECK (refunded_amount >= 0 AND refunded_amount <= amount)
);
CREATE INDEX payment_order_org_idx ON payment_order (org_id, created_at);

CREATE TABLE virtual_account (
    org_id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    provider_va_id VARCHAR(128) NOT NULL UNIQUE,
    account_number VARCHAR(64) NOT NULL,
    ifsc VARCHAR(16) NOT NULL,
    beneficiary_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE buyer_refund (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    requested_by_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT buyer_refund_status_chk CHECK (status IN ('REQUESTED', 'PROCESSING', 'DONE', 'PARTLY_FAILED', 'FAILED')),
    CONSTRAINT buyer_refund_org_request_uq UNIQUE (org_id, request_id)
);

CREATE TABLE buyer_refund_part (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id UUID NOT NULL REFERENCES buyer_refund (id),
    payment_order_id UUID NOT NULL REFERENCES payment_order (id),
    amount NUMERIC(19,2) NOT NULL,
    provider_refund_id VARCHAR(128) UNIQUE,
    status VARCHAR(16) NOT NULL,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT buyer_refund_part_status_chk CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED'))
);
CREATE INDEX buyer_refund_part_refund_idx ON buyer_refund_part (refund_id);

CREATE TABLE payment_webhook_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(32) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    CONSTRAINT payment_webhook_event_uq UNIQUE (provider, event_id)
);

CREATE TABLE reconciliation_issue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    day DATE NOT NULL,
    kind VARCHAR(64) NOT NULL,
    provider_ref VARCHAR(128) NOT NULL,
    detail JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT reconciliation_issue_uq UNIQUE (day, kind, provider_ref)
);
