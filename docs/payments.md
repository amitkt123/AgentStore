# Payments runbook (WS10b)

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
- [ ] Reconciliation run (`/admin/money`, or wait for the nightly job): no issues.

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
- [ ] Payments from `GET /v1/payments` carry `virtual_account_id` for bank transfers. If not, reconciliation cannot flag a bank-transfer payment as MISSING_LOCALLY; the fix is `GET /v1/virtual_accounts/{id}/payments` per known account.
- [ ] Error bodies are `{"error": {"code": "...", "description": ...}}`; a refund over the refundable amount or on an unknown payment is a 400, not a 5xx.
- [ ] Rate limiting answers 429 (the adapter treats 429 and 408 as "outcome unknown", retried later).
- [ ] The Checkout `amount` the backend returns is paise and the frontend passes it through unchanged.

## Reconciliation issues and how to resolve them

The nightly `ReconciliationJob` (or `POST /api/v1/admin/payments/reconciliation/run?date=YYYY-MM-DD`, ADMIN) compares one UTC day with the provider. List the day's issues with `GET /api/v1/admin/payments/reconciliation?date=YYYY-MM-DD` and mark one done with `POST /api/v1/admin/payments/reconciliation/{issueId}/resolve` (`{"note": "..."}`). There is no screen for these yet (the `/admin/money` reconciliation view is WS10c); use the API.

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
