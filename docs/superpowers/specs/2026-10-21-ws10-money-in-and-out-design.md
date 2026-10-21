# WS10: real money in and out

## Purpose

Today a run charge only lowers the buyer wallet's `balance`. Nobody is credited, money can only enter the system through `scripts/demo-fund-wallet`, and nothing can leave it. WS10 builds the real infrastructure for moving money: buyers add money through Razorpay, every charge is split into developer earnings and Karya's fee on a double-entry ledger, buyers can get unused money back to the payment it came from, and developers withdraw their earnings to their own bank account or UPI ID through RazorpayX whenever they want.

It replaces the parent spec's WS10 section (`2026-09-23-agent-store-developer-platform-and-sandbox-design.md` §5 WS10) where the two differ. Most importantly, payouts are developer-initiated withdrawals, not a monthly admin batch.

Success: real rupees go in by UPI/card/netbanking/bank transfer, come out to developers and back to buyers, and at every moment the ledger balances and matches what Razorpay reports.

## Decisions made with the user

- **One vendor for both directions: Razorpay.** Payment Gateway (Checkout and Smart Collect virtual accounts) for money in and refunds; RazorpayX Payouts for money out. Per-transaction fees are accepted for this. Both sit behind provider interfaces so a manual adapter or another vendor can be added without a redesign. This is a deliberate exception to the earlier "no paid third parties" rule.
- **Buyer channels:** the buyer picks the channel when adding money: UPI, card, netbanking, or bank transfer (NEFT/RTGS/IMPS) to a virtual account created once for their org.
- **Buyer money back goes only to the original payment method.** Never to an account the buyer types in. (Regulatory reason: a balance that can be withdrawn anywhere looks like a prepaid payment instrument.)
- **Money out means developers moving earnings out of Karya.** A verified developer withdraws to their own bank account or UPI ID.
- **Withdraw any time**, minimum ₹100 per withdrawal, **no holding period** on earnings.
- **Two buyer billing modes, switchable** platform-wide: `PREPAID` (wallet, today's behaviour) and `POSTPAID` (billed after use, monthly bill paid through the same Razorpay channels). Counsel has not been consulted yet; the switch exists so the answer can be applied without a redesign.
- **Tax collection is switchable**: TDS (Income Tax s.194-O) and GST TCS (CGST s.52) are settings, off by default, until a CA confirms rates and applicability.
- **Delivered as three PRs, each with its screens**: WS10a ledger, WS10b money in and back, WS10c money out.

## Current state (master `9213afa`)

- `Wallet(org_id unique, balance, cap, reserved)`; `WalletReservationService.reserve/release` holds a run's spend cap under a `PESSIMISTIC_WRITE` lock (`RunController` reserves at start).
- `RunLifecycleService.charge` saves a `UsageEvent` per agent `COST` event, calls `LedgerService.chargeUsageEvent` (writes a `ledger_entry` CHARGE, subtracts from `wallet.balance`, idempotent on `usage_event_id`), then releases the consumed part of the reservation. Sandbox runs (no connection) are only simulated and never ledgered.
- `LedgerEntryType` has `REVERSAL` and `FEE`, never created. `StatementController` reads `ledger_entry`.
- `LlmCall.cost` is recorded per call with `runId`; it is charged to nobody.
- Orgs are `BUYER` or `DEVELOPER` (`OrgType`). A buyer org has the wallet; the agent's owning developer org is the one earning.
- WS7 stores PAN (encrypted) and GSTIN for verified developers and sets `org.developer_verified_at`. No bank details are captured (frontend gap G13).
- Outbox (`events` package, `DomainEventPublisher` with `MANDATORY` propagation, `OutboxDispatcher`) and ShedLock-guarded jobs gated on `agentstore.jobs.enabled` exist.
- No billing or earnings screens exist in `frontend/`. Mockup boards "Billing and wallet" and "Earnings and payouts" in `fe-mockups/latest/Karya — Agentic UI.html`; FE-3 plan `2026-09-23-fe3-developer-studio-and-money.md` describes them.
- Latest migration V43.

## Goals

1. A balanced double-entry ledger that is the source of truth for every rupee.
2. Each run charge split into developer earnings and platform fee; the developer's platform-LLM cost for that run deducted at cost.
3. Buyers add money by UPI, card, netbanking or virtual-account bank transfer; credited only by verified webhook, exactly once.
4. Buyers get unused prepaid money back to the original payments.
5. Developers withdraw earnings to a verified bank account or UPI ID any time, ≥ ₹100.
6. Postpaid buyers receive a monthly bill and pay it through the same channels.
7. Daily reconciliation against Razorpay with an alert on mismatch.
8. Screens: buyer Billing (balance, add money, refund, statement by agent, bills), developer Earnings (balance, withdraw, payout account, history, statement download), admin money console (freeze, payouts, reconciliation).

## Non-goals

- GST tax invoices (a legal question, §8 Q6); the bill in postpaid mode is a statement, not a tax invoice.
- Disputes (WS16). WS10 only provides the reversal posting and the negative-balance recovery WS16 will use.
- Multi-currency. INR only.
- Subscription or outcome pricing (`PricingUnit` stays ignored, see `known-issues.md`).
- Charging developers for sandbox LLM usage (covered by sandbox quotas). *Builder's call made by me: only buyer-run LLM cost is deducted.*

## Anti-goals (failure even if it works)

- Crediting a wallet from a browser redirect or client-supplied data.
- Any posting that does not balance, or a balance that disagrees with the sum of its postings.
- A webhook replay, retry or double click moving money twice.
- Paying out to an account whose holder name was not checked, or to an unverified, suspended or frozen developer.
- Sending a buyer's money anywhere other than the payment it came from.
- Floating-point money. `BigDecimal` / `NUMERIC(19,2)` everywhere, paise only at the Razorpay boundary.
- Secrets (Razorpay key secret, webhook secrets) in the repo or logs.

## Design

### 1. Ledger (WS10a)

**Tables (V44):**

- `ledger_account(id, org_id null, kind, created_at)`, unique (org_id, kind). Kinds:
  - per buyer org: `BUYER_WALLET` (prepaid balance), `BUYER_RECEIVABLE` (postpaid amount owed);
  - per developer org: `DEVELOPER_PAYABLE`;
  - platform (org_id null): `PLATFORM_REVENUE`, `PLATFORM_LLM_COST`, `PAYMENT_CLEARING` (captured by Razorpay, not yet settled), `PLATFORM_BANK` (Karya's settlement bank account), `RAZORPAYX_BALANCE` (money available for payouts), `PAYOUT_CLEARING` (payouts in flight), `TAX_TDS_PAYABLE`, `TAX_TCS_PAYABLE`, `GATEWAY_FEES`, `PAYOUT_FEES`, `EXTERNAL` (opening balances and manual adjustments from outside the system).

**Clearing steady state** (*my call*): clearing accounts net to zero once money has moved, so their balances always equal what is in flight at the provider, and reconciliation compares them to it.
- Top-up captured: `PAYMENT_CLEARING −A`, `BUYER_WALLET +A`. Refund created: `BUYER_WALLET −A`, `PAYMENT_CLEARING +A`.
- Settlement report (daily): for each settlement, `PAYMENT_CLEARING +net+fee` (closing the settled payments and refunds), `GATEWAY_FEES −fee`, `PLATFORM_BANK −net`. *(Sign convention: positive raises the account's balance; platform asset accounts such as `PLATFORM_BANK` are stored with the same signed convention, so an asset's balance reads negative. The builder may flip this for display.)*
- Funding RazorpayX (manual or auto-settlement, recorded by reconciliation from the RazorpayX account statement): `PLATFORM_BANK +A`, `RAZORPAYX_BALANCE −A`.
- Withdrawal requested: `DEVELOPER_PAYABLE −A`, `PAYOUT_CLEARING +A`. Payout processed: `PAYOUT_CLEARING −A`, `RAZORPAYX_BALANCE +A`, plus the RazorpayX fee and its GST from the webhook: `PAYOUT_FEES −fee`, `RAZORPAYX_BALANCE +fee`. **Karya absorbs payout fees (user's decision):** the developer receives exactly the amount withdrawn. Payout reversed or failed: `PAYOUT_CLEARING −A`, `DEVELOPER_PAYABLE +A`.

**Lock order** (*my call*): the wallet row (`PESSIMISTIC_WRITE`, existing) is always taken first, then `ledger_account_balance` rows in ascending account id. Every code path that touches both follows this order; `wallet.balance` is written in the same transaction as the `BUYER_WALLET` posting, so they never disagree.
- `ledger_transaction(id, type, idempotency_key unique, run_id null, org_id null, reference jsonb, created_at)`. Types: `RUN_CHARGE`, `LLM_COST`, `TOPUP`, `REFUND`, `WITHDRAWAL`, `WITHDRAWAL_REVERSED`, `BILL_PAYMENT`, `REVERSAL`, `ADJUSTMENT`.
- `ledger_posting(id, transaction_id, account_id, amount NUMERIC(19,2), created_at)`. Signed amount: positive = credit to that account's balance, negative = debit. A deferred constraint trigger rejects a commit where a transaction's postings do not sum to zero; the service checks the same before insert.
- `ledger_account_balance(account_id pk, balance, version)` maintained in the same transaction as the postings (updated under row lock). Balance = sum of postings, verified by a test and by the nightly reconciliation job.

`LedgerService.post(type, idempotencyKey, postings…)` is the only writer. A repeated idempotency key returns the existing transaction.

**Run charge** (replaces `chargeUsageEvent`, idempotency key `usage:{usageEventId}`), for charge `C`, fee percent `f`:
- prepaid: `BUYER_WALLET −C`, `DEVELOPER_PAYABLE +C·(1−f)`, `PLATFORM_REVENUE +C·f`;
- postpaid: same but `BUYER_RECEIVABLE −C` (receivable grows negative = owed).
- Fee rounded half-up to paise; the developer gets the remainder so the three always balance.
- `fee_percent`: `agent.platform_fee_percent` if set, else `developer org fee_percent` if set, else config `agentstore.money.platform-fee-percent` (default 20). Admin-editable per developer (console).

**LLM cost**: on run finish (in `finish`, after the last charge), sum platform-mode `LlmCall.cost` for the run; post `DEVELOPER_PAYABLE −L`, `PLATFORM_LLM_COST +L`, key `llm:{runId}`. BYOK calls are excluded. It is posted for every buyer run that finishes in any status, including runs that were never charged or were killed or reaped, and may drive the balance negative (*my call*; see "Negative developer balance").

**Wallet and reservations**: `wallet.balance` becomes a read of the `BUYER_WALLET` balance (kept as a column updated in the same transaction during migration so existing code keeps working); `reserved` stays as is. `available = balance − reserved`. In postpaid mode reservations check against `credit_limit − owed` instead (org-level `credit_limit`, admin-set, default `agentstore.money.postpaid-default-credit-limit`).

**Billing mode**: `agentstore.money.billing-mode = PREPAID | POSTPAID` (platform-wide), with a nullable per-org override column `org.billing_mode` so an admin can move one org. Switching an org with a non-zero wallet to postpaid is refused until the wallet is refunded or used up (*my call*).

**Migration of existing data**: V44 creates accounts for every org with a wallet and posts one `ADJUSTMENT` per wallet (`BUYER_WALLET +balance`, `PAYMENT_CLEARING −balance`, i.e. opening balance from outside) so balances match. Existing `ledger_entry` rows stay as history; `StatementController` keeps working off them and new charges also write a `ledger_entry` until the frontend moves to postings (*my call*: dual-write, removed in a later cleanup).

**Taxes (switchable)**: config `agentstore.money.tds.enabled` (false), `tds.rate` (0.001), `tds.rate-no-pan` (0.05); `gst-tcs.enabled` (false), `gst-tcs.rate` (0.01). When enabled they are posted on each run charge (s.194-O deducts at credit or payment, whichever is earlier; crediting the developer's payable is the credit), computed on the gross charge `C`, taken from the developer's share (`DEVELOPER_PAYABLE −x`, `TAX_*_PAYABLE +x`, same transaction), so `DEVELOPER_PAYABLE` and the withdrawable balance are always net (*my call*). The rate is picked from whether the developer org has a PAN on its WS7 verification, cached per org; a rate change applies only to charges after it. Rates are unconfirmed and must be checked by a CA before enabling.

**Negative developer balance**: a reversal (WS16) or adjustment can drive `DEVELOPER_PAYABLE` negative; withdrawals are blocked until it is positive again, and future earnings recover it automatically.

**Endpoints (WS10a)**:
- `GET /api/v1/orgs/{id}/wallet` → `{mode, balance, held, available, spentThisMonth, monthlyLimit, creditLimit?, owed?}`.
- `GET /api/v1/orgs/{id}/billing/summary?month=` → per agent `{agent, runs, total, lines[{runDisplayId, units, unitDisplay, amount, terminalReason, decidedBy, at}]}` (parent spec).
- `GET /api/v1/developer/earnings?month=` → `{buyersPaid, platformFee, feePercent, aiUsage, tax, earned, available, byAgent[…]}`.
- `GET /api/v1/developer/statements/{month}/download` → CSV of every posting on the developer's account for the month.
- Admin: `PATCH /api/v1/admin/orgs/{id}/money` `{feePercent?, billingMode?, creditLimit?}`; `GET /api/v1/admin/ledger/accounts` (platform balances).

**Screens (WS10a)**: buyer Billing page (balance, held, available, statement by agent with "Stopped at limit" flag); developer Earnings page (this month, by agent, available balance, statement download); admin money settings per org. Add-money and Withdraw buttons appear in WS10b/c.

### 2. Money in and back (WS10b)

**Provider interface**: `PaymentProvider` with `createOrder(orgId, amount, method)`, `createVirtualAccount(orgId)`, `refund(paymentRef, amount, idempotencyKey)`, `verifyWebhook(body, signature)`, `fetchSettlements(date)`. Adapters: `RazorpayPaymentProvider` (live/test via keys) and `FakePaymentProvider` (tests and dev without keys; can simulate webhooks). Selected by `agentstore.money.payment-provider = razorpay | fake`.

**Tables (V45)**:
- `wallet_topup(id, org_id, method UPI|CARD|NETBANKING|BANK_TRANSFER, amount, status CREATED|PAID|FAILED|EXPIRED, provider_order_id, provider_payment_id unique null, refunded_amount, created_by_user_id, created_at, paid_at)`.
- `virtual_account(org_id pk, provider_va_id, account_number, ifsc, created_at)`.
- `buyer_refund(id, org_id, amount, status REQUESTED|PROCESSING|DONE|FAILED, requested_by_user_id, created_at)` and `buyer_refund_part(id, refund_id, topup_id, amount, provider_refund_id unique null, status)`.
- `payment_webhook_event(id, provider, event_id unique, type, payload jsonb, received_at, processed_at)`.

**Top-up flow**: `POST /api/v1/orgs/{id}/wallet/topups {amount, method}` (BUYER_ADMIN) → creates a Razorpay order, returns Checkout params; the browser opens Razorpay Checkout. The wallet is credited only when the webhook `payment.captured` (or `order.paid`) arrives: signature verified, `event_id` stored (unique → replay ignored), ledger post `TOPUP` key `payment:{provider_payment_id}`: `PAYMENT_CLEARING −A`, `BUYER_WALLET +A`. The UI says "updates within a minute" and polls the wallet. Limits: per-top-up min ₹100, daily per-org max `agentstore.money.topup-daily-limit` (₹1,00,000).

**Bank transfer**: `GET /api/v1/orgs/{id}/wallet/bank-transfer-details` creates the Smart Collect virtual account once and returns account number + IFSC. An incoming `virtual_account.credited` webhook creates a `wallet_topup(method=BANK_TRANSFER, status=PAID)` and posts the same `TOPUP`.

**Postpaid bills**: on the 1st (ShedLock job) each postpaid org with charges in the previous calendar month gets a `buyer_bill(id, org_id, period, amount, status OPEN|PAID|OVERDUE, due_at, paid_at)` (V45) for exactly those charges; charges after the cut go on next month's bill. `POST /api/v1/orgs/{id}/bills/{billId}/pay {method}` creates an order for the exact amount (no partial payment); the webhook posts `BILL_PAYMENT`: `PAYMENT_CLEARING −A`, `BUYER_RECEIVABLE +A`. Due after `agentstore.money.bill-due-days` (15); an overdue bill blocks new runs for that org. `owed` = the whole negative `BUYER_RECEIVABLE` balance (open bills plus unbilled charges), and a reservation needs `owed + reserved + spendCap ≤ credit_limit` (*all my calls*).

**Refunds to the original payment**: `POST /api/v1/orgs/{id}/wallet/refunds {amount}` (BUYER_ADMIN). Spending is not tied to particular top-ups. Refundable = `min(available, Σ over PAID top-ups within the provider's refund window of (amount − refunded_amount))`; money that entered without a provider payment (`ADJUSTMENT`s, opening balances, demo funding) is never refundable by this endpoint and needs an admin (*my call*). The UI shows the refundable amount, not the balance. The amount is split across PAID top-ups with unrefunded money, newest first, each part refunded via Razorpay against its own payment. Ledger at request: `BUYER_WALLET −A`, `PAYMENT_CLEARING +A` (key `refund:{refundPartId}` per part). A failed provider refund reverses its part's posting. Bank-transfer top-ups refund to the source account via Razorpay's virtual-account refund. Top-ups older than the provider's refund window (Razorpay: 6 months for card) are skipped, and if nothing refundable remains the request is refused with a reason (*my call*; an admin can then handle it by hand).

**Gateway fees**: Razorpay deducts fees on settlement. They are posted from the settlement report (`PAYMENT_CLEARING +fee`, `GATEWAY_FEES −fee`) and borne by Karya, not the buyer (*my call*).

**Screens (WS10b)**: Billing gets "Add money" (amount + channel chooser, Razorpay Checkout), "Bank transfer details", top-up history with status, "Get money back" (shows which payments it returns to), and in postpaid mode the bills list with "Pay".

### 3. Money out (WS10c)

**Provider interface**: `PayoutProvider` with `createContact(orgId)`, `createFundAccount(contact, bank|vpa)`, `validateFundAccount(fundAccount)` (penny-drop / VPA validation, returns registered name), `createPayout(fundAccount, amount, mode, idempotencyKey)`, `verifyWebhook`. Adapters: `RazorpayXPayoutProvider`, `FakePayoutProvider`. `agentstore.money.payout-provider = razorpayx | fake`.

**Tables (V46)**:
- `payout_account(id, org_id, type BANK|VPA, account_number_encrypted, account_nonce, ifsc, vpa, holder_name, validated_name, name_match boolean, status PENDING|VERIFIED|FAILED, provider_contact_id, provider_fund_account_id, created_at, verified_at)`. One active account per org; changing it creates a new row and needs re-verification. Account numbers encrypted with the existing `SECRET_ENCRYPTION_KEY` scheme used for PAN.
- `withdrawal(id, org_id, payout_account_id, amount, status REQUESTED|PROCESSING|PROCESSED|REVERSED|FAILED, provider_payout_id unique null, utr, requested_by_user_id, failure_reason, created_at, processed_at)`.
- `org.money_frozen_at`, `org.money_frozen_reason`.

**Payout account**: developer enters bank account + IFSC or a UPI ID. A validation call (penny drop, ₹1 deposit; VPA lookup for UPI) returns the registered name; it is compared to the WS7 verified legal name (case/whitespace/initial-insensitive; *matching rule is the builder's call*). Match → `VERIFIED`. Mismatch → `FAILED` with the returned name shown; an admin can approve it by hand from the console with a note. Karya pays the validation fee; at most 3 validation attempts per org per day (*my call*).

**Withdraw**: `POST /api/v1/developer/withdrawals {amount}` (DEVELOPER_ADMIN). Checks, all under a row lock on the developer's `DEVELOPER_PAYABLE` balance: org verified (`developer_verified_at`) and not suspended; not frozen; a VERIFIED payout account; `amount ≥ agentstore.money.withdrawal-min` (₹100); `amount ≤ available`; daily cap `agentstore.money.withdrawal-daily-limit` per org (default ₹2,00,000, *my call*). Ledger: `DEVELOPER_PAYABLE −A`, `PAYOUT_CLEARING +A`, key `withdrawal:{id}`. Then `createPayout` with idempotency key = withdrawal id, mode IMPS (bank) or UPI (VPA). Webhooks `payout.processed` → PROCESSED with UTR; `payout.reversed` / `payout.failed` → post `WITHDRAWAL_REVERSED` (money back to `DEVELOPER_PAYABLE`) and notify. A withdrawal stuck in PROCESSING over 24h is polled and alerted.

**Funding RazorpayX**: payouts draw on the RazorpayX balance. Funding it from the Razorpay settlement account is an operational setup step (auto-settlement to RazorpayX), documented in the runbook, not code. An insufficient-balance failure from RazorpayX leaves the withdrawal REQUESTED and retried, and raises an alert.

**Admin**: `POST /api/v1/admin/orgs/{id}/money/freeze {reason}` / `unfreeze`; `GET /api/v1/admin/withdrawals?status=`; approve a name-mismatched payout account; `GET /api/v1/admin/reconciliation?date=`.

**Screens (WS10c)**: Earnings gets "Payout account" (add, verify status, name shown back), "Withdraw" (amount, minimum, available, confirmation), withdrawal history with UTR; admin console pages for withdrawals, freezes, account approvals, reconciliation results.

### 4. Cross-cutting

- **Webhooks**: two endpoints, `POST /webhooks/razorpay` (Payment Gateway, secret `RAZORPAY_WEBHOOK_SECRET`) and `POST /webhooks/razorpayx` (payouts, secret `RAZORPAYX_WEBHOOK_SECRET`), public, no auth, signature verified, rate limited like approval links (*my call*). Event mapping: `payment.captured` credits a top-up or bill (`order.paid` is stored and ignored, keyed on payment id so either alone would be safe); `payment.failed` → top-up FAILED; `virtual_account.credited` → bank-transfer top-up; `refund.processed` → refund part DONE; `refund.failed` → part FAILED and its posting reversed; `payout.processed` / `payout.reversed` / `payout.failed` → withdrawal. Raw body is verified before parsing. Every event stored in `payment_webhook_event` with unique `event_id`; processing happens in the same transaction as the ledger post. Unknown types are stored and ignored.
- **Reconciliation** (WS10b, extended in c): nightly ShedLock job fetches Razorpay payments, refunds, settlements and payouts for the previous day, matches them to `wallet_topup`, `buyer_refund_part`, `withdrawal`; any unmatched item, amount difference, or ledger account whose balance ≠ sum of postings is written to `reconciliation_issue` and logged at ERROR with a gauge `agentstore.money.reconciliation_issues` (same pattern as the outbox DEAD gauge).
- **Events**: top-up paid, refund done, withdrawal processed/reversed publish domain events through the outbox so WS14 notifications can email them (consumers optional in this work).
- **Secrets**: `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `RAZORPAYX_ACCOUNT_NUMBER` from env (`.dev/env` locally), required when the provider is `razorpay`/`razorpayx`, never logged.
- **Local dev**: `dev-up.sh` defaults to the fake providers; with test keys set it uses Razorpay test mode, and webhooks reach the backend through the existing cloudflared tunnel. `scripts/demo-fund-wallet` posts a proper `ADJUSTMENT` instead of updating the wallet row.
- **Developer agreement**: add the agreement text (`docs/legal/developer-agreement.md`, served at a public route) including that an earnings balance is money owed for services, not a deposit; bump `DeveloperAgreement.CURRENT_VERSION`. Buyer terms at sign-up get the matching clause that wallet money is prepayment for services, refundable only to its source. *Wording is a draft for counsel.*

## Tests the builder must include

- Every ledger transaction balances; account balance equals sum of postings (property test over random runs, top-ups, refunds, withdrawals, reversals).
- After N runs: developer payable + platform revenue (+ LLM cost, taxes) = buyer charges exactly.
- Webhook replay and concurrent duplicate delivery credit once; bad signature → 400 and nothing stored.
- Concurrent withdrawals cannot exceed available; concurrent runs and top-ups never make `available` negative.
- Withdrawal refused for unverified, suspended, frozen, unverified-account, below-minimum, over-daily-cap and negative-balance cases.
- Reversed payout restores the balance once.
- Refund split across top-ups newest first; partial provider failure reverses only its part.
- Postpaid: runs reserve against credit limit; overdue bill blocks runs; bill payment clears receivable.
- Razorpay adapters tested against recorded HTTP fixtures (WireMock); a manual test-mode run with real test keys before merge of WS10b and WS10c.

## Left to the builder

- Package layout (suggest `com.agentstore.platform.money` with `ledger`, `payments`, `payouts` subpackages, keeping `ledger` classes where they are until callers move).
- Exact Razorpay API calls, retry/backoff, and timeouts.
- Name-matching rule details.
- Screen layout within the existing frontend style, following the mockup boards named above.
- Whether `wallet.balance` dual-write is removed in WS10c or later.

## Open items for the user (do not block building)

- A Razorpay account with test keys (free) is needed for the manual test-mode check; live keys need Karya's business KYC.
- A CA/lawyer must confirm TDS/GST rates, the buyer billing mode, and the agreement wording before live money.
