-- Every platform (org-less) ledger account exists up front with a zero balance row, so posting never has to create
-- one while holding wallet or run locks. Keep in step with LedgerAccountKind.isPlatform().
INSERT INTO ledger_account (id, org_id, kind)
SELECT gen_random_uuid(), NULL, kind
FROM (VALUES ('PLATFORM_REVENUE'), ('PLATFORM_LLM_COST'), ('PAYMENT_CLEARING'), ('PLATFORM_BANK'),
             ('RAZORPAYX_BALANCE'), ('PAYOUT_CLEARING'), ('TAX_TDS_PAYABLE'), ('TAX_TCS_PAYABLE'),
             ('GATEWAY_FEES'), ('PAYOUT_FEES'), ('EXTERNAL')) AS platform (kind)
ON CONFLICT DO NOTHING;

INSERT INTO ledger_account_balance (account_id)
SELECT id FROM ledger_account WHERE org_id IS NULL
ON CONFLICT DO NOTHING;
