# Bank reconciliation demo agent

Reads a `bank_statement` CSV and maps each line to a ledger account by keyword (anything it can't match goes to
`9999 Suspense`). It then stages journal entries for `ledger` and opens the `post_entries` gate; approving it posts them.
It has a WRITE scope, so a version needs an admin's approval before it goes live. `bank_statement.csv` is a sample file
for the buyer. Create and push it with `EXAMPLE=bank-reconciliation scripts/demo-agent.sh`.

Each entry has only one side (a debit or a credit against the mapped account, with no matching bank-account line), so
the ledger it produces does not balance. That keeps the demo short; it is not a model for real bookkeeping.
