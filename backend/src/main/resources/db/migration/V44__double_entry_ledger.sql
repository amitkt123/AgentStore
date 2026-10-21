CREATE TABLE ledger_account (
    id         uuid PRIMARY KEY,
    org_id     uuid NULL,
    kind       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Platform accounts have a null org_id; coalesce makes them unique per kind too.
CREATE UNIQUE INDEX ux_ledger_account_org_kind
    ON ledger_account (coalesce(org_id, '00000000-0000-0000-0000-000000000000'::uuid), kind);

CREATE TABLE ledger_transaction (
    id              uuid PRIMARY KEY,
    type            text NOT NULL,
    idempotency_key text NOT NULL UNIQUE,
    run_id          uuid NULL,
    org_id          uuid NULL,
    reference       jsonb NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger_posting (
    id             uuid PRIMARY KEY,
    transaction_id uuid NOT NULL REFERENCES ledger_transaction (id),
    account_id     uuid NOT NULL REFERENCES ledger_account (id),
    amount         numeric(19,2) NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_ledger_posting_account ON ledger_posting (account_id);
CREATE INDEX ix_ledger_posting_transaction ON ledger_posting (transaction_id);

CREATE TABLE ledger_account_balance (
    account_id uuid PRIMARY KEY REFERENCES ledger_account (id),
    balance    numeric(19,2) NOT NULL DEFAULT 0,
    version    bigint NOT NULL DEFAULT 0
);

CREATE FUNCTION ledger_posting_balanced_check() RETURNS trigger AS $$
BEGIN
    IF (SELECT coalesce(sum(amount), 0) FROM ledger_posting WHERE transaction_id = NEW.transaction_id) <> 0 THEN
        RAISE EXCEPTION 'ledger_posting_balanced: postings for transaction % do not sum to zero', NEW.transaction_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_posting_balanced
    AFTER INSERT ON ledger_posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_posting_balanced_check();

ALTER TABLE agent ADD COLUMN platform_fee_percent numeric(5,2) NULL;
ALTER TABLE org ADD COLUMN platform_fee_percent numeric(5,2) NULL,
                ADD COLUMN billing_mode text NULL,
                ADD COLUMN credit_limit numeric(19,2) NULL;
