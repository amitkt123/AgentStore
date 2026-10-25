# WS10c Money Out Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A verified developer adds a payout account (bank or UPI ID) whose holder name is checked, then withdraws earnings of ₹100 or more at any time to it. Admins can freeze an org's money, approve name-mismatched accounts, see withdrawals, and read reconciliation results on screen.

**Architecture:** A new `com.agentstore.platform.payouts` package mirrors `payments`. A `PayoutProvider` interface hides the vendor: `FakePayoutProvider` for dev and tests, `RazorpayXPayoutProvider` for test or live keys. Withdrawing moves money from `DEVELOPER_PAYABLE` to `PAYOUT_CLEARING` in one locked transaction. The provider payout is created after commit, keyed by the withdrawal id. Webhooks (or a poll for stuck ones) settle it. Payout checks join the existing nightly `ReconciliationService` run.

**Tech Stack:** Java 21, Spring Boot, JPA, Flyway, Postgres (Testcontainers), `RestClient` + `MockRestServiceServer`, ShedLock, JUnit 5 + AssertJ + `TestRestTemplate`; Next.js 14 app router with server actions, TypeScript, CSS modules, Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-21-ws10-money-in-and-out-design.md` §3, plus the WS10c parts of §4 (webhooks, reconciliation, events, secrets, developer agreement). Read both, and skim `payments/` from WS10b: this plan copies its patterns on purpose.

## Global Constraints

- Money is `BigDecimal` in Java and `NUMERIC(19,2)` in SQL. Paise (`long`) appear only inside `RazorpayXPayoutProvider`.
- `LedgerService.post` is the only writer of postings. Ledger keys and postings (amount `A` = withdrawal amount):
  - `withdrawal:{id}`, type `WITHDRAWAL`: `DEVELOPER_PAYABLE −A`, `PAYOUT_CLEARING +A`.
  - `withdrawal-paid:{id}`, type `WITHDRAWAL`: `PAYOUT_CLEARING −A`, `RAZORPAYX_BALANCE +A` (money left our RazorpayX balance).
  - `withdrawal-reversed:{id}`, type `WITHDRAWAL_REVERSED`: `DEVELOPER_PAYABLE +A`, and `PAYOUT_CLEARING −A` if `withdrawal-paid` was never posted, otherwise `RAZORPAYX_BALANCE −A` (a reversal after processing returned the money to RazorpayX).
  - `payout-fee:{id}`, type `ADJUSTMENT`: `RAZORPAYX_BALANCE +fee`, `PAYOUT_FEES −fee` (Karya absorbs payout fees).
- Config, all under `agentstore.money.payouts`, bound to a new `PayoutProperties` record:
  - `provider: razorpayx` in `application.yml`, `fake` in `application-dev.yml` and `application-test.yml`;
  - `withdrawal-min: 100.00`, `withdrawal-daily-limit: 200000.00`, `validation-attempts-per-day: 3`;
  - `retry-after: PT2M` (re-submit a REQUESTED withdrawal), `stuck-after: PT24H` (poll and alert a PROCESSING one);
  - `razorpayx.key-id: ${RAZORPAY_KEY_ID:}`, `razorpayx.key-secret: ${RAZORPAY_KEY_SECRET:}`, `razorpayx.account-number: ${RAZORPAYX_ACCOUNT_NUMBER:}`, `razorpayx.webhook-secret: ${RAZORPAYX_WEBHOOK_SECRET:}`, `razorpayx.base-url: https://api.razorpay.com`;
  - `fake.webhook-secret: fake-payout-webhook-secret` (dev and test only).
- Roles: only `DEVELOPER` users of a `DEVELOPER` org add payout accounts and withdraw, for their own org. The spec's `DEVELOPER_ADMIN` does not exist (`UserRole` is `BUYER_ADMIN, BUYER_APPROVER, DEVELOPER, ADMIN`). `ADMIN` reads anything and acts only through `/api/v1/admin/**`.
- Error bodies are `{code, message}` via `PayoutApiException(HttpStatus, code, message)` and a `@RestControllerAdvice` `PayoutApiErrors` scoped to the payouts controllers (copy `PaymentApiErrors`). Codes:
  - `INVALID_FIELD` 400, `AMOUNT_INVALID` 400;
  - `DEVELOPER_NOT_VERIFIED` 403, `MONEY_FROZEN` 409, `NO_VERIFIED_PAYOUT_ACCOUNT` 409, `ACCOUNT_NOT_APPROVABLE` 409, `WITHDRAWAL_NOT_SIMULATABLE` 409;
  - `BELOW_MINIMUM` 422, `INSUFFICIENT_EARNINGS` 422, `DAILY_LIMIT_REACHED` 422;
  - `VALIDATION_LIMIT_REACHED` 429, `PROVIDER_UNAVAILABLE` 502.
- The full account number is encrypted with `AesGcmCipher` (the same one used for PAN in `DeveloperVerificationService.encryptInto`). It is never returned, logged or put in an event. Views show the last 4 digits only.
- Secrets are never logged and never in the repo. `RazorpayXPayoutProvider` refuses to start when the key id, key secret, account number or webhook secret is blank.
- No Claude/AI attribution in commits or the PR. Update `docs/status.md` in this PR (final task).
- Backend tests: `cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn test -Dtest=<ClassName>`; integration tests extend `AbstractIntegrationTest`. Frontend: `cd frontend && npx vitest run <path>`.

## Deliberate deviations from the spec (record them in the PR description)

1. **Migration V50, not V46.** V46 and V47 were used by WS10a and WS10b, V48 by run replay (#50) and V49 by the BUYER_MEMBER rename (#53).
2. **The webhook path is `POST /webhooks/payouts/{provider}`.** This mirrors WS10b's `/webhooks/payments/{provider}`. It is a separate path because both fake providers are named `fake`. Events are stored in the existing `payment_webhook_event` table with `provider = 'payouts:' + name`. In the RazorpayX dashboard the URL is `/webhooks/payouts/razorpayx`.
3. **Reconciliation stays at `/api/v1/admin/payments/reconciliation`** (WS10b's path), not `/api/v1/admin/reconciliation`. Payout checks are added to the same run.
4. **`DEVELOPER` role, not `DEVELOPER_ADMIN`** (see Global Constraints).
5. **No "suspended" check.** There is no suspension concept in the codebase. "Not suspended" is covered by the freeze. A developer whose verification is revoked (`developer_verified_at` null) is refused.
6. **Validation can be asynchronous.** RazorpayX penny drops complete later. A payout account can stay `PENDING` until a `fund_account.validation.completed` webhook arrives or the maintenance job polls it.
7. **A freeze also blocks buyer refunds**, not only withdrawals. A frozen org can take money in but cannot move any out. This is my call.
8. **Validation fees are not ledgered** in WS10c. Record this in `docs/known-issues.md`.
9. **`wallet.balance` dual-write stays.** Its removal is a later cleanup.

## Review Focus

1. **A payout reversed after it was processed** (the bank returns the money days later). The developer gets the money back once, and the ledger takes it from `RAZORPAYX_BALANCE`, not `PAYOUT_CLEARING`. Test in Task 4.
2. **Double click or retried request on Withdraw.** The same `requestId` returns the first withdrawal and moves no more money. A different `requestId` at the same time cannot overdraw. Test in Task 3.
3. **The provider times out on createPayout.** The withdrawal stays `REQUESTED`, the retry job re-sends it with the same idempotency key, and only one payout exists at the provider (the fake provider dedupes on the key). Test in Task 3.
4. **Earnings go negative after a withdrawal** (a later WS16 reversal or adjustment). Withdraw is refused with `INSUFFICIENT_EARNINGS`, and the screen shows "You owe" without a Withdraw button. Test in Tasks 3 and 8.
5. **A developer changes their payout account while a withdrawal is in flight.** The in-flight withdrawal keeps paying the old fund account it was created with. The new account must verify before the next withdrawal. Test in Task 2.

---

### Task 1: Schema, config and the provider seam

**Files:**
- Create: `backend/src/main/resources/db/migration/V50__payouts.sql`
- Create in `backend/src/main/java/com/agentstore/platform/payouts/`:
  - `PayoutProperties.java`, `PayoutProvider.java`, `PayoutProviderConfig.java`, `FakePayoutProvider.java`;
  - `PayoutProviderRejectedException.java`, `PayoutProviderUnavailableException.java`, `PayoutInsufficientBalanceException.java`;
  - `PayoutAccount.java`, `PayoutAccountRepository.java`, `Withdrawal.java`, `WithdrawalRepository.java`;
  - `PayoutApiException.java`, `PayoutApiErrors.java`.
- Modify: `identity/Org.java` (add `moneyFrozenAt`, `moneyFrozenReason`), `application.yml`, `application-dev.yml`, `application-test.yml`
- Test: `backend/src/test/java/com/agentstore/platform/payouts/PayoutsSchemaTest.java`, `FakePayoutProviderTest.java`, `PayoutProviderConfigTest.java`

**Interfaces:**
- Produces: the provider interface, used by every later task.

```java
public interface PayoutProvider {
    String name();                                            // "razorpayx" | "fake"
    String createContact(UUID orgId, String name);            // provider contact id
    String createFundAccount(String contactId, FundAccountDetails details);   // provider fund account id
    ProviderValidation validateFundAccount(String fundAccountId, PayoutAccountType type);
    Optional<ProviderValidation> fetchValidation(String validationId);
    ProviderPayout createPayout(String fundAccountId, BigDecimal amount, PayoutMode mode, String idempotencyKey);
    Optional<ProviderPayout> fetchPayout(String payoutId);
    List<ProviderPayout> fetchPayouts(LocalDate dayUtc);
    boolean verifyWebhook(byte[] body, Map<String, String> headers);
    PayoutWebhookEvent parseWebhook(byte[] body, Map<String, String> headers);

    record FundAccountDetails(PayoutAccountType type, String accountNumber, String ifsc, String vpa, String holderName) {}
    /** status: COMPLETED | PENDING | FAILED; registeredName null unless COMPLETED. */
    record ProviderValidation(String validationId, String status, String registeredName, String failureReason) {}
    /** status: QUEUED | PROCESSING | PROCESSED | REVERSED | FAILED; idempotencyKey is our withdrawal id. */
    record ProviderPayout(String payoutId, String idempotencyKey, BigDecimal amount, BigDecimal fees,
                          String status, String utr, String failureReason, Instant createdAt) {}
}
enum PayoutAccountType { BANK, VPA }
enum PayoutMode { IMPS, UPI }       // BANK → IMPS, VPA → UPI
record PayoutWebhookEvent(String eventId, Kind kind, String payoutId, String validationId, BigDecimal amount,
                          BigDecimal fees, String utr, String registeredName, String failureReason) {
    enum Kind { PAYOUT_PROCESSED, PAYOUT_REVERSED, PAYOUT_FAILED, VALIDATION_COMPLETED, VALIDATION_FAILED, OTHER }
}
```

- Produces: the exceptions. `PayoutProviderRejectedException` is a definite no. `PayoutProviderUnavailableException` is a 5xx, a timeout, or a 401/403. `PayoutInsufficientBalanceException` means the RazorpayX balance is too low. All extend `RuntimeException`.
- Produces: the `V50` schema.
  - `payout_account(id, org_id → org, type, account_number_encrypted bytea, account_nonce bytea, account_last4, ifsc, vpa, holder_name, validated_name, name_match boolean, status PENDING|VERIFIED|FAILED, provider_contact_id, provider_fund_account_id, provider_validation_id unique, failure_reason, approved_by_user_id, approval_note, created_at, verified_at, replaced_at)`.
    - Partial unique index `(org_id) where replaced_at is null`: one active account per org.
    - Check constraint: BANK has number and IFSC and no VPA; VPA has a VPA and no number.
  - `withdrawal(id, org_id, payout_account_id → payout_account, request_id uuid, amount NUMERIC(19,2) check > 0, mode, status REQUESTED|PROCESSING|PROCESSED|REVERSED|FAILED, provider_payout_id unique null, utr, fees NUMERIC(19,2), requested_by_user_id, failure_reason, created_at, submitted_at, processed_at, settled_at)`.
    - `unique (org_id, request_id)`;
    - index `(status, created_at)`.
  - `alter table org add money_frozen_at timestamptz, add money_frozen_reason varchar(500)`.
- Produces: `FakePayoutProvider(PayoutProperties.Fake)` with these deterministic rules:
  - Validation:
    - the registered name is the holder name, unless the account number ends `0000`, in which case it is `"SOMEONE ELSE"`;
    - a VPA starting `fail` gives `FAILED`;
    - an account number ending `1111` gives `PENDING`, and `fetchValidation` then returns `COMPLETED`.
  - `createPayout` dedupes on `idempotencyKey`, returning the same payout. It returns status `PROCESSING` and fees `0.00`.
  - An amount of exactly `999.99` throws `PayoutInsufficientBalanceException`, and `777.77` throws `PayoutProviderUnavailableException`. Tests use both.
  - `signedEvent(Kind, payoutOrValidationId, Map<String, Object> fields)` returns `(body, headers)` signed with HMAC-SHA256 under `fake.webhook-secret` in header `X-Fake-Signature`, for tests and the dev simulate endpoint.

- [ ] **Step 1: Write the failing tests**
  - `PayoutsSchemaTest.secondActiveAccountForOrgIsRejected` inserts two rows with `replaced_at` null and asserts `DataIntegrityViolationException`.
  - `PayoutsSchemaTest.withdrawalRequestIdIsUniquePerOrg`.
  - `PayoutsSchemaTest.bankAccountWithoutIfscIsRejected`.
  - `FakePayoutProviderTest.createPayoutTwiceWithSameKeyReturnsSamePayout`.
  - `FakePayoutProviderTest.validationNameRules` (all three rules above).
  - `FakePayoutProviderTest.signedEventVerifiesAndTamperedBodyDoesNot`.
  - `PayoutProviderConfigTest.unknownProviderFailsStartup`.
  - `PayoutProviderConfigTest.razorpayxWithBlankAccountNumberFailsStartup` (use `ApplicationContextRunner`, as `PaymentProviderConfigTest` does).
- [ ] **Step 2: Run them.** Expected: FAIL (classes and migration missing).
- [ ] **Step 3: Implement the migration, entities, repositories, properties, config, fake provider and error advice.** `PayoutProviderConfig` copies `PaymentProviderConfig`'s switch. Add a `RazorpayXPayoutProvider` stub whose constructor only validates config and whose methods throw `UnsupportedOperationException`; Task 7 fills it in.
- [ ] **Step 4: Run the three test classes plus `PaymentsSchemaTest`.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(payouts): payout schema, config and provider seam`.

### Task 2: Payout account with a name check

**Files:**
- Create in `payouts/`: `NameMatcher.java`, `PayoutAccountService.java`, `PayoutAccountController.java`, `PayoutAccountRequest.java`, `PayoutAccountView.java`, `PayoutsSummaryResponse.java`
- Test: `NameMatcherTest.java`, `PayoutAccountControllerTest.java`

**Interfaces:**
- Consumes: Task 1's `PayoutProvider`, `PayoutAccountRepository`, `AesGcmCipher`; `DeveloperVerificationRepository.findByOrgId(orgId)` for `legalName`.
- Produces:
  - `NameMatcher.matches(String legalName, String registeredName): boolean`.
  - `PayoutAccountService.add(UUID orgId, UUID userId, PayoutAccountRequest req): PayoutAccountView`.
  - `PayoutAccountService.applyValidation(UUID accountId, ProviderValidation v)`. This is idempotent and called by Task 4's webhook and Task 5's poll.
  - `PayoutAccountService.activeAccount(UUID orgId): Optional<PayoutAccount>`.
  - Endpoints:
    - `POST /api/v1/developer/payout-account` with body `{type, accountNumber?, ifsc?, vpa?, holderName}`, returning `201 PayoutAccountView`;
    - `GET /api/v1/developer/payouts`, returning `PayoutsSummaryResponse(PayoutAccountView account /*nullable*/, BigDecimal available, BigDecimal minimum, BigDecimal dailyLimit, BigDecimal withdrawnToday, boolean canWithdraw, String blockedReason /*nullable code from the list above*/, int validationAttemptsLeft, String provider /* payout provider name */)`.
  - `PayoutAccountView(UUID id, String type, String accountLast4, String ifsc, String vpa, String holderName, String validatedName, Boolean nameMatch, String status, String failureReason, Instant createdAt, Instant verifiedAt)`.

Name rule (*my call*):
1. Normalise both names: uppercase, replace anything that is not a letter or space with a space, drop the tokens `MR MRS MS DR SHRI SMT KUMARI`, then split on whitespace.
2. They match when both have the same number of tokens and the tokens can be paired one-to-one, ignoring order, where each pair is equal or one side is a single letter equal to the other's first letter.
3. At least one pair must be a full match of two or more letters.

Rules for `add`:
1. Refuse unless the caller's org is a `DEVELOPER` org with `developer_verified_at` set (`DEVELOPER_NOT_VERIFIED`) and not frozen (`MONEY_FROZEN`).
2. Validate the fields with `INVALID_FIELD`:
   - account number 9–18 digits;
   - IFSC `^[A-Z]{4}0[A-Z0-9]{6}$`, uppercased first;
   - VPA `^[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z]{2,64}$`;
   - holder name 1–120 characters after trimming.
3. Count today's (UTC) `payout_account` rows for the org. At or over `validation-attempts-per-day`, refuse with `VALIDATION_LIMIT_REACHED`.
4. Call the provider outside any transaction: contact, fund account, validate. On `PayoutProviderUnavailableException` → `PROVIDER_UNAVAILABLE`, and no row is saved.
5. In one transaction, mark the current active row `replaced_at = now()`, insert the new row, then `applyValidation`.

`applyValidation` rules:
- `COMPLETED` with `NameMatcher.matches(legalName, registeredName)` → `VERIFIED` and `verified_at`.
- `COMPLETED` without a match → `FAILED`, `name_match=false`, `failure_reason = "NAME_MISMATCH"`.
- `FAILED` → `FAILED` with the provider's reason.
- `PENDING` → leave the row unchanged.
- A row that is no longer `PENDING` is not changed.

- [ ] **Step 1: Write the failing tests**
  - `NameMatcherTest`, parameterised:
    - `("Amit Kumar Tiwari","AMIT K TIWARI")` → true;
    - `("Tiwari Amit","Amit Tiwari")` → true;
    - `("Dr. Amit Tiwari","AMIT TIWARI")` → true;
    - `("A Tiwari","Amit Tiwari")` → true;
    - `("Amit Tiwari","Rahul Tiwari")` → false;
    - `("A K","Amit Kumar")` → false;
    - `("Amit Tiwari","Amit Kumar Tiwari")` → false;
    - `(null,"Amit")` → false.
  - `PayoutAccountControllerTest`:
    - `bankAccountWithMatchingNameIsVerified` (response has `accountLast4` and no `accountNumber` field; the DB column is not plaintext);
    - `mismatchedNameIsFailedWithRegisteredNameShown` (account ending `0000` → `validatedName = "SOMEONE ELSE"`);
    - `vpaValidationFailureIsFailed`;
    - `pendingValidationStaysPending` (ending `1111`);
    - `unverifiedDeveloperGets403`;
    - `frozenOrgGets409`;
    - `buyerGets403`;
    - `fourthAttemptInADayIs429`;
    - `badIfscIs400`;
    - `newAccountReplacesOldButInFlightWithdrawalKeepsOldAccount` (Review Focus 5: insert a PROCESSING withdrawal on the old account directly, add a new account, and assert that the withdrawal's `payout_account_id` is unchanged and the summary shows the new account);
    - `summaryShowsAttemptsLeftAndBlockedReason` (no account → `NO_VERIFIED_PAYOUT_ACCOUNT`).
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement `NameMatcher`, `PayoutAccountService`, the controller and the views.** `available` comes from the org's `DEVELOPER_PAYABLE` balance, as in `EarningsController`. `withdrawnToday` sums today's withdrawals in `REQUESTED|PROCESSING|PROCESSED`. `canWithdraw` is true only when `blockedReason` is null. `blockedReason` checks in this order: `DEVELOPER_NOT_VERIFIED`, `MONEY_FROZEN`, `NO_VERIFIED_PAYOUT_ACCOUNT`, `INSUFFICIENT_EARNINGS` (available < minimum), `DAILY_LIMIT_REACHED`.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(payouts): payout account with holder-name check`.

### Task 3: Withdraw

**Files:**
- Create in `payouts/`: `WithdrawalService.java`, `WithdrawalController.java`, `WithdrawalRequest.java`, `WithdrawalView.java`, `WithdrawalSubmitJob.java`
- Test: `WithdrawalControllerTest.java`, `WithdrawalConcurrencyTest.java`

**Interfaces:**
- Consumes: `PayoutAccountService.activeAccount`, `LedgerAccounts.orgAccount(orgId, DEVELOPER_PAYABLE)`, `LedgerAccounts.platformAccount(PAYOUT_CLEARING)`, `LedgerAccountBalanceRepository.findForUpdate`, `LedgerService.post`.
- Produces:
  - `WithdrawalService.request(UUID orgId, UUID userId, UUID requestId, BigDecimal amount): WithdrawalView`.
  - `WithdrawalService.submit(UUID withdrawalId)`. It sends one REQUESTED withdrawal to the provider and is safe to repeat.
  - `WithdrawalService.reverse(UUID withdrawalId, WithdrawalStatus to, String reason)`. `to` is `REVERSED` or `FAILED`; it posts `withdrawal-reversed:{id}` and publishes `payout.withdrawal_reversed`. Task 4 uses it.
  - Endpoints:
    - `POST /api/v1/developer/withdrawals` with `{requestId, amount}`, returning `201 WithdrawalView`, or `200` with the existing one for a repeated `requestId`;
    - `GET /api/v1/developer/withdrawals?limit=50`, newest first.
  - `WithdrawalView(UUID id, BigDecimal amount, String mode, String status, String utr, String accountLabel /* "Bank ••1234" or the VPA */, String failureReason, Instant createdAt, Instant processedAt)`.

How `request` works:
1. Return the existing withdrawal for `(orgId, requestId)` if there is one.
2. Check the amount: positive, at most 2 decimals, otherwise `AMOUNT_INVALID`. Then check verified, not frozen, and active account `VERIFIED`, using Task 2's codes.
3. Resolve both ledger accounts before the transaction.
4. In one transaction:
   - lock the org's `DEVELOPER_PAYABLE` balance row with `findForUpdate`;
   - refuse with `BELOW_MINIMUM` (amount < `withdrawal-min`), `INSUFFICIENT_EARNINGS` (amount > balance) or `DAILY_LIMIT_REACHED` (today's REQUESTED|PROCESSING|PROCESSED total + amount > `withdrawal-daily-limit`);
   - insert the withdrawal `REQUESTED`, with `mode` derived from the account type;
   - post `withdrawal:{id}`.
5. After commit, call `submit`.

How `submit` works:
- It calls `createPayout(fundAccountId, amount, mode, withdrawalId.toString())`.
- Success → `PROCESSING`, with `provider_payout_id` and `submitted_at`.
- `PayoutProviderUnavailableException` → it stays `REQUESTED` and the error is logged.
- `PayoutInsufficientBalanceException` → it stays `REQUESTED`, ERROR is logged, and `ReconciliationIssues.record("PAYOUT_FUNDS_LOW", "razorpayx-balance", {withdrawalId, amount})` is called.
- `PayoutProviderRejectedException` → `reverse(id, FAILED, message)`.

`WithdrawalSubmitJob` runs `@Scheduled(fixedDelay = 60s)` with ShedLock `payoutSubmit`. It is gated on `agentstore.jobs.enabled`. It calls `submit` for each `REQUESTED` withdrawal older than `retry-after`.

- [ ] **Step 1: Write the failing tests**
  - `WithdrawalControllerTest`:
    - `withdrawMovesPayableToClearingAndSubmits` (the payable balance drops by A, `PAYOUT_CLEARING` rises by A, the status is `PROCESSING`, the payout id is set);
    - `sameRequestIdTwiceMovesMoneyOnce` (Review Focus 2);
    - `belowMinimumIs422` (`99.99`);
    - `overAvailableIs422`;
    - `negativeBalanceIs422` (Review Focus 4: post an ADJUSTMENT taking payable to −50, then try to withdraw 100);
    - `overDailyCapIs422` (set `withdrawal-daily-limit` via `@TestPropertySource` to `500.00`, then withdraw 300 + 300);
    - `unverifiedAccountIs409`;
    - `frozenIs409`;
    - `unverifiedDeveloperIs403`;
    - `buyerIs403`;
    - `providerTimeoutLeavesRequestedAndRetrySubmitsOnce` (Review Focus 3: amount `777.77` stays REQUESTED; switch the fake to succeed; run `submit` twice; one provider payout, status PROCESSING);
    - `lowRazorpayXBalanceLeavesRequestedAndRecordsIssue` (`999.99`);
    - `providerRejectionFailsAndRestoresBalance`;
    - `listIsNewestFirstAndOwnOrgOnly`.
  - `WithdrawalConcurrencyTest.concurrentWithdrawalsCannotExceedAvailable`: available 1000; 8 threads each withdraw 300 with distinct request ids; exactly 3 succeed, the balance is 100, and `LedgerChecks.run()` is all true.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement the service, controller, views and job.** The fake provider needs a test hook to stop failing `777.77`. Expose `FakePayoutProvider.clearFailures()` and have it make every amount succeed afterwards.
- [ ] **Step 4: Run them.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(payouts): developer withdrawals`.

### Task 4: Payout webhooks and dev simulation

**Files:**
- Create in `payouts/`: `PayoutWebhookController.java`, `PayoutWebhookProcessor.java`, `FakePayoutController.java`
- Test: `PayoutWebhookProcessorTest.java`, `PayoutWebhookControllerTest.java`

**Interfaces:**
- Consumes: `WithdrawalService.reverse`, `PayoutAccountService.applyValidation`, `DomainEventPublisher.publish(type, orgId, aggregateId, payload)`, `ReconciliationIssues`.
- Produces:
  - `PayoutWebhookProcessor.process(String providerName, byte[] body, Map<String,String> headers): WebhookProcessor.Outcome`.
  - `PayoutWebhookProcessor.settle(UUID withdrawalId, ProviderPayout payout)`. It applies a polled payout with the same rules as a webhook. Task 5 uses it.
  - `POST /webhooks/payouts/{provider}`. Copy `PaymentWebhookController`: 404 for a provider that is not the configured one, the 1 MB cap, 400 `BAD_SIGNATURE` or `MALFORMED_EVENT`, and 200 `{outcome}` otherwise.
  - `POST /api/v1/dev/payouts/simulate` with `{withdrawalId, outcome: PROCESSED|REVERSED|FAILED}`, role `DEVELOPER` or `ADMIN`, own org unless ADMIN. It exists only when `agentstore.money.payouts.provider=fake`. A withdrawal that is not PROCESSING or PROCESSED returns 409 `WITHDRAWAL_NOT_SIMULATABLE`.

`process` copies `WebhookProcessor`: verify the signature, parse, insert into `payment_webhook_event` with provider `payouts:{name}` (`on conflict do nothing` → `DUPLICATE`), then act, all in one transaction. Find the withdrawal by `provider_payout_id`. An unknown id is stored, logged at WARN and returns `IGNORED`.
- `PAYOUT_PROCESSED`:
  - when the withdrawal is `PROCESSING` (or `REQUESTED` with this payout id), set `PROCESSED` with `utr` and `processed_at`;
  - post `withdrawal-paid:{id}`, and `payout-fee:{id}` when fees > 0;
  - publish `payout.withdrawal_processed` with `{withdrawalId, amount, utr}`;
  - an event amount different from ours is not settled and records the issue `PAYOUT_AMOUNT_MISMATCH`.
- `PAYOUT_REVERSED` / `PAYOUT_FAILED` → `reverse(id, REVERSED|FAILED, reason)`. This is allowed from `PROCESSING` or `PROCESSED`, and the reversal account depends on whether `withdrawal-paid` exists (Global Constraints). A withdrawal already `REVERSED` or `FAILED` does nothing.
- `VALIDATION_COMPLETED` / `VALIDATION_FAILED` → find the account by `provider_validation_id` and call `applyValidation`.
- `OTHER` is stored only.

- [ ] **Step 1: Write the failing tests**
  - `PayoutWebhookProcessorTest`:
    - `processedSetsUtrAndMovesClearingToRazorpayX` (`PAYOUT_CLEARING` back to 0, `RAZORPAYX_BALANCE` +A, event published);
    - `processedWithFeePostsPayoutFee`;
    - `replayedEventIsDuplicateAndPostsOnce`;
    - `sameOutcomeUnderNewEventIdPostsOnce` (ledger key absorbs it);
    - `failedRestoresPayableOnce`;
    - `reversedAfterProcessedRestoresPayableFromRazorpayX` (Review Focus 1: assert `RAZORPAYX_BALANCE` returns to 0, `PAYOUT_CLEARING` stays 0, payable is back to its original value);
    - `amountMismatchIsNotSettledAndRecordsIssue`;
    - `unknownPayoutIsStoredAndIgnored`;
    - `validationCompletedVerifiesPendingAccount`;
    - `ledgerChecksPassAfterEachScenario`.
  - `PayoutWebhookControllerTest`:
    - `badSignatureIs400AndStoresNothing`;
    - `otherProviderPathIs404`;
    - `simulateProcessedEndToEnd`;
    - `simulateOnFailedWithdrawalIs409`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement the processor, controller and fake controller.**
- [ ] **Step 4: Run them, plus `WebhookRateLimitFilterTest`** (the `/webhooks/` prefix already covers the new path). Expected: PASS.
- [ ] **Step 5: Commit** `feat(payouts): payout and validation webhooks`.

### Task 5: Admin money controls and the stuck-payout poll

**Files:**
- Create in `payouts/`: `AdminPayoutsController.java`, `FreezeRequest.java`, `PayoutMaintenanceJob.java`, `PayoutMaintenanceService.java`
- Modify: `payments/RefundService.java` (refuse `MONEY_FROZEN` 409 via `PaymentApiException` before creating a refund)
- Test: `AdminPayoutsControllerTest.java`, `PayoutMaintenanceServiceTest.java`, `RefundServiceTest.java` (add one case)

**Interfaces:**
- Produces, all `ADMIN` only:
  - `POST /api/v1/admin/orgs/{id}/money/freeze` with `{reason}` (required, 1–500 characters) and `POST /api/v1/admin/orgs/{id}/money/unfreeze`. Both return `OrgMoneySettingsResponse`, extended with `moneyFrozenAt` and `moneyFrozenReason`. Freezing twice keeps the first time.
  - `GET /api/v1/admin/withdrawals?status=&orgId=&limit=100` → `List<AdminWithdrawalView>`, which is `WithdrawalView` plus `orgId` and `orgName`.
  - `GET /api/v1/admin/payout-accounts?status=FAILED` → active accounts only, `PayoutAccountView` plus `orgId`, `orgName` and `legalName`.
  - `POST /api/v1/admin/payout-accounts/{id}/approve` with `{note}` (required). Only an active `FAILED` row with `failure_reason = NAME_MISMATCH` can be approved; anything else is 409 `ACCOUNT_NOT_APPROVABLE`. Approval sets `VERIFIED`, `approved_by_user_id`, `approval_note` and `verified_at`.
- Produces: `PayoutMaintenanceService.run(Instant now)`, called every 5 minutes by `PayoutMaintenanceJob` (ShedLock `payoutMaintenance`, gated on `agentstore.jobs.enabled`). It does two things:
  1. For each `PENDING` account with a validation id older than 1 minute, call `fetchValidation` and then `applyValidation`.
  2. For each `PROCESSING` withdrawal with `submitted_at` older than `stuck-after`, call `fetchPayout` and then `PayoutWebhookProcessor.settle`. If it is still not final, log ERROR and record the issue `WITHDRAWAL_STUCK` (provider ref = payout id).

- [ ] **Step 1: Write the failing tests**
  - `AdminPayoutsControllerTest`:
    - `freezeBlocksWithdrawAndUnfreezeAllowsIt`;
    - `freezeNeedsReason`;
    - `nonAdminGets403OnEveryEndpoint`;
    - `approveNameMismatchVerifiesAndAllowsWithdraw`;
    - `approveVerifiedOrValidationFailedAccountIs409`;
    - `withdrawalsFilterByStatusAndOrg`.
  - `PayoutMaintenanceServiceTest`:
    - `pendingValidationIsCompletedByPoll`;
    - `stuckWithdrawalIsSettledByPoll`;
    - `stuckWithdrawalStillProcessingRecordsIssue` (pass `now` = submitted + 25h).
  - `RefundServiceTest.frozenOrgCannotRefund`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement the controller, freeze fields on `Org`, maintenance service and job, and the refund guard.**
- [ ] **Step 4: Run them, plus `RefundControllerTest`.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(payouts): admin freeze, account approval, withdrawals list, stuck poll`.

### Task 6: Payouts in nightly reconciliation

**Files:**
- Create: `payouts/PayoutReconciliation.java`
- Modify: `payments/ReconciliationService.java` (call it from `reconcile(LocalDate, Instant)` after the payment checks)
- Test: `payouts/PayoutReconciliationTest.java`, plus one case in `payments/ReconciliationServiceTest.java`

**Interfaces:**
- Produces: `PayoutReconciliation.check(LocalDate day, Instant now): int`. It returns the number of issues found and records each one through `ReconciliationIssues.record(day, kind, providerRef, detail)`.
  - The provider call (`fetchPayouts(day)`) happens before any transaction.
  - A provider payout whose `idempotencyKey` is not one of our withdrawal ids, and that is older than 1 hour at `now`, is `PAYOUT_UNMATCHED`.
  - A status disagreement is `PAYOUT_MISMATCH`. Provider `PROCESSED`↔`PROCESSED`, `REVERSED`↔`REVERSED`, `FAILED`↔`FAILED`, and `QUEUED|PROCESSING`↔`PROCESSING|REQUESTED` all agree.
  - An amount difference is `PAYOUT_AMOUNT_MISMATCH`.
  - A local `PROCESSED` withdrawal with `processed_at` on `day` that the provider list lacks is `PAYOUT_MISSING_AT_PROVIDER`.
  - The existing `LEDGER_CHECK_FAILED` checks already cover the payout postings.

- [ ] **Step 1: Write the failing tests**
  - `PayoutReconciliationTest`: one test per issue kind above, plus `matchingPayoutsRecordNothing` and `repeatRunRecordsOnce`.
  - `ReconciliationServiceTest.reconcileIncludesPayoutIssues`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** `FakePayoutProvider.fetchPayouts` returns the payouts it created, filtered by `createdAt` day. Tests can also inject extra payouts through `FakePayoutProvider.addExternalPayout(ProviderPayout)`.
- [ ] **Step 4: Run them, plus `AdminPaymentsControllerTest`.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(payouts): reconcile payouts nightly`.

### Task 7: RazorpayX adapter

**Files:**
- Modify: `payouts/RazorpayXPayoutProvider.java` (replace the stub)
- Test: `payouts/RazorpayXPayoutProviderTest.java` (`MockRestServiceServer`, like `RazorpayPaymentProviderTest`)

**Interfaces:**
- Produces: every `PayoutProvider` method against the RazorpayX REST API.
  - Auth is HTTP Basic with key id and secret.
  - Calls:
    - `POST /v1/contacts` (`type: vendor`, `reference_id: orgId`);
    - `POST /v1/fund_accounts` (`account_type: bank_account|vpa`);
    - `POST /v1/fund_accounts/validations` (`account_number` = the RazorpayX account number; `amount: 100` paise for a bank account; `fund_account.id`);
    - `GET /v1/fund_accounts/validations/{id}`;
    - `POST /v1/payouts` (`account_number`, `fund_account_id`, `amount` in paise, `currency: INR`, `mode`, `purpose: payout`, `queue_if_low_balance: false`, `reference_id: withdrawalId`), with header `X-Payout-Idempotency: {idempotencyKey}`;
    - `GET /v1/payouts/{id}`;
    - `GET /v1/payouts?account_number=…&from=…&to=…&count=100&skip=…`, paged until a short page.
  - The webhook signature is HMAC-SHA256 hex of the raw body under `webhook-secret`, in `X-Razorpay-Signature`, compared in constant time.
  - Event mapping:
    - `payout.processed` → `PAYOUT_PROCESSED`, with `utr` and fees (fees + tax, rupees);
    - `payout.reversed` → `PAYOUT_REVERSED`;
    - `payout.failed` and `payout.rejected` → `PAYOUT_FAILED`;
    - `fund_account.validation.completed` → `VALIDATION_COMPLETED`, with `results.registered_name`, or `VALIDATION_FAILED` when `results.account_status` is not `active`;
    - `fund_account.validation.failed` → `VALIDATION_FAILED`;
    - anything else → `OTHER`.
  - Errors:
    - 5xx, timeouts, 401 and 403 → `PayoutProviderUnavailableException`;
    - a 400 whose `error.description` contains `insufficient` (case-insensitive) → `PayoutInsufficientBalanceException`;
    - any other 4xx → `PayoutProviderRejectedException`;
    - 404 on a fetch → `Optional.empty()`.
  - Timeouts: 5 s connect, 15 s read.
  - Never log the request body (it holds the account number) or the secrets.

- [ ] **Step 1: Write the failing tests**, one per call: the request shape, the paise conversion (`1234.56` → `123456`), the idempotency header and response mapping.
  - `webhookSignatureVerifiesAndTamperedFails`;
  - `eachEventMaps` (use recorded payloads saved under `src/test/resources/razorpayx/`);
  - `insufficientBalanceMaps`;
  - `unauthorizedIsUnavailable`;
  - `fetchPayoutsPages`;
  - `logsNeverContainAccountNumber` (capture with `OutputCaptureExtension`).
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, plus `PayoutProviderConfigTest`.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(payouts): RazorpayX payout adapter`.

### Task 8: Earnings screens

**Files:**
- Modify: `frontend/src/lib/api/types.ts`, `frontend/src/lib/api/money.ts`, `frontend/src/app/(app)/developer/earnings/page.tsx`, `frontend/src/features/earnings/EarningsView.tsx`, `earnings.module.css`
- Create in `frontend/src/features/earnings/`: `PayoutAccountPanel.tsx`, `WithdrawForm.tsx`, `WithdrawalHistory.tsx`, each with a `.test.tsx`
- Create: `frontend/src/app/(app)/developer/earnings/actions.ts`

**Interfaces:**
- Consumes: Tasks 2–4 endpoints.
- Produces:
  - Types `PayoutAccount`, `PayoutsSummary`, `Withdrawal` matching the Java views.
  - Client functions `getPayoutsSummary()`, `addPayoutAccount(body)`, `createWithdrawal({requestId, amount})`, `listWithdrawals()`, `simulatePayout(withdrawalId, outcome)`.
  - Server actions `savePayoutAccount`, `withdraw` and `simulatePayoutAction`, each returning `{…} | {error}` like `billing/actions.ts`. `withdraw` takes the `requestId` from the form (`crypto.randomUUID()`, made once per opened form) and revalidates `/developer/earnings`.

Screen rules (follow the "Earnings and payouts" board in `fe-mockups/latest/Karya — Agentic UI.html`):
- **Payout account panel**:
  - no account → "Add payout account" form, with a Bank/UPI toggle, holder name, and account number + IFSC or UPI ID;
  - `PENDING` → "Checking your account…";
  - `VERIFIED` → "Bank ••1234 · IFSC" or the VPA, plus "Name on account: {validatedName}";
  - `FAILED` with `NAME_MISMATCH` → "The bank says this account belongs to {validatedName}, which does not match {your verified name}. Karya will review it, or add a different account.";
  - other `FAILED` → the reason and "Try another account".
  - Show "{n} tries left today" next to the form.
  - The page never shows a full account number.
- **Withdraw**: shown in the existing "Available to withdraw" section.
  - The button appears only when `canWithdraw`. Otherwise a one-line reason maps each `blockedReason` to plain text:
    - `NO_VERIFIED_PAYOUT_ACCOUNT` → "Add and verify a payout account first";
    - `INSUFFICIENT_EARNINGS` → "You can withdraw once you have ₹100";
    - `DAILY_LIMIT_REACHED` → "You've reached today's ₹2,00,000 limit";
    - `MONEY_FROZEN` → "Withdrawals are paused on your account. Contact support.";
    - `DEVELOPER_NOT_VERIFIED` → "Get verified to withdraw".
    - Amounts in these messages come from the summary.
  - The form: amount (prefilled with available, capped at available and the daily limit left), then a confirmation step "Send ₹X to Bank ••1234?", then submit.
  - A negative balance keeps the existing "You owe" line and shows no button (Review Focus 4).
- **History**: date, amount, account label, status (`Sending`, `Sent`, `Returned`, `Failed`), and UTR when sent. A dev-only "Simulate" menu appears on `PROCESSING` rows when the summary's provider is fake.

- [ ] **Step 1: Write the failing tests**, one per component:
  - `PayoutAccountPanel`: each status renders its copy; the form validates IFSC and VPA client-side; it never renders more than 4 digits.
  - `WithdrawForm`: amount over available is disabled; the confirmation shows the account label; a repeated submit uses the same `requestId`.
  - `WithdrawalHistory`: status labels and UTR.
  - `EarningsView`: negative balance → no Withdraw button; each `blockedReason` → its copy.
- [ ] **Step 2: Run them.** `npx vitest run src/features/earnings`. Expected: FAIL.
- [ ] **Step 3: Implement.** The page loads `getEarnings` and `getPayoutsSummary` + `listWithdrawals` in parallel.
- [ ] **Step 4: Run them, plus `npx tsc --noEmit` and `npm run lint`.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(frontend): payout account, withdraw and history on Earnings`.

### Task 9: Admin screens

**Files:**
- Modify: `frontend/src/features/adminmoney/AdminMoneyView.tsx` (+ test), `frontend/src/app/(app)/admin/money/actions.ts`, `frontend/src/ui/Rail.tsx` (+ test), `lib/api/types.ts`, `lib/api/money.ts`
- Create: `frontend/src/app/(app)/admin/payouts/page.tsx` + `actions.ts`, `frontend/src/features/adminpayouts/AdminPayoutsView.tsx` + test + `adminpayouts.module.css`, `frontend/src/app/(app)/admin/reconciliation/page.tsx` + `actions.ts`, `frontend/src/features/adminreconciliation/ReconciliationView.tsx` + test

**Interfaces:**
- Consumes: Task 5 endpoints and WS10b's `GET/POST /api/v1/admin/payments/reconciliation[/run|/{id}/resolve]`.
- Produces:
  - **Admin Money org section:** a "Freeze money" control with a required reason; when frozen, a banner "Frozen {date}: {reason}" and an "Unfreeze" button.
  - **`/admin/payouts`:**
    - "Accounts to review", listing name-mismatched accounts with the legal name vs. the bank name and an Approve button with a required note;
    - "Withdrawals", with a status filter (default `REQUESTED` + `PROCESSING`) and columns org, amount, account label, status, UTR, created.
  - **`/admin/reconciliation`:**
    - a date picker (default yesterday UTC), the issue list (kind, ref, detail as key: value, created, resolved) and "Run now";
    - "Resolve" on each issue with a required note.
  - **Rail:** "Payouts" and "Reconciliation" next to "Money", admin only.

- [ ] **Step 1: Write the failing tests:**
  - freeze needs a reason, and the frozen banner shows;
  - approve needs a note;
  - the withdrawal filter changes the query;
  - reconciliation lists issues and resolve needs a note;
  - Rail shows the links only for `ADMIN`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run `npx vitest run`, `npx tsc --noEmit` and `npm run lint`.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(frontend): admin payouts, freezes and reconciliation screens`.

### Task 10: Developer agreement, docs and status

**Files:**
- Create: `docs/legal/developer-agreement.md`
- Modify:
  - `verification/DeveloperAgreement.java` (bump `CURRENT_VERSION` to `"2.2"`), plus any test asserting `"2.1"`;
  - the developer verification agreement step in the frontend: link to `/legal/developer-agreement`;
  - `docs/payments.md`: a "Payouts" section with the flow, the config keys, the fake provider rules from Task 1, dev simulation, and a RazorpayX test-mode checklist (contact → fund account → penny drop → payout → webhook → reconciliation). Add go-live gates: enable RazorpayX, auto-settlement from the Razorpay settlement account into RazorpayX (the runbook step from spec §3 "Funding RazorpayX"), set the webhook URL `/webhooks/payouts/razorpayx`, set the secrets, and get CA sign-off;
  - `docs/known-issues.md`: validation fees not ledgered; no org suspension concept; the agreement wording is a draft for counsel;
  - `docs/status.md`;
  - `.dev/env.example` (or whatever `dev-up.sh` reads) with the blank `RAZORPAYX_ACCOUNT_NUMBER` and `RAZORPAYX_WEBHOOK_SECRET`.
- Create: `frontend/src/app/legal/developer-agreement/page.tsx` (public, renders the markdown text; reuse whatever markdown rendering the frontend already has, or plain paragraphs).

**Interfaces:** none new.

Agreement text (draft for counsel, from spec §4):
- the developer is paid for services Karya resells;
- an earnings balance is money owed for services, not a deposit;
- withdrawals go only to a verified account in the developer's own name;
- Karya may freeze withdrawals while it investigates fraud or a dispute;
- tax may be withheld when the law requires it.

Also add the buyer-terms clause that wallet money is prepayment for services, refundable only to its source, wherever buyer sign-up terms live. If there are none, add `docs/legal/buyer-terms.md` with that clause and note it in `known-issues.md`.

`docs/status.md`:
- WS10c row → `Done (manual RazorpayX test-mode check pending)`;
- the Earnings and Admin Money frontend rows' "Left to do" → `—` (or the remaining manual check);
- "Next up" → (1) the manual Razorpay + RazorpayX test-mode checks, (2) WS16 disputes;
- bump "Last updated".

- [ ] **Step 1: Write the agreement, buyer clause, route and docs; bump the version.**
- [ ] **Step 2: Run the full backend suite** (`mvn test`) **and the frontend suite** (`npx vitest run && npx tsc --noEmit && npm run lint`). Expected: PASS, apart from the known Colima clock-skew failures listed in `docs/status.md`. Name any other failure and fix it.
- [ ] **Step 3: Commit** `docs: payouts runbook, developer agreement 2.2, status`.
