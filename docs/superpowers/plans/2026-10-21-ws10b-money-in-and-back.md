# WS10b Money In and Back Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Buyers add real money to their wallet by UPI, card, netbanking or bank transfer, get unused money back to the payments it came from, and postpaid buyers get and pay a monthly bill. The payment provider and the enabled channels are set in config, and every rupee is reconciled nightly against the provider.

**Architecture:** A new `com.agentstore.platform.payments` package. A `PaymentProvider` interface hides the vendor. `FakePaymentProvider` is used for dev and tests and can simulate webhooks; `RazorpayPaymentProvider` is used for test or live keys. Adapters turn vendor webhooks into one `PaymentWebhookEvent` shape, so `WebhookProcessor` never sees vendor JSON. Money moves only inside a webhook transaction, through `LedgerService.post`, with the same wallet lock and `wallet.balance` dual-write that WS10a's admin adjustment uses.

**Tech Stack:** Java 21, Spring Boot, JPA, Flyway, Postgres (Testcontainers), `RestClient` + `MockRestServiceServer`, ShedLock, JUnit 5 + AssertJ + `TestRestTemplate`; Next.js 14 app router with server actions, TypeScript, CSS modules, Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-21-ws10-money-in-and-out-design.md` §2 (including the "Configurable provider and channels" paragraph) and the WS10b parts of §4 (webhooks, reconciliation, events, secrets, local dev). Read both before starting.

## Global Constraints

- Money is `BigDecimal` in Java and `NUMERIC(19,2)` in SQL. Paise (`long`) only inside the Razorpay adapter.
- `LedgerService.post` is the only writer of postings. Lock order: the `wallet` row (`PESSIMISTIC_WRITE`) first, then postings. Call `LedgerAccounts.buyerWallet(lockedWallet)` **before** changing `wallet.balance`. Every posting that changes `BUYER_WALLET` must also change `wallet.balance` by the same amount in the same transaction (dual-write, see `AdminMoneyController.applyAdjustment`).
- Ledger idempotency keys:
  - top-up or bill payment: `payment:{providerPaymentId}`;
  - refund part: `refund:{refundPartId}`, and its reversal `refund-failed:{refundPartId}`;
  - gateway fee: `gateway-fee:{settlementId}`.
- Transaction types (already in `LedgerTransactionType`): `TOPUP`, `BILL_PAYMENT`, `REFUND`, `REVERSAL`, `ADJUSTMENT`.
- Postings:
  - TOPUP: `PAYMENT_CLEARING −A`, `BUYER_WALLET +A`.
  - BILL_PAYMENT: `PAYMENT_CLEARING −A`, `BUYER_RECEIVABLE +A`.
  - REFUND: `BUYER_WALLET −a`, `PAYMENT_CLEARING +a`; its REVERSAL is the opposite.
  - Gateway fee (type `ADJUSTMENT`): `PAYMENT_CLEARING +fee`, `GATEWAY_FEES −fee`.
- Config, all under `agentstore.money.payments` and bound to the new `PaymentProperties` record:
  - `provider: razorpay` in `application.yml`, `fake` in `application-dev.yml` and `application-test.yml`;
  - `methods: UPI,CARD,NETBANKING,BANK_TRANSFER`;
  - `topup-min: 100.00`, `topup-daily-limit: 100000.00`;
  - `refund-window-days: 180`, `bill-due-days: 15`;
  - `webhook-rate-per-minute: 120`;
  - `razorpay.key-id: ${RAZORPAY_KEY_ID:}`, `razorpay.key-secret: ${RAZORPAY_KEY_SECRET:}`, `razorpay.webhook-secret: ${RAZORPAY_WEBHOOK_SECRET:}`, `razorpay.base-url: https://api.razorpay.com`;
  - `fake.webhook-secret: fake-webhook-secret`.
- Method enum `PaymentMethod { UPI, CARD, NETBANKING, BANK_TRANSFER }`.
- Roles: `BUYER_ADMIN` creates top-ups, refunds and bill payments for their own org only. `ADMIN` may read any org's data. `BUYER_APPROVER` gets 403. Follow the claims check in `WalletController`.
- Error bodies are `{code, message}` via a `PaymentApiException(HttpStatus, code, message)` and a `@RestControllerAdvice` `PaymentApiErrors` scoped to the payments controllers (copy `VerificationApiErrors`). Codes:
  - `PAYMENT_METHOD_DISABLED` 422, `AMOUNT_INVALID` 400, `BELOW_MINIMUM` 422, `DAILY_LIMIT_REACHED` 422;
  - `POSTPAID_NO_WALLET` 409, `PREPAID_NO_BILLS` 409;
  - `NOTHING_REFUNDABLE` 422, `REFUND_EXCEEDS_REFUNDABLE` 422, `BILL_NOT_PAYABLE` 409;
  - `BANK_TRANSFER_DISABLED` 404, `PROVIDER_UNAVAILABLE` 502.
- Secrets are never logged and never in the repo. `RazorpayPaymentProvider` must refuse to start when any of key id, key secret or webhook secret is blank.
- No Claude/AI attribution in commits or the PR. Update `docs/status.md` in this PR (final task).
- Backend tests: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=<ClassName>`. Frontend: `cd frontend && npx vitest run <path>`.

## Deliberate deviations from the spec (record them in the PR description)

1. **One `payment_order` table, not `wallet_topup`.** Top-ups and bill payments are both "an order the buyer pays", with `purpose TOPUP|BILL`, so the webhook, expiry and reconciliation code has one table to match against.
2. **The webhook path is `POST /webhooks/payments/{provider}`, not `/webhooks/razorpay`.** A `{provider}` that is not the configured one returns 404. This keeps the provider configurable. In the Razorpay dashboard the webhook URL is `/webhooks/payments/razorpay`.
3. **`MockRestServiceServer`, not WireMock.** It is already used by `HttpBuildRunnerClientTest`, so no new dependency is needed.

## Review Focus

1. **Retrying in the same Checkout after a failed attempt.** A `payment.failed` followed by a `payment.captured` on the same order must still credit the wallet: FAILED → PAID is allowed. The same applies to EXPIRED → PAID. Test in Task 3.
2. **The captured amount differs from the order amount** (vendor bug or a tampered order). Do not credit. Store a `reconciliation_issue` of kind `AMOUNT_MISMATCH` and log ERROR. Test in Task 3.
3. **A webhook for an order this system never created** (another environment sharing the Razorpay account). Store it, credit nothing, return 200 so the vendor stops retrying, and log WARN. Test in Task 3.
4. **A refund requested while runs hold money.** Refundable is capped by `available = balance − reserved`, so held money is never refunded. Test in Task 5.
5. **A top-up paid after the org switched to POSTPAID.** Still credit the wallet, since the money arrived. `setMode`'s existing guard then blocks switching back until it is refunded or used. Test in Task 3.

---

### Task 1: Schema, config and the provider seam

**Files:**
- Create: `backend/src/main/resources/db/migration/V47__payments.sql`
- Create in `backend/src/main/java/com/agentstore/platform/payments/`:
  - `PaymentProperties.java`, `PaymentMethod.java`, `PaymentProvider.java`, `PaymentWebhookEvent.java`, `FakePaymentProvider.java`, `PaymentProviderConfig.java`;
  - entities and Spring Data repositories `PaymentOrder`, `VirtualAccount`, `BuyerRefund`, `BuyerRefundPart`, `BuyerBill`, `PaymentWebhookEventRecord`, `ReconciliationIssue`.
- Modify: `backend/src/main/resources/application.yml`, `application-dev.yml`, `backend/src/test/resources/application-test.yml`
- Test: `backend/src/test/java/com/agentstore/platform/payments/PaymentsSchemaTest.java`, `FakePaymentProviderTest.java`, `PaymentProviderConfigTest.java`

**Interfaces:**
- Produces (exact):
  - `PaymentProvider`:
    - `String name()`
    - `ProviderOrder createOrder(String receipt, BigDecimal amount, PaymentMethod method, Map<String,String> notes)`
    - `ProviderVirtualAccount createVirtualAccount(UUID orgId, String orgName)`
    - `ProviderRefund refund(String providerPaymentId, BigDecimal amount, String receipt)`
    - `Optional<ProviderRefund> findRefund(String providerPaymentId, String receipt)`
    - `boolean verifyWebhook(byte[] body, Map<String,String> headers)`
    - `PaymentWebhookEvent parseWebhook(byte[] body, Map<String,String> headers)`
    - `List<ProviderPayment> fetchPayments(LocalDate dayUtc)`, `List<ProviderRefund> fetchRefunds(LocalDate dayUtc)`, `List<ProviderSettlement> fetchSettlements(LocalDate dayUtc)`
  - Nested records on `PaymentProvider`:
    - `ProviderOrder(String orderId, Map<String,Object> checkout)`;
    - `ProviderVirtualAccount(String id, String accountNumber, String ifsc, String beneficiaryName)`;
    - `ProviderRefund(String refundId, String paymentId, BigDecimal amount, String status /*PROCESSED|PENDING|FAILED*/, String receipt)`;
    - `ProviderPayment(String paymentId, String orderId, BigDecimal amount, String status, String virtualAccountId)`;
    - `ProviderSettlement(String settlementId, BigDecimal fees)`.
  - `PaymentWebhookEvent(String eventId, Kind kind, String orderId, String paymentId, String refundId, String virtualAccountId, BigDecimal amount, PaymentMethod method, String failureReason)` with `enum Kind { PAYMENT_CAPTURED, PAYMENT_FAILED, ORDER_PAID, VIRTUAL_ACCOUNT_CREDITED, REFUND_PROCESSED, REFUND_FAILED, OTHER }`.
  - `PaymentProperties(String provider, Set<PaymentMethod> methods, BigDecimal topupMin, BigDecimal topupDailyLimit, int refundWindowDays, int billDueDays, int webhookRatePerMinute, Razorpay razorpay, Fake fake)` with `Razorpay(String keyId, String keySecret, String webhookSecret, String baseUrl)` and `Fake(String webhookSecret)`.
  - `FakePaymentProvider` extras for dev and tests:
    - `byte[] webhookBody(PaymentWebhookEvent e)`;
    - `Map<String,String> signedHeaders(byte[] body)`, which sets `X-Fake-Signature`, the hex HMAC-SHA256 of the body with `fake.webhook-secret`, and `X-Fake-Event-Id`;
    - an in-memory record of orders, payments and refunds, which the `fetch*` methods return.

V47 tables (all money columns `NUMERIC(19,2)`, ids `uuid`, times `timestamptz default now()`):
- `payment_order`:
  - columns `id, org_id, purpose TOPUP|BILL, bill_id null → buyer_bill, method, amount, status CREATED|PAID|FAILED|EXPIRED, provider, provider_order_id unique, provider_payment_id unique null, refunded_amount default 0, created_by_user_id null, created_at, paid_at`;
  - checks `refunded_amount between 0 and amount` and `amount > 0`.
- `virtual_account(org_id pk, provider, provider_va_id unique, account_number, ifsc, beneficiary_name, created_at)`.
- `buyer_refund(id, org_id, request_id, amount, status REQUESTED|PROCESSING|DONE|PARTLY_FAILED|FAILED, requested_by_user_id, created_at, unique(org_id, request_id))`.
- `buyer_refund_part(id, refund_id, payment_order_id, amount, provider_refund_id unique null, status PENDING|PROCESSING|DONE|FAILED, failure_reason, created_at, updated_at)`.
- `buyer_bill(id, org_id, period char(7) 'YYYY-MM', amount, status OPEN|PAID|OVERDUE, due_at, paid_at, created_at, unique(org_id, period))`.
- `payment_webhook_event(id, provider, event_id, type, payload jsonb, received_at, processed_at, unique(provider, event_id))`.
- `reconciliation_issue(id, day date, kind, provider_ref, detail jsonb, created_at, resolved_at, unique(day, kind, provider_ref))`.

- [ ] **Step 1: Write `PaymentsSchemaTest`.** Using `JdbcTemplate`, test that:
  - inserting two `payment_order`s with the same `provider_order_id` throws `DataIntegrityViolationException`;
  - `refunded_amount > amount` is rejected;
  - a duplicate `(provider, event_id)` is rejected;
  - a duplicate `(org_id, period)` bill is rejected.
- [ ] **Step 2: Write `FakePaymentProviderTest`.** Test that:
  - `verifyWebhook(body, signedHeaders(body))` is true;
  - it is false with one body byte changed, and false with no signature header;
  - `parseWebhook` of `webhookBody(e)` round-trips `e`;
  - `createOrder` returns an `orderId` starting `fake_order_` and `checkout` containing `provider=fake`, `orderId` and `method`.
- [ ] **Step 3: Write `PaymentProviderConfigTest`** with `ApplicationContextRunner`:
  - `provider=fake` gives a `FakePaymentProvider` bean;
  - `provider=razorpay` with a blank `razorpay.key-secret` fails context start with a message naming `RAZORPAY_KEY_SECRET` (and not its value);
  - `provider=paypal` fails with `Unknown payment provider: paypal`.
- [ ] **Step 4: Run the three tests and confirm they fail.** Run `PaymentsSchemaTest,FakePaymentProviderTest,PaymentProviderConfigTest`. Expected: compilation errors or FAIL.
- [ ] **Step 5: Implement the migration, entities, repositories, properties and the two providers.**
  - `PaymentProviderConfig` is the one `@Bean PaymentProvider`, switching on `props.provider()`.
  - For now `RazorpayPaymentProvider` is a class whose constructor validates the keys and whose other methods throw `UnsupportedOperationException`; Task 8 fills it in.
  - Register `PaymentProperties` with `@EnableConfigurationProperties` alongside `MoneyProperties`.
- [ ] **Step 6: Run the three tests and confirm they pass.** Then run the `ledger` package to confirm nothing regressed.
- [ ] **Step 7: Commit** with `feat(payments): payment tables, provider seam and fake provider`.

### Task 2: Payment options and creating a top-up

**Files:**
- Create in `backend/src/main/java/com/agentstore/platform/payments/`: `PaymentApiException.java`, `PaymentApiErrors.java`, `PaymentAccess.java`, `TopUpService.java`, `TopUpController.java`, and the records `PaymentOptionsResponse`, `TopUpRequest`, `PaymentOrderView`, `CheckoutResponse`
- Test: `backend/src/test/java/com/agentstore/platform/payments/TopUpControllerTest.java`

**Interfaces:**
- Consumes: Task 1's `PaymentProvider`, `PaymentProperties`, `PaymentOrder`/`PaymentOrderRepository`, and `BillingModeService.modeFor`.
- Produces:
  - `PaymentAccess.requireOwnOrgAdmin(UUID orgId)` throws 403 unless the caller is that org's `BUYER_ADMIN`.
  - `PaymentAccess.requireOwnOrgOrAdmin(UUID orgId)` also lets `ADMIN` through.
  - `PaymentOrderView(UUID id, String purpose, PaymentMethod method, BigDecimal amount, String status, BigDecimal refundedAmount, Instant createdAt, Instant paidAt)` and `PaymentOrderView.of(PaymentOrder)`.
  - `CheckoutResponse(PaymentOrderView order, String provider, String providerOrderId, Map<String,Object> checkout)`.
  - `TopUpService.checkMethod(PaymentMethod m)` throws `PAYMENT_METHOD_DISABLED`; Task 6 reuses it.
  - `TopUpService.createOrder(UUID orgId, UUID userId, String purpose, UUID billId, BigDecimal amount, PaymentMethod method)` returns `CheckoutResponse`; Task 6 reuses it.
- Endpoints:
  - `GET /api/v1/orgs/{orgId}/wallet/payment-options` returns `{provider, methods (enum order), topUpMin, topUpDailyLimit, topUpRemainingToday}`, for own org or `ADMIN`.
  - `POST /api/v1/orgs/{orgId}/wallet/topups {amount, method}` returns 201 `CheckoutResponse`, own `BUYER_ADMIN` only.
  - `GET /api/v1/orgs/{orgId}/wallet/topups` returns `List<PaymentOrderView>`: the newest 50 with `purpose=TOPUP`, own org or `ADMIN`.

- [ ] **Step 1: Write the failing tests in `TopUpControllerTest`** (the test profile uses the fake provider with all four methods):
  - `optionsListEnabledMethods`: the response has `provider="fake"`, `methods=[UPI,CARD,NETBANKING,BANK_TRANSFER]`, `topUpMin=100.00`, `topUpRemainingToday=100000.00`.
  - `disabledMethodIsRefused`: with `@TestPropertySource(properties="agentstore.money.payments.methods=UPI")` in a nested class, `CARD` gives 422 `PAYMENT_METHOD_DISABLED`, and options lists only `UPI`.
  - `createsOrderWithoutCreditingWallet`: ₹500 by UPI gives 201, `order.status=CREATED`, and `checkout.method="UPI"`. `GET /wallet` balance is unchanged and no `TOPUP` ledger transaction exists.
  - `belowMinimum`: ₹99.99 gives 422 `BELOW_MINIMUM`.
  - `badAmount`: `0`, `-5` and `10.001` each give 400 `AMOUNT_INVALID`.
  - `dailyLimit`: two ₹60,000 orders on the same UTC day; the second gives 422 `DAILY_LIMIT_REACHED`. CREATED and PAID orders both count; FAILED and EXPIRED do not.
  - `postpaidOrgHasNoWallet`: gives 409 `POSTPAID_NO_WALLET`.
  - `onlyOwnBuyerAdmin`: another org's `BUYER_ADMIN` gets 403, and `BUYER_APPROVER` gets 403.
- [ ] **Step 2: Run `TopUpControllerTest` and confirm it fails.**
- [ ] **Step 3: Implement.**
  - `createOrder` order of work:
    1. validate amount, method and limits;
    2. save the `PaymentOrder` (CREATED, `provider_order_id` set to a placeholder `pending:{id}`) and commit;
    3. call `provider.createOrder(receipt = order id, amount, method, notes {orgId, purpose})` outside any transaction;
    4. update `provider_order_id`.
  - If step 3 throws, mark the order FAILED and return 502 `PROVIDER_UNAVAILABLE`. This keeps the HTTP call out of a DB transaction.
- [ ] **Step 4: Run `TopUpControllerTest` and confirm it passes.**
- [ ] **Step 5: Commit** with `feat(payments): payment options and top-up orders with configurable methods`.

### Task 3: Webhooks credit the wallet, plus dev simulation

**Files:**
- Create in `backend/src/main/java/com/agentstore/platform/payments/`: `PaymentWebhookController.java`, `WebhookProcessor.java`, `WebhookRateLimitFilter.java`, `FakePaymentController.java`, `PaymentCrediting.java`
- Test: `backend/src/test/java/com/agentstore/platform/payments/WebhookProcessorTest.java`, `PaymentWebhookControllerTest.java`

**Interfaces:**
- Consumes:
  - `LedgerService.post`, `LedgerAccounts.buyerWallet` and `LedgerAccounts.platformAccount`;
  - `WalletRepository.insertEmptyIfMissing` and `findByOrgId`;
  - `DomainEventPublisher.publish(String type, UUID orgId, UUID aggregateId, Map<String,Object> payload)`;
  - `RuntimeRateLimiter.tryAcquire(String subject, int limit)`.
- Produces:
  - `WebhookProcessor.process(String provider, byte[] body, Map<String,String> headers)` returns `Outcome { PROCESSED, DUPLICATE, IGNORED }` and throws `BadSignatureException`.
  - `PaymentCrediting.creditTopUp(PaymentOrder order, String paymentId, BigDecimal amount)` and `PaymentCrediting.creditBill(PaymentOrder order, String paymentId, BigDecimal amount)`; Task 6 fills in `creditBill`.
  - Event types `payment.topup_paid` and `payment.bill_paid`.
- Endpoints:
  - `POST /webhooks/payments/{provider}`: public. It reads the raw body as `byte[]`, returns 404 for a `{provider}` other than the configured one, 400 for a bad signature (storing nothing), and 200 otherwise.
  - `POST /api/v1/dev/payments/simulate {orderId, outcome: "PAID"|"FAILED"}`: exists only when the provider is `fake` (`@ConditionalOnProperty`). Own org's `BUYER_ADMIN` or `ADMIN`. It builds a fake event (`eventId` random, `paymentId` = `fake_pay_{random}`, amount = the order amount) and calls `process("fake", …)`.

Processing order, all in one transaction:
1. Verify the signature on the raw bytes, then parse.
2. `insert … on conflict (provider, event_id) do nothing`. Zero rows means `DUPLICATE` with no other work.
3. Dispatch on `kind`:
   - `PAYMENT_CAPTURED`: find the order by `orderId`. If there is none, return `IGNORED` and log WARN. If the amount differs from `order.amount`, write `reconciliation_issue(AMOUNT_MISMATCH)`, log ERROR and return `IGNORED`. Otherwise call `creditTopUp` or `creditBill` by purpose.
   - `ORDER_PAID`: store only.
   - `PAYMENT_FAILED`: the order moves CREATED → FAILED; it is left alone if PAID.
   - The rest go in Tasks 4–5. `OTHER` is stored only.
4. Set `processed_at`.

`creditTopUp`:
1. Lock the wallet, creating the row if missing.
2. Get `walletAccount = buyerWallet(wallet)`.
3. Post `TOPUP` with key `payment:{paymentId}` and reference `{paymentOrderId, method}`.
4. If the post returned an existing transaction (a replay by another event id), stop.
5. Otherwise add to `wallet.balance`, set the order PAID with `provider_payment_id` and `paid_at` (from CREATED, FAILED or EXPIRED), and publish `payment.topup_paid`.

- [ ] **Step 1: Write the failing `WebhookProcessorTest`** (it drives `process` with `FakePaymentProvider.webhookBody`/`signedHeaders`):
  - `capturedCreditsOnce`: a ₹500 order is captured, so the wallet balance rises by 500.00, `BUYER_WALLET` rises by 500.00 and `PAYMENT_CLEARING` falls by 500.00. Processing the same event again returns `DUPLICATE` with no balance change.
  - `samePaymentDifferentEventIdCreditsOnce`: two events with different `eventId` but the same `paymentId` credit once.
  - `concurrentDuplicateDeliveryCreditsOnce`: the same event from 8 threads (`ExecutorService`, `CountDownLatch`) leaves the balance +500.00 exactly, with one `PROCESSED` and the rest `DUPLICATE`.
  - `failedThenCapturedCredits` (Review Focus 1): FAILED, then captured with a new event, ends PAID and credited.
  - `amountMismatchDoesNotCredit` (Review Focus 2): a capture of 499.00 on a 500.00 order leaves the balance unchanged, the order CREATED and one `AMOUNT_MISMATCH` issue.
  - `unknownOrderIgnored` (Review Focus 3): returns `IGNORED`, stores the event, and posts nothing.
  - `orderPaidIsStoredOnly`.
  - `postpaidSwitchStillCredits` (Review Focus 5): create the order, switch the org to POSTPAID via `setMode` (allowed while the wallet is empty), then capture. The wallet balance is 500.00.
  - `badSignatureStoresNothing`: `BadSignatureException` is thrown and the `payment_webhook_event` count is unchanged.
- [ ] **Step 2: Write the failing `PaymentWebhookControllerTest`.** Test that:
  - an HTTP post with valid headers gives 200 and a wrong signature gives 400;
  - `/webhooks/payments/razorpay` gives 404 while the provider is `fake`;
  - `simulate` PAID credits the wallet, and another org's `BUYER_ADMIN` gets 403.
- [ ] **Step 3: Run both tests and confirm they fail.**
- [ ] **Step 4: Implement the controller, processor, crediting, filter and fake controller.**
  - The filter rate-limits `/webhooks/**` per remote IP at `webhook-rate-per-minute` (copy `ApprovalRateLimitFilter`).
  - `/webhooks/**` is already `permitAll` via `anyRequest()`; add it explicitly to the `SecurityConfig` matcher list so the intent is visible.
- [ ] **Step 5: Run both tests and confirm they pass.** Then run `ledger` and `LedgerConcurrencyInvariantTest`.
- [ ] **Step 6: Commit** with `feat(payments): verified webhooks credit top-ups exactly once`.

### Task 4: Bank transfer to a virtual account

**Files:**
- Create: `backend/src/main/java/com/agentstore/platform/payments/BankTransferController.java`, `BankTransferDetailsResponse.java`
- Modify: `WebhookProcessor.java`, adding `VIRTUAL_ACCOUNT_CREDITED`
- Test: `backend/src/test/java/com/agentstore/platform/payments/BankTransferTest.java`

**Interfaces:**
- Consumes: `PaymentProvider.createVirtualAccount` and `PaymentCrediting.creditTopUp`.
- Produces: `GET /api/v1/orgs/{orgId}/wallet/bank-transfer-details` returns `BankTransferDetailsResponse(String accountNumber, String ifsc, String beneficiaryName)`. It is for the org's own `BUYER_ADMIN`. It creates the account once, under `insert … on conflict (org_id) do nothing` after the provider call, so a concurrent second creator reads the winner's row. It returns 404 `BANK_TRANSFER_DISABLED` when the method is off, and 409 `POSTPAID_NO_WALLET` for postpaid orgs.
- `VIRTUAL_ACCOUNT_CREDITED`:
  1. find the org by `virtualAccountId`, or return `IGNORED` and log WARN;
  2. insert a `payment_order(purpose=TOPUP, method=BANK_TRANSFER, status=CREATED, provider_order_id="va:{paymentId}", amount)`, or reuse it if that `provider_order_id` exists;
  3. call `creditTopUp`.

- [ ] **Step 1: Write the failing tests in `BankTransferTest`:**
  - `detailsCreatedOnce`: two GETs return the same account number, and the fake provider saw one `createVirtualAccount` call.
  - `disabledIs404`: in a nested class with `methods=UPI,CARD`.
  - `creditByTransfer`: a VA-credited event for ₹25,000 raises the wallet by 25,000.00, creates a top-up with method `BANK_TRANSFER` and status `PAID`, and a replay credits nothing more.
  - `unknownVirtualAccountIgnored`.
- [ ] **Step 2: Run `BankTransferTest` and confirm it fails.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `BankTransferTest` and confirm it passes.**
- [ ] **Step 5: Commit** with `feat(payments): bank transfer top-ups through a virtual account`.

### Task 5: Refunds to the original payments

**Files:**
- Create in `backend/src/main/java/com/agentstore/platform/payments/`: `RefundService.java`, `RefundController.java`, `RefundRetryJob.java`, and the records `RefundableResponse`, `RefundRequest`, `RefundView`
- Modify: `WebhookProcessor.java`, adding `REFUND_PROCESSED` and `REFUND_FAILED`
- Test: `backend/src/test/java/com/agentstore/platform/payments/RefundServiceTest.java`, `RefundControllerTest.java`

**Interfaces:**
- Consumes: `PaymentProvider.refund` and `findRefund`, the wallet lock, `LedgerService.post`, and `DomainEventPublisher`.
- Produces:
  - `RefundService.plan(UUID orgId, BigDecimal amount)` returns `List<Part(UUID paymentOrderId, PaymentMethod method, Instant paidAt, BigDecimal amount)>`. It walks PAID `TOPUP` orders with `paid_at ≥ now − refund-window-days`, newest `paid_at` first, taking `amount − refunded_amount` from each.
  - `RefundService.refundable(UUID orgId)` returns `min(wallet.balance − wallet.reserved, Σ eligible (amount − refunded_amount))`, floored at 0.
  - `RefundService.request(UUID orgId, UUID userId, String requestId, BigDecimal amount)` returns `RefundView`.
  - `RefundService.submitPending()`, called by `RefundRetryJob` every 5 minutes (ShedLock `refundRetry`).
  - Event type `payment.refund_done`.
- Endpoints, all for the org's own `BUYER_ADMIN`; `ADMIN` may also read:
  - `GET /api/v1/orgs/{orgId}/wallet/refundable?amount=` returns `RefundableResponse(BigDecimal refundable, List<Part> parts)`. `parts` is the plan for `amount`, or for the full refundable amount if none is given.
  - `POST /api/v1/orgs/{orgId}/wallet/refunds {requestId, amount}` returns 201 `RefundView(UUID id, BigDecimal amount, String status, List<PartView> parts, Instant createdAt)`. The same `requestId` returns the existing refund with 200.
  - `GET /api/v1/orgs/{orgId}/wallet/refunds` returns the newest 50.

`request`, in one transaction under the wallet lock:
1. Reject with `NOTHING_REFUNDABLE` (refundable is 0) or `REFUND_EXCEEDS_REFUNDABLE`.
2. Save the refund (PROCESSING) and its parts (PENDING).
3. For each part:
   - post `REFUND` with key `refund:{partId}`;
   - subtract from `wallet.balance`;
   - add to the order's `refunded_amount`.
4. Commit.

Then, outside the transaction, call `submit(part)` for each part. `submit`:
- calls `provider.findRefund(paymentId, receipt=partId)` and, only if that finds nothing, `provider.refund(paymentId, amount, receipt=partId)`;
- on success, stores `provider_refund_id` and sets the part to PROCESSING (DONE if the provider already says PROCESSED);
- on a definite provider rejection (4xx), calls `failPart`;
- on a timeout or 5xx, leaves the part PENDING for the retry job.

`failPart`, under the wallet lock:
- posts `REVERSAL` with key `refund-failed:{partId}`;
- adds back to `wallet.balance` and subtracts from `refunded_amount`;
- sets the part to FAILED with its reason.

Webhooks: `REFUND_PROCESSED` sets the part DONE. `REFUND_FAILED` calls `failPart`. When every part is final, the refund becomes DONE (all DONE), FAILED (all FAILED) or PARTLY_FAILED, and `payment.refund_done` is published when any money was returned.

- [ ] **Step 1: Write the failing tests in `RefundServiceTest`:**
  - `splitsNewestFirst`: top-ups ₹1,000 on day 1 and ₹500 on day 2, both PAID. A refund of ₹700 gives parts [500 from the day-2 order, 200 from the day-1 order].
  - `adjustmentMoneyIsNotRefundable`: ₹1,000 from an admin adjustment plus a ₹300 top-up gives refundable = 300.00.
  - `heldMoneyIsNotRefundable` (Review Focus 4): ₹1,000 topped up with ₹800 reserved gives refundable 200.00, and requesting 201 gives `REFUND_EXCEEDS_REFUNDABLE`.
  - `outsideWindowSkipped`: a top-up whose `paid_at` is 181 days ago is excluded, and if nothing else is left the error is `NOTHING_REFUNDABLE`.
  - `partialProviderFailureReversesOnlyThatPart`: with the fake set to reject refunds against the second order, wallet = before − first part, and the refund is PARTLY_FAILED.
  - `refundFailedWebhookReversesOnce`: delivering `REFUND_FAILED` twice restores the balance once.
  - `retryDoesNotDoubleRefund`: a part left PENDING (the fake throws a timeout on the first call but has recorded the refund) is found by `findRefund` when `submitPending()` runs, so the provider holds one refund.
  - `ledgerBalances`: after all of the above, every account's balance equals the sum of its postings.
- [ ] **Step 2: Write the failing tests in `RefundControllerTest`:** the endpoints, roles, and `requestId` idempotency.
- [ ] **Step 3: Run both tests and confirm they fail.**
- [ ] **Step 4: Implement.** Give `FakePaymentProvider` the test hooks `failRefundsFor(String paymentId)` and `timeoutNextRefund()`.
- [ ] **Step 5: Run both tests and confirm they pass.**
- [ ] **Step 6: Commit** with `feat(payments): refunds to the original payments`.

### Task 6: Postpaid bills

**Files:**
- Create in `backend/src/main/java/com/agentstore/platform/payments/`: `BillingCycleService.java`, `BillingCycleJob.java`, `BillController.java`, `BillView.java`, `PayBillRequest.java`, `PaymentMaintenanceJob.java`
- Modify:
  - `PaymentCrediting.creditBill`;
  - `backend/src/main/java/com/agentstore/platform/ledger/WalletReservationService.java` (`reserve`), for the overdue block;
  - `ledger/LedgerPostingRepository.java`, adding a receivable charge-sum query.
- Test: `backend/src/test/java/com/agentstore/platform/payments/BillingCycleTest.java`, `BillControllerTest.java`

**Interfaces:**
- Consumes: `TopUpService.createOrder(..., purpose "BILL", billId, ...)` and `BillingModeService`.
- Produces:
  - `BillingCycleService.issueBills(YearMonth period)` creates one bill per POSTPAID org with a non-zero amount.
    - The amount is the negated sum of postings on the org's `BUYER_RECEIVABLE` in `RUN_CHARGE` transactions whose `created_at` falls in the period (UTC). It is idempotent on `(org_id, period)`.
    - `due_at` is the period end + `bill-due-days`.
  - `BillingCycleJob` runs the cron `0 30 0 1 * *` (UTC) with ShedLock `billingCycle` and calls `issueBills(previous month)`.
  - `BillingCycleService.hasOverdueBill(UUID orgId)` is true when a bill is OPEN or OVERDUE with `due_at < now()`. It reads status and time, so it does not depend on the job.
  - `PaymentMaintenanceJob` runs hourly (ShedLock `paymentMaintenance`). It sets OPEN bills past `due_at` to OVERDUE, and `payment_order` rows that have been CREATED for over 24h to EXPIRED.
  - `creditBill` locks the wallet (lock order), posts `BILL_PAYMENT` with key `payment:{paymentId}`, sets the bill PAID with `paid_at`, sets the order PAID, and publishes `payment.bill_paid`.
- Endpoints, for the org's own `BUYER_ADMIN`; `ADMIN` may also read:
  - `GET /api/v1/orgs/{orgId}/bills` returns `List<BillView(UUID id, String period, BigDecimal amount, String status, Instant dueAt, Instant paidAt)>`, newest first.
  - `POST /api/v1/orgs/{orgId}/bills/{billId}/pay {method}` returns 201 `CheckoutResponse` for exactly the bill amount. It gives 409 `BILL_NOT_PAYABLE` if the bill is PAID or another org's, and 409 `PREPAID_NO_BILLS` for prepaid orgs. `BANK_TRANSFER` is refused here with `PAYMENT_METHOD_DISABLED`: bank transfer only tops up a wallet, so it can't pay a bill. This is my call.
- `reserve`: for POSTPAID, after the lock and before the credit check, throw `InsufficientFundsException("A bill is overdue; pay it to start new runs")` if `hasOverdueBill`. `RunController` already maps it to 402.

- [ ] **Step 1: Write the failing tests in `BillingCycleTest`:**
  - `billsExactlyPreviousMonth`: a postpaid org has charges of ₹40 and ₹60 posted in September (set `ledger_transaction.created_at` via `JdbcTemplate`) and ₹25 in October. `issueBills(2026-09)` gives one bill of 100.00, OPEN, due 2026-10-16T00:00Z. Running it twice leaves one bill.
  - `prepaidAndZeroOrgsGetNoBill`.
  - `overdueBlocksRuns`: with an OPEN bill whose `due_at` is in the past, `walletReservationService.reserve` throws `InsufficientFundsException`; after the bill is paid, reserve succeeds.
  - `payingClearsReceivable`: owed 100.00 before; pay the bill, simulate the capture, then owed is 0.00, the bill is PAID, and the ledger is balanced.
  - `maintenanceExpiresOldOrdersAndMarksOverdue`.
- [ ] **Step 2: Write the failing tests in `BillControllerTest`:** list, pay, the error codes above, and roles.
- [ ] **Step 3: Run both tests and confirm they fail.**
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run both tests and confirm they pass.** Also run `PostpaidTest` and `WalletReservationServiceTest`.
- [ ] **Step 6: Commit** with `feat(payments): monthly postpaid bills, paying them, and overdue blocking`.

### Task 7: Nightly reconciliation and gateway fees

**Files:**
- Create:
  - `backend/src/main/java/com/agentstore/platform/ledger/LedgerChecks.java`, extracted from `AdminMoneyController`'s three checks;
  - in `payments/`: `ReconciliationService.java`, `ReconciliationJob.java`, `AdminPaymentsController.java`, `ReconciliationIssueView.java`.
- Modify: `AdminMoneyController.java`, so it uses `LedgerChecks`.
- Test: `backend/src/test/java/com/agentstore/platform/payments/ReconciliationServiceTest.java`

**Interfaces:**
- Produces:
  - `LedgerChecks.run()` returns `Result(boolean totalsZero, boolean balancesMatchPostings, boolean walletsMatchLedger)`, which `AdminMoneyController` now calls.
  - `ReconciliationService.reconcile(LocalDate dayUtc)` returns `int issuesFound`. It is idempotent: issues are unique on `(day, kind, provider_ref)`.
  - `ReconciliationJob` runs the cron `0 0 2 * * *` (UTC) with ShedLock `paymentReconciliation`, for yesterday.
  - Gauge `agentstore.money.reconciliation_issues`: the number of unresolved issues. Copy the outbox DEAD gauge in `RetentionJob`.
  - Admin endpoints:
    - `GET /api/v1/admin/payments/reconciliation?date=YYYY-MM-DD` returns `List<ReconciliationIssueView>`;
    - `POST /api/v1/admin/payments/reconciliation/run?date=` returns `{issuesFound}`;
    - `POST /api/v1/admin/payments/reconciliation/{issueId}/resolve {note}`.

Rules:
- A provider payment that is captured, with an order id or virtual account id we know, but has no PAID `payment_order` with that `provider_payment_id`, is `MISSING_LOCALLY`.
- A PAID `payment_order` whose `paid_at` falls on that day but is absent from the provider's list is `MISSING_AT_PROVIDER`.
- An amount that differs is `AMOUNT_MISMATCH`.
- A provider refund whose status differs from its part's (PROCESSED vs DONE) is `REFUND_MISMATCH`.
- Any `LedgerChecks` flag that is false is `LEDGER_CHECK_FAILED`, with `provider_ref` set to the check name.
- For each settlement, post the gateway fee once, with key `gateway-fee:{settlementId}`.
- Every issue is logged at ERROR.

- [ ] **Step 1: Write the failing tests in `ReconciliationServiceTest`** (seed the fake's records directly):
  - `cleanDayHasNoIssues`.
  - `capturedButNotCredited`: a payment exists only in the fake, giving one `MISSING_LOCALLY`.
  - `paidButUnknownToProvider`: one `MISSING_AT_PROVIDER`.
  - `refundStatusMismatch`: one issue.
  - `feesPostedOnce`: a settlement with fees 23.60, reconciled twice, posts one transaction, and `GATEWAY_FEES` = −23.60.
  - `rerunDoesNotDuplicateIssues`.
  - `gaugeCountsUnresolved`: resolving one issue lowers the gauge.
- [ ] **Step 2: Run `ReconciliationServiceTest` and confirm it fails.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `ReconciliationServiceTest` and `AdminMoneyControllerTest` and confirm they pass.**
- [ ] **Step 5: Commit** with `feat(payments): nightly reconciliation and gateway fee postings`.

### Task 8: Razorpay adapter

**Files:**
- Modify: `backend/src/main/java/com/agentstore/platform/payments/RazorpayPaymentProvider.java`
- Create: `backend/src/test/resources/razorpay/`, holding JSON fixtures of `order`, `payment.captured`, `payment.failed`, `order.paid`, `virtual_account.credited`, `refund.processed`, `refund.failed`, payments, refunds and settlements list responses, shaped like Razorpay's API reference
- Test: `backend/src/test/java/com/agentstore/platform/payments/RazorpayPaymentProviderTest.java`

**Interfaces:**
- Consumes: `PaymentProperties.razorpay()` and a `RestClient.Builder` (constructor `RazorpayPaymentProvider(RestClient.Builder, PaymentProperties)`, using Basic auth with key id and secret).
- Calls and mapping. The exact request fields are the builder's call from Razorpay's docs, but these are pinned:
  - **Amounts:** convert to paise with `amount.movePointRight(2).longValueExact()`; the reverse uses `movePointLeft(2)`.
  - **`createOrder`:** `POST /v1/orders {amount, currency:"INR", receipt, notes}`. The returned `checkout` holds `key` (the key id, never the secret), `order_id`, `amount`, `currency`, `name: "Karya"`, and `config.display.blocks` with a single block for the chosen method, plus `sequence` and `preferences.show_default_blocks=false`. The method maps as UPI → `upi`, CARD → `card`, NETBANKING → `netbanking`. BANK_TRANSFER never reaches `createOrder`.
  - **`createVirtualAccount`:** `POST /v1/virtual_accounts` with receivers `bank_account`.
  - **`refund`:** `POST /v1/payments/{id}/refund {amount, receipt}`. `findRefund` uses `GET /v1/payments/{id}/refunds` and matches on `receipt`.
  - **`verifyWebhook`:** a constant-time comparison (`MessageDigest.isEqual`) of the hex HMAC-SHA256 of the raw body, keyed with `webhook-secret`, against header `X-Razorpay-Signature`.
  - **`parseWebhook`:** `eventId` comes from header `X-Razorpay-Event-Id`. The event types `payment.captured`, `payment.failed`, `order.paid`, `virtual_account.credited`, `refund.processed` and `refund.failed` map to their `Kind`, anything else is `OTHER`, and the payment entity's `method` maps back to `PaymentMethod`.
  - **`fetch*`:** use `from`/`to` epoch seconds for the UTC day and page with `count=100&skip=`.
  - **Errors:** a 4xx becomes `PaymentProviderRejectedException`, and a 5xx or timeout becomes `PaymentProviderUnavailableException`; both are new, in `payments`. Task 5 relies on that split.
  - **Timeouts:** connect 5s, read 15s.

- [ ] **Step 1: Write the failing tests in `RazorpayPaymentProviderTest`** (`MockRestServiceServer`, as in `HttpBuildRunnerClientTest`):
  - `createOrderSendsPaiseAndRestrictsMethod`: ₹500.50 by UPI sends request `amount=50050` and `receipt` = the order id. The checkout has `key=rzp_test_x`, a single `upi` block, and no secret anywhere in it.
  - `signatureVerification`: the hex HMAC-SHA256 of a fixture body, computed in the test with `javax.crypto.Mac`, passes. A tampered body or a missing header fails.
  - `parsesEachWebhookFixture`: kind, ids, amount (in rupees) and method for every fixture.
  - `refundFindsExistingByReceipt`.
  - `fourXxIsRejectedFiveXxIsUnavailable`.
  - `fetchPaymentsPages`: 100 + 3 results across two pages, giving 103.
- [ ] **Step 2: Run `RazorpayPaymentProviderTest` and confirm it fails.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `RazorpayPaymentProviderTest` and `PaymentProviderConfigTest` and confirm they pass.**
- [ ] **Step 5: Commit** with `feat(payments): Razorpay adapter`.

### Task 9: Billing screens

**Files:**
- Modify:
  - `frontend/src/lib/api/types.ts`, `frontend/src/lib/api/money.ts`, `frontend/src/lib/api/money.test.ts`;
  - `frontend/src/app/(app)/billing/page.tsx`, `frontend/src/features/billing/BillingView.tsx`, `billing.module.css`.
- Create:
  - `frontend/src/app/(app)/billing/actions.ts`;
  - in `frontend/src/features/billing/`: `AddMoney.tsx`, `BankTransferDetails.tsx`, `TopUpHistory.tsx`, `GetMoneyBack.tsx`, `Bills.tsx`, `razorpayCheckout.ts`, plus a `*.test.tsx` for each component.

**Interfaces:**
- Consumes: the endpoints of Tasks 2–6.
- Produces:
  - TS types `PaymentOptions`, `PaymentOrder`, `Checkout`, `BankTransferDetails`, `Refundable`, `Refund`, `Bill`, mirroring the Java records.
  - API functions in `money.ts`: `getPaymentOptions`, `listTopUps`, `createTopUp`, `getBankTransferDetails`, `getRefundable`, `createRefund`, `listRefunds`, `listBills`, `payBill`, `simulatePayment`.
  - Server actions in `actions.ts`: `startTopUp(amount, method)`, `startBillPayment(billId, method)`, `requestRefund(amount)` (generates `requestId` with `crypto.randomUUID()`), `simulate(orderId, outcome)`. Each returns `{ok:true, data} | {ok:false, message}`, following `admin/money/actions.ts`.
  - `openRazorpayCheckout(checkout: Record<string,unknown>): Promise<"closed"|"paid">` loads `https://checkout.razorpay.com/v1/checkout.js` once.

Behaviour (copy is exact):
- **Prepaid page:**
  - The figures row as today.
  - An "Add money" button opens `AddMoney`, which has an amount field ("Amount (₹)", with the minimum from options) and radio buttons for the **enabled** methods only, labelled "UPI", "Card", "Netbanking" and "Bank transfer".
  - Choosing bank transfer shows `BankTransferDetails` (account number, IFSC, beneficiary, each with a copy button) and the note "Money sent here is added to your wallet automatically, usually within a few minutes." The other methods submit to `startTopUp`.
  - When `checkout.provider==="razorpay"`, the button opens Razorpay Checkout. When it is `"fake"`, it shows "Test mode: no real money moves" with the buttons "Simulate payment" and "Simulate failure".
  - After paying, it shows "Payment received. Your balance updates within a minute." and calls `router.refresh()` every 3s for up to 60s, stopping once the balance changes.
  - Below that: `TopUpHistory` (date, method, amount, status, with "Refunded ₹x" when non-zero) and `GetMoneyBack`.
- **`GetMoneyBack`:**
  - Shows "You can get back ₹{refundable}". When that is 0: "Money added by bank adjustment or still held by running runs can't be returned here."
  - The amount input defaults to refundable. A preview list reads "₹{part} back to your {method} payment of {date}", and confirming shows "Refunds reach the original payment in 5–7 working days."
  - Recent refunds are listed with status.
- **Postpaid page:** the figures row as today, plus `Bills` (period, amount, due date, status, and a "Pay" button on OPEN or OVERDUE bills that opens method choice without bank transfer). An OVERDUE bill shows the banner "A bill is overdue. New runs are paused until it's paid."
- **Page load:**
  - `page.tsx` fetches the payment options, top-ups and refundable amount for prepaid orgs, and the bills for postpaid ones, in the existing `Promise.all`.
  - An `ApiError` from the optional payment calls hides only that panel. It must not fail the page.

- [ ] **Step 1: Write the failing component tests:**
  - `AddMoney`: it renders only the methods in `options.methods`, so with `["UPI"]` there is no "Card". It validates the minimum ("Minimum is ₹100"). With the fake provider it shows the simulate buttons, and with razorpay it calls `openRazorpayCheckout` (mocked).
  - `GetMoneyBack`: renders the preview lines and the zero-state sentence.
  - `Bills`: "Pay" appears only on unpaid bills, and the overdue banner appears.
  - `money.test.ts`: each new function hits the right path and method.
- [ ] **Step 2: Run the tests and confirm they fail.** Run `npx vitest run src/features/billing src/lib/api/money.test.ts`.
- [ ] **Step 3: Implement.** Follow the "Billing and wallet" mockup board in `fe-mockups/latest/Karya — Agentic UI.html` and the existing CSS-module style.
- [ ] **Step 4: Run the same tests and confirm they pass.** Also run `npx tsc --noEmit` and `npm run lint`.
- [ ] **Step 5: Commit** with `feat(frontend): add money, bank transfer, refunds and bills on Billing`.

### Task 10: Docs, dev setup and the go-live checklist

**Files:**
- Modify: `docs/status.md`, `docs/known-issues.md`, `docs/frontend-gaps.md`, `docs/demo.md`, `scripts/dev-up.sh` (only if it needs to pass `RAZORPAY_*` through from `.dev/env`)
- Create: `docs/payments.md`

- [ ] **Step 1: Write `docs/payments.md`.** It covers:
  - choosing the provider and methods (the config keys and their defaults);
  - fake mode in dev;
  - switching to Razorpay test mode: put `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` and `RAZORPAY_WEBHOOK_SECRET` in `.dev/env`, then set `agentstore.money.payments.provider=razorpay`;
  - setting the dashboard webhook URL to `{tunnel}/webhooks/payments/razorpay` with the six events;
  - the manual test-mode checklist, which is still to be done by the user once keys exist: a UPI top-up, a card top-up, a failed payment then a retry, a bank transfer to the test virtual account, a partial refund, a postpaid bill payment, and a reconciliation run with no issues;
  - before live money: business KYC, live keys, and the CA/lawyer items from the spec.
- [ ] **Step 2: Update `docs/status.md`:**
  - the WS10b row becomes "Done (manual Razorpay test-mode check pending)", with this PR and the plan path;
  - mark the Billing page's WS10b items done;
  - Next up becomes WS10c;
  - set the Last updated line.
- [ ] **Step 3: Update `docs/known-issues.md`.** Remove "Postpaid orgs accrue owed with no bill". Add:
  - bank transfer can't pay a bill;
  - refunds of money older than 180 days need an admin;
  - only the settlement fee is posted, not the settlement net to the bank.
- [ ] **Step 4: Update `docs/frontend-gaps.md`.** Mark G19 and G20 resolved.
- [ ] **Step 5: Run the whole backend suite and the frontend suite.** Expect both green apart from the known `EmailDeliveryTest`/`ApprovalLinkTest` clock-skew failures, which the PR description must name if they occur.
- [ ] **Step 6: Commit** with `docs: WS10b status, payments runbook and known issues`.
