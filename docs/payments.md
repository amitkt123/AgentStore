# Payments runbook (WS10b money in, WS10c money out)

How money gets in (top-ups, bank transfer, bill payments) and back (refunds), which provider runs it, and what to check before live money. Design: `docs/superpowers/specs/2026-10-21-ws10-money-in-and-out-design.md` §2. Plan: `docs/superpowers/plans/2026-10-21-ws10b-money-in-and-back.md`.

## Choosing the provider and methods

All keys are under `agentstore.money.payments` (bound to `PaymentProperties`).

| Key | Default | Meaning |
|---|---|---|
| `provider` | `razorpay` (`application.yml`); `fake` in `application-dev.yml` and `application-test.yml` | Which payment provider bean runs |
| `methods` | `UPI,CARD,NETBANKING,BANK_TRANSFER` | Methods a buyer may use; others answer `PAYMENT_METHOD_DISABLED` |
| `topup-min` | `100.00` | Smallest top-up (rupees) |
| `topup-daily-limit` | `100000.00` | Per org per day (soft limit, see `known-issues.md`) |
| `refund-window-days` | `180` | Older money needs an admin |
| `bill-due-days` | `15` | Days from bill issue to due date |
| `webhook-rate-per-minute` | `120` | Rate limit on the webhook endpoint |
| `razorpay.key-id`, `razorpay.key-secret`, `razorpay.webhook-secret` | from `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` (blank) | Razorpay credentials. The Razorpay provider refuses to start if any is blank, when the provider is `razorpay` (the `application.yml` default) |
| `razorpay.base-url` | `https://api.razorpay.com` | API base |
| `fake.webhook-secret` | `fake-webhook-secret` in `application-dev.yml` and `application-test.yml` only (unset in `application.yml`) | Signs fake webhooks. The fake provider refuses to start when it is blank, so it cannot run outside dev and test by accident |

## Fake mode in dev

The dev profile (`scripts/dev-up.sh` runs `dev,runner`) uses `provider: fake`. No keys are needed. Orders, virtual accounts and refunds are simulated in-process, and webhooks are signed with `fake.webhook-secret` and posted to `POST /webhooks/payments/fake`. Use it to try Add money, Bank transfer details, Get money back and Pay on the Billing page.

## Switching to Razorpay test mode

1. In the Razorpay dashboard, switch to Test Mode and create API keys. Enable Smart Collect (virtual accounts) on the test account.
2. Put the keys in `.dev/env` (git-ignored; `scripts/dev-up.sh` sources it and the backend inherits the variables):
   ```
   RAZORPAY_KEY_ID=rzp_test_...
   RAZORPAY_KEY_SECRET=...
   RAZORPAY_WEBHOOK_SECRET=...
   ```
3. Set `agentstore.money.payments.provider=razorpay` (for example `AGENTSTORE_MONEY_PAYMENTS_PROVIDER=razorpay` in `.dev/env`).
4. Webhook: in Dashboard, Settings, Webhooks, add `{tunnel}/webhooks/payments/razorpay` (the tunnel URL `dev-up.sh` prints as "callback URL"), set the same secret as `RAZORPAY_WEBHOOK_SECRET`, and tick exactly these six events:
   - `payment.captured`
   - `payment.failed`
   - `order.paid`
   - `virtual_account.credited`
   - `refund.processed`
   - `refund.failed`
   The quick tunnel URL changes on every `dev-up.sh`, so update the dashboard URL each time.

## Manual test-mode checklist (to be done by the user once keys exist)

Not yet done. Nothing below has run against real Razorpay.

Flows:
- [ ] UPI top-up (Razorpay test VPA `success@razorpay`): wallet credited once, top-up history shows PAID.
- [ ] Card top-up (Razorpay test card): wallet credited.
- [ ] Failed payment (`failure@razorpay`) then a retry on the same order: failure message shown, retry credits once.
- [ ] Bank transfer to the test virtual account (Dashboard, Smart Collect, test payment on the account): wallet credited.
- [ ] Partial refund of a top-up: wallet debited, refund reaches PROCESSED via webhook, then a second refund of the rest.
- [ ] Postpaid bill. The automatic bill run is at 00:30 UTC on the 1st (`BillingCycleJob`), and the hourly `PaymentMaintenanceJob` re-issues last month's bills (idempotent) in case that run failed. It bills only last month and there is no admin trigger, so in test mode create the bill by hand:
  1. On `/admin/money`, switch a test org to POSTPAID and make a run charge so the org owes something. Note the amount owed.
  2. Insert an OPEN bill for that org, with the amount it owes (replace the org id and amount):
     ```sql
     INSERT INTO buyer_bill (id, org_id, period, amount, status, due_at, created_at)
     VALUES (gen_random_uuid(), '<org-uuid>', to_char(now(), 'YYYY-MM'), 250.00, 'OPEN', now() + interval '15 days', now());
     ```
  3. Open Billing as that org, press Pay on the bill, complete the test payment. The bill becomes PAID and `BUYER_RECEIVABLE` clears.
- [ ] Reconciliation run (`POST /api/v1/admin/payments/reconciliation/run?date=YYYY-MM-DD` as ADMIN, or wait for the nightly job), then `GET /api/v1/admin/payments/reconciliation?date=YYYY-MM-DD`: no issues. The admin reconciliation screen is `/admin/reconciliation` (WS10c).

Go-live gates (hard stops: do not take live money until each is confirmed):
- [ ] **Refund list returns the receipt.** `GET /v1/payments/{id}/refunds` must return the `receipt` sent with `POST /v1/payments/{id}/refund` (the refund part id). A retry after a timeout looks the refund up by that receipt; the adapter also sends the part id as `X-Refund-Idempotency`, so a repeated request returns the first refund. Confirm both in test mode by refunding, then listing.
- [ ] **Payments auto-capture.** Orders are created with `payment_capture: 1`; also confirm in Dashboard → Settings → Payment capture that payments are captured automatically. An authorized-but-not-captured payment never sends `payment.captured`, so no wallet is credited.

Razorpay API assumptions to confirm (the adapter was written from documentation, not a live account):
- [ ] `POST /v1/orders` accepts `{amount, currency, receipt, notes, payment_capture}` and returns `id`; a 36-character UUID receipt is within the 40-character limit.
- [ ] Checkout `config.display.blocks.<name>.instruments[0].method` takes `upi`, `card` or `netbanking`, and with `sequence: ["block.<name>"]` and `preferences.show_default_blocks: false` restricts checkout to that method.
- [ ] `POST /v1/virtual_accounts` with `receivers.types=["bank_account"]`, `description` and `notes` succeeds without `customer_id`; `receivers[]` holds an entry with `entity: "bank_account"`, `account_number`, `ifsc` and `name`.
- [ ] `POST /v1/payments/{id}/refund` accepts `receipt` and the `X-Refund-Idempotency` header, and both its response and `GET /v1/payments/{id}/refunds` return that `receipt` (see the go-live gate above). Refund statuses are `pending`, `processed`, `failed`. A list answer without an `items` array is treated as an outage, never as "no refunds".
- [ ] Webhook signature is the lowercase hex HMAC-SHA256 of the raw body keyed with the webhook secret, in `X-Razorpay-Signature`; the event id is in `X-Razorpay-Event-Id`.
- [ ] Payload shapes: `payload.payment.entity` for `payment.*` and `order.paid`; `payload.refund.entity` (with `payment_id`) for `refund.*`; `payload.virtual_account.entity.id` plus `payload.payment.entity` (method `bank_transfer`, paise) for `virtual_account.credited`.
- [ ] `payment.failed` carries `error_description`. `refund.failed` carries no reason, so the default "Provider failed the refund" is shown.
- [ ] `/v1/payments`, `/v1/refunds` and `/v1/settlements` accept `from`/`to` in epoch seconds, treat `to` as inclusive (range inclusivity), and page with `count` (max 100) and `skip`.
- [ ] Settlement `fees` is the total fee in paise and includes tax. If it excludes `tax`, the gateway-fee posting under-counts.
- [ ] Payments from `GET /v1/payments` and `GET /v1/payments/{id}` carry `virtual_account_id` for bank transfers. If not, reconciliation cannot flag a bank-transfer payment as MISSING_LOCALLY and the apply endpoint cannot credit it; the fix is `GET /v1/virtual_accounts/{id}/payments` per known account.
- [ ] Error bodies are `{"error": {"code": "...", "description": ...}}`; a refund over the refundable amount or on an unknown payment is a 400, not a 5xx.
- [ ] Rate limiting answers 429 (the adapter treats 429 and 408 as "outcome unknown", retried later).
- [ ] The Checkout `amount` the backend returns is paise and the frontend passes it through unchanged.

## Reconciliation issues and how to resolve them

The nightly `ReconciliationJob` (or `POST /api/v1/admin/payments/reconciliation/run?date=YYYY-MM-DD`, ADMIN) compares one UTC day with the provider. List the day's issues with `GET /api/v1/admin/payments/reconciliation?date=YYYY-MM-DD` and mark one done with `POST /api/v1/admin/payments/reconciliation/{issueId}/resolve` (`{"note": "..."}`). The same list is on `/admin/reconciliation`; the API does the same.

- `MISSING_LOCALLY` (the provider captured a payment we never credited, usually a lost webhook): call `POST /api/v1/admin/payments/payments/{providerPaymentId}/apply` (ADMIN). It fetches the payment from the provider, requires status `captured` and an order (or virtual account) of ours with a matching amount, then credits it through the same path as the webhook (ledger key `payment:{id}`, so a late webhook or a second call credits nothing more) and resolves the open `MISSING_LOCALLY` issues for that payment. **Never resolve it with a wallet adjustment:** the adjustment is not keyed to the payment, so a late webhook would credit the money a second time, and the top-up would not be refundable to its source.
- `REFUND_UNMATCHED` (a provider refund with no local refund part, e.g. one made in the Razorpay dashboard): decide by hand; the wallet was not debited.
- `AMOUNT_MISMATCH`, `MISSING_AT_PROVIDER`, `REFUND_MISMATCH`, `DUPLICATE_PAYMENT`, `BILL_NOT_FOUND`, `LEDGER_CHECK_FAILED`: investigate, fix by hand, then resolve with a note.

## Before live money

- Business KYC completed with Razorpay and the account activated.
- Live keys and a live webhook (stable HTTPS URL, not a quick tunnel) with its own secret; keys only in the deployment's secret store.
- Set `AGENTSTORE_FORWARD_HEADERS=native`, and only behind a trusted proxy that sets `X-Forwarded-For`. Without it the webhook rate limit (`webhook-rate-per-minute`, per client IP) sees only the proxy's IP, so every sender shares one bucket. Webhook bodies over 1 MB are refused with 413 before the signature is checked.
- Items for a CA or lawyer (spec §8): GST treatment and tax invoices (WS10 non-goal, bills are statements), refund and chargeback policy, holding buyer money before payout, and the terms shown at top-up.
- Re-run the checklist above in live mode with a small real amount, then a reconciliation run.
- Decide the daily top-up limit and `methods` for launch.

## Payouts (WS10c)

### Flow

1. A developer (role `DEVELOPER`, verified, own org) adds a payout account on Earnings: bank account (number + IFSC) or UPI VPA. Karya creates a RazorpayX contact and fund account, then validates it (penny drop; for a VPA a name lookup). The registered name must match the developer's verified legal name (`NameMatcher`) or an admin approves it by hand. Validation can finish later: the account stays `PENDING` until the `fund_account.validation.completed` webhook arrives or the maintenance job polls it. Adding a new account retires the previous VERIFIED one immediately.
2. The developer withdraws (`requestId` makes a retry safe). Ledger: `withdrawal:{id}` moves `DEVELOPER_PAYABLE` to `PAYOUT_CLEARING`. The withdrawal is `REQUESTED`, then submitted to the provider with the withdrawal id as idempotency key, then `PROCESSING`.
3. RazorpayX reports `processed` (webhook or poll): `withdrawal-paid:{id}` moves `PAYOUT_CLEARING` to `RAZORPAYX_BALANCE`, and `payout-fee:{id}` books the fee (`PAYOUT_FEES`; Karya absorbs it). A `reversed` or `failed` payout posts `withdrawal-reversed:{id}`, returning the money to the developer (from `RAZORPAYX_BALANCE` if it had been processed, else from `PAYOUT_CLEARING`).
4. A `REQUESTED` withdrawal that could not be submitted (provider down, low RazorpayX balance) is retried by `WithdrawalSubmitJob`, which runs every 60 seconds and resubmits `REQUESTED` withdrawals older than `retry-after`. If the provider made the payout but the answer was lost (a timeout), the retry gets the same payout back by idempotency key; if that payout has already finished it is settled at once. A payout webhook that arrives before the retry recorded the payout id is matched by its `reference_id` (our withdrawal id). A `PROCESSING` one older than `stuck-after` is polled and raises `WITHDRAWAL_STUCK`.
5. The nightly reconciliation compares the provider's payouts for the day with local withdrawals (same run as payments).

Freeze: an admin freeze of an org blocks new withdrawals, submission of already-`REQUESTED` withdrawals (they wait and resume on unfreeze), new payout accounts, and buyer refunds. Money in still works. A developer whose verification is revoked (`developer_verified_at` null) cannot withdraw, and their `REQUESTED` withdrawals are not submitted until verification is restored.

Stored webhooks: `payment_webhook_event.payload` for payout events is a redacted copy. Any `bank_account.account_number` and `vpa.address` in the body is masked to `••••` plus its last 4 characters. The signature is checked on the raw body before redaction.

### Config

All under `agentstore.money.payouts` (`PayoutProperties`):

| Key | Default |
|---|---|
| `provider` | `razorpayx`, the default in `application.yml`; `application-dev.yml` and `application-test.yml` override it to `fake` |
| `withdrawal-min` | `100.00` |
| `withdrawal-daily-limit` | `200000.00` |
| `validation-attempts-per-day` | `3` |
| `retry-after` | `PT2M` |
| `stuck-after` | `PT24H` |
| `razorpayx.key-id` | env `RAZORPAY_KEY_ID` |
| `razorpayx.key-secret` | env `RAZORPAY_KEY_SECRET` |
| `razorpayx.account-number` | env `RAZORPAYX_ACCOUNT_NUMBER` |
| `razorpayx.webhook-secret` | env `RAZORPAYX_WEBHOOK_SECRET` |
| `razorpayx.base-url` | `https://api.razorpay.com` |
| `fake.webhook-secret` | `fake-payout-webhook-secret` (dev and test only) |

The RazorpayX provider refuses to start when the key id, key secret, account number or webhook secret is blank. Webhook: `POST /webhooks/payouts/{provider}`, so `/webhooks/payouts/razorpayx` in the RazorpayX dashboard (a separate path from `/webhooks/payments/...` because both fake providers are named `fake`). Events are stored in `payment_webhook_event` with provider `payouts:<name>`.

Subscribe the RazorpayX webhook to exactly these events:

- `payout.processed`
- `payout.reversed`
- `payout.failed`
- `payout.rejected`
- `fund_account.validation.completed`
- `fund_account.validation.failed`

If one is missing, withdrawals (or payout account validations) that depend on it stall until the maintenance poll: a `PROCESSING` withdrawal is polled only after `stuck-after` (24 hours).

### Fake provider (dev)

- Bank account number ending `0000`: registered name comes back as `SOMEONE ELSE` (name mismatch).
- Account number ending `1111`: validation stays `PENDING` and completes on the next poll.
- VPA starting `fail`: validation `FAILED`.
- Payout amount `999.99`: insufficient RazorpayX balance. The withdrawal stays `REQUESTED` and raises `PAYOUT_FUNDS_LOW`.
- Payout amount `777.77`: provider unavailable. The withdrawal stays `REQUESTED` and is retried.
- Simulate a provider outcome: `POST /api/v1/dev/payouts/simulate` with `{withdrawalId, outcome}` where outcome is `PROCESSED`, `REVERSED` or `FAILED`; or use the Simulate menu on `PROCESSING` rows in the Earnings history.

### Reconciliation issue kinds (payouts)

- `PAYOUT_UNMATCHED`: a provider payout with no local withdrawal (made outside Karya). Decide by hand.
- `PAYOUT_MISMATCH`: provider status disagrees with local status. Check the webhook log, then poll or correct.
- `PAYOUT_AMOUNT_MISMATCH`: amounts differ. Investigate before anything else.
- `PAYOUT_MISSING_AT_PROVIDER`: a local withdrawal the provider has no payout for. Check whether the submit never happened.
- `PAYOUT_FETCH_FAILED`: the provider listing failed, so the day was not compared. Re-run.
- `PAYOUT_FUNDS_LOW`: RazorpayX balance too low for a payout. Fund the RazorpayX account; the retry job then submits it.
- `WITHDRAWAL_STUCK`: `PROCESSING` longer than `stuck-after`. Check the payout in the RazorpayX dashboard.
- `PAYOUT_AFTER_REVERSAL`: the provider says processed after we already reversed, or a payout was made for a withdrawal already `FAILED` or `REVERSED`. The developer has been credited back; decide by hand. Note: if `payout.reversed` arrives before `payout.processed` for the same payout, this issue is raised even though the ledger nets correctly (the reversal took the money from `PAYOUT_CLEARING` and the late processed event moves nothing). Check the payout in the RazorpayX dashboard; if it shows reversed, resolve the issue with a note.
- `PAYOUT_DUPLICATE`: a provider payout carries our withdrawal id as reference but is not the payout the withdrawal recorded (or the withdrawal is past `REQUESTED` and recorded none). Money may have gone out twice. Check both payouts in the RazorpayX dashboard before anything else.

### RazorpayX test-mode checklist (to be done by the user once keys exist)

Not yet done. Nothing below has run against real RazorpayX.

- [ ] Set the RazorpayX test keys and account number, the webhook URL `/webhooks/payouts/razorpayx` (stable HTTPS or the dev quick tunnel) with its secret, and start with `provider: razorpayx`.
- [ ] Contact then fund account: add a bank payout account; contact and fund account are created.
- [ ] Penny drop: the account moves to VERIFIED (or PENDING then VERIFIED via webhook/poll) when the registered name matches; a different name goes to admin approval.
- [ ] VPA account: validation works and the name check runs.
- [ ] Payout: withdraw the minimum; withdrawal goes `PROCESSING` then `PROCESSED`, ledger shows `withdrawal-paid` and `payout-fee`.
- [ ] Failure and reversal: a failed or reversed test payout returns the money to Earnings once.
- [ ] Webhook: signature accepted, event stored with provider `payouts:razorpayx`, a replay does nothing.
- [ ] Reconciliation run for the day: no issues.
- [ ] Payload shapes written from documentation, to confirm: the key that carries the validation webhook payload (`fund_account.validation.completed`); confirm payout `fees` includes `tax` (the adapter uses `fees` alone and falls back to `tax` only when `fees` is absent; if `fees` excludes `tax`, `PAYOUT_FEES` under-counts); the VPA validation request shape; the event id header `X-Razorpay-Event-Id`.

### Go-live gates (hard stops)

- [ ] RazorpayX enabled on the business account, with its account number set.
- [ ] Auto-settlement from the Razorpay settlement account into RazorpayX configured, so the RazorpayX balance is funded (spec section 3, "Funding RazorpayX"). Watch for `PAYOUT_FUNDS_LOW`.
- [ ] Webhook URL `/webhooks/payouts/razorpayx` set in the RazorpayX dashboard.
- [ ] Secrets set in the deployment's secret store: `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAYX_ACCOUNT_NUMBER`, `RAZORPAYX_WEBHOOK_SECRET`.
- [ ] CA sign-off on TDS and tax handling, the developer agreement (`docs/legal/developer-agreement.md`) and the buyer clause (`docs/legal/buyer-terms.md`).
