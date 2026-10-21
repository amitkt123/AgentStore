-- Billing summary, earnings and spent-this-month filter transactions by org and month, and LLM cost by run.
CREATE INDEX ix_ledger_transaction_org_created ON ledger_transaction (org_id, created_at);
CREATE INDEX ix_ledger_transaction_run ON ledger_transaction (run_id);

-- The books change only through new balanced transactions: posted rows are never updated or deleted.
CREATE FUNCTION ledger_immutable_check() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_immutable: % on % is not allowed; post a new transaction instead', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_transaction_immutable
    BEFORE UPDATE OR DELETE ON ledger_transaction
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable_check();

CREATE TRIGGER ledger_posting_immutable
    BEFORE UPDATE OR DELETE ON ledger_posting
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable_check();

-- Every developer org has its DEVELOPER_PAYABLE and every buyer org its BUYER_RECEIVABLE from the start, so charges
-- and run finishes never create one while holding run or wallet locks. (BUYER_WALLET stays lazy: its first use
-- seeds the opening balance under the wallet lock.)
CREATE FUNCTION ledger_org_accounts_create() RETURNS trigger AS $$
DECLARE
    account_kind text := CASE NEW.type WHEN 'DEVELOPER' THEN 'DEVELOPER_PAYABLE' WHEN 'BUYER' THEN 'BUYER_RECEIVABLE' END;
    account_id   uuid := gen_random_uuid();
BEGIN
    IF account_kind IS NOT NULL THEN
        INSERT INTO ledger_account (id, org_id, kind) VALUES (account_id, NEW.id, account_kind) ON CONFLICT DO NOTHING;
        IF FOUND THEN
            INSERT INTO ledger_account_balance (account_id) VALUES (account_id);
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER org_ledger_accounts
    AFTER INSERT ON org
    FOR EACH ROW EXECUTE FUNCTION ledger_org_accounts_create();

INSERT INTO ledger_account (id, org_id, kind)
SELECT gen_random_uuid(), id, CASE type WHEN 'DEVELOPER' THEN 'DEVELOPER_PAYABLE' ELSE 'BUYER_RECEIVABLE' END
FROM org WHERE type IN ('DEVELOPER', 'BUYER')
ON CONFLICT DO NOTHING;

INSERT INTO ledger_account_balance (account_id)
SELECT id FROM ledger_account WHERE kind IN ('DEVELOPER_PAYABLE', 'BUYER_RECEIVABLE')
ON CONFLICT DO NOTHING;
