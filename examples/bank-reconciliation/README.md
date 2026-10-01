# Bank reconciliation demo agent

Reads a `bank_statement` CSV, maps each line to a ledger account by keyword (anything it can't match goes to
`9999 Suspense`), asks for approval at the `post_entries` gate, stages journal entries for `ledger` and opens the gate; approving it posts them. It has a WRITE
scope, so a version needs an admin's approval before it goes live. `bank_statement.csv` is a sample file for the buyer.
Create and push it with `EXAMPLE=bank-reconciliation scripts/demo-agent.sh`.
