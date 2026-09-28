CREATE TABLE developer_verification (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES org(id) UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',        -- DRAFT, PENDING, VERIFIED, FIX_REQUESTED, REJECTED
    current_step VARCHAR(20) NOT NULL DEFAULT 'ABOUT',  -- ABOUT, TAX_IDS, PHONE, AGREEMENT
    entity_type VARCHAR(20),                            -- INDIVIDUAL, LLP, PRIVATE_LIMITED, PARTNERSHIP, OTHER
    legal_name VARCHAR(255),
    display_name VARCHAR(255),
    country CHAR(2),
    city VARCHAR(100),
    pan_encrypted BYTEA, pan_nonce BYTEA, pan_masked VARCHAR(20),
    gstin_encrypted BYTEA, gstin_nonce BYTEA, gstin_masked VARCHAR(20),
    pan_document_key VARCHAR(512),
    phone_encrypted BYTEA, phone_nonce BYTEA, phone_masked VARCHAR(20),
    agreement_version VARCHAR(20),
    agreement_accepted_at TIMESTAMPTZ,
    resubmission_count INT NOT NULL DEFAULT 0,
    reviewer_user_id UUID REFERENCES app_user(id),
    decision_note TEXT,
    submitted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
